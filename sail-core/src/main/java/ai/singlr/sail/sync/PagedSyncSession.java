/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.engine.SemVer;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.EraseRequests;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * The protocol-4 session: main's change log arrives one bounded page at a time and each page is its
 * own engine round, so a round costs O(what changed) and a node can be seeded from any history
 * size. For one type the session reads {@link SyncWire.Tips} once, pulls pages after the local
 * checkpoint only when main's tip has moved past it, and reconciles each page as its own engine
 * round. Every adoption is one atomic store operation and the checkpoint advances only after the
 * page, so a round that dies mid-page re-pulls that page and re-adopts nothing: an entity already
 * at main's rev converges without a write. No transaction spans the wire — the node's database
 * stays open to its API and CLI while pages and pushes cross the channel. It then asks main for the
 * rows of everything this node changed that no page covered ({@link SyncWire.Need}), so the engine
 * sees main's real state for a local edit rather than guessing, and pushes in batches bounded by
 * the frame.
 *
 * <p>A page's view of main answers the engine from the page alone; a checkpoint only ever advances
 * to a seq whose entries this node has actually seen, never to main's high-water after its own
 * pushes, so a change another node lands between two exchanges can never be skipped. The view
 * weighs every offer as its bytes on the wire and budgets the engine one frame of them, so a first
 * upload of a large table holds one batch of snapshots at a time, never the whole table.
 *
 * <p>Erasures never reach the engine. The prunes this node asked for go to main first, as erase
 * offers, and each one main answers is applied here at main's rev; an erasure entry in a page, a
 * need answer or a refreshed view is applied the moment it arrives ({@link Erasure#adopt}) and
 * handed to the engine only as the absent entity it now is. After each type the session compacts
 * the history of what the round touched and, when it adopted a deletion or compacted anything,
 * collects the content left unreferenced, reporting the bytes freed.
 */
public final class PagedSyncSession implements SyncSession {

  /** Entries asked for per pull; the frame bound, not this, is what caps a page's size. */
  static final int PAGE_LIMIT = 2000;

  private final InputStream in;
  private final OutputStream out;
  private final String mainId;
  private final int frame;
  private final SyncEngine engine = new SyncEngine();
  private Map<String, Long> tips;
  private Map<String, Asked> asked;
  private BlobStore blobs;
  private Erasure erasure;
  private EraseRequests eraseRequests;
  private Map<String, Set<String>> contentFields = Map.of();
  private long fetchedBytes;
  private long sentBytes;
  private boolean sawTombstone;
  private int adopted;
  private Set<String> touched = Set.of();
  private String broken;

  PagedSyncSession content(Sqlite db) {
    blobs = new BlobStore(db);
    erasure = new Erasure(db);
    eraseRequests = new EraseRequests(db);
    var fields = new LinkedHashMap<String, Set<String>>();
    for (var entity : SyncedEntities.all())
      fields.put(entity.type(), entity.store(db).contentFields());
    contentFields = Map.copyOf(fields);
    return this;
  }

  PagedSyncSession(InputStream in, OutputStream out, String mainId, int frame) {
    this.in = Objects.requireNonNull(in, "in");
    this.out = Objects.requireNonNull(out, "out");
    this.mainId = Objects.requireNonNull(mainId, "mainId");
    this.frame = frame;
  }

  /** This session with pushes and need requests cut at {@code frame} bytes; a test seam. */
  PagedSyncSession frame(int frame) {
    var copy = new PagedSyncSession(in, out, mainId, frame);
    copy.tips = tips;
    copy.blobs = blobs;
    copy.erasure = erasure;
    copy.eraseRequests = eraseRequests;
    copy.asked = asked;
    copy.contentFields = contentFields;
    return copy;
  }

  static PagedSyncSession open(
      InputStream in,
      OutputStream out,
      SyncWire.Hello hello,
      SyncWire.Welcome welcome,
      Consumer<String> notice) {
    if (welcome.protocol() != SyncWire.PROTOCOL) {
      throw new SyncTransportException(
          "refused",
          "hello: main speaks sync protocol "
              + welcome.protocol()
              + ", this node speaks "
              + SyncWire.PROTOCOL
              + ": upgrade main first, then nodes",
          null);
    }
    if (welcome.mainId() == null || welcome.mainId().isBlank()) {
      throw new SyncTransportException("hello: welcome names no main box id");
    }
    var mainVersion = SemVer.tryParse(welcome.version());
    var nodeVersion = SemVer.tryParse(hello.version());
    if (mainVersion.isPresent()
        && nodeVersion.isPresent()
        && mainVersion.get().compareTo(nodeVersion.get()) < 0) {
      notice.accept(
          "this node is sail "
              + hello.version()
              + " but main is "
              + welcome.version()
              + "; upgrade main first, then nodes.");
    }
    return new PagedSyncSession(in, out, welcome.mainId(), SyncWire.MAX_FRAME);
  }

  @Override
  public TypeReport reconcile(String type, LocalReplica local) {
    return onLiveChannel(
        () -> {
          if (blobs == null) return reconcileType(type, local);
          TypeReport report;
          try (var scope = blobs.retain()) {
            report = reconcileType(type, local);
          }
          return report.withFreedBytes(collect(type));
        });
  }

  /**
   * Compacts what the type's round touched and, after a deletion or an erasure, collects the
   * content left unreferenced. Housekeeping on data already synced: a failure here is reported and
   * left to the next round or {@code sail sync gc}, never charged to the type.
   */
  private long collect(String type) {
    try {
      return blobs.gc(BlobStore.Compaction.of(type, touched), sawTombstone || adopted > 0).freed();
    } catch (RuntimeException e) {
      System.err.println(
          "  [sync] "
              + type
              + ": could not collect content; 'sail sync gc' frees it: "
              + e.getMessage());
      return 0;
    }
  }

  /**
   * Every exchange on this channel after it broke fails naming why. The channel breaks when a
   * failure escapes while main still owes, or is still reading, part of a reply ({@link #owed}): a
   * chunk refused with its bytes and the run's {@code done} unread, main left waiting for
   * manifests, a line that never arrived. The next request would then read another type's answer as
   * its own. A failure that arrives as a complete reply — main refusing a type or an upload — or
   * one raised before anything was sent leaves the channel where it was, and the round, which
   * reports each type separately, carries on over it.
   */
  private <T> T onLiveChannel(Supplier<T> exchange) {
    if (broken != null) {
      throw new SyncTransportException(
          "unreachable",
          "channel unusable after an earlier failure (" + broken + "); run sail sync again",
          null);
    }
    return exchange.get();
  }

  private <T> T owed(Supplier<T> reply) {
    try {
      return reply.get();
    } catch (RuntimeException e) {
      broken = e.getMessage();
      throw e;
    }
  }

  private TypeReport reconcileType(String type, LocalReplica local) {
    var fetchedBefore = fetchedBytes;
    var sentBefore = sentBytes;
    sawTombstone = false;
    touched = Set.of();
    var ask = askedFor(type);
    adopted = ask.adopted();
    var tip = tips().get(type);
    if (tip == null) {
      throw new SyncTransportException("refused", type + ": main does not sync this type", null);
    }
    if (!ask.refusals().isEmpty()) {
      throw new SyncTransportException(
          "refused", type + ": main refused to prune " + String.join("; ", ask.refusals()), null);
    }
    var since = local.checkpoint(mainId);
    var report = SyncEngine.Report.NONE;
    var pages = 0;
    var entries = 0;
    var seen = new LinkedHashSet<String>();
    if (tip > since) {
      SyncWire.Page page;
      do {
        page = page(new SyncWire.Pull(type, since, PAGE_LIMIT), type);
        if (!page.done() && page.next() <= since) {
          throw new SyncTransportException(
              "protocol",
              type + ": main paged nothing past seq " + since + " yet is not done",
              null);
        }
        fetchContent(type, page.entries());
        seen.addAll(ids(page.entries()));
        var live = adoptErasures(type, page.entries());
        report = report.plus(reconcile(local, ids(live), new PageView(type, live, page.next())));
        pages++;
        entries += page.entries().size();
        since = page.next();
      } while (!page.done());
    }
    var dirty = new LinkedHashSet<>(local.dirtyIds());
    dirty.removeAll(seen);
    if (!dirty.isEmpty()) {
      report = report.plus(reconcileDirty(type, local, List.copyOf(dirty), since));
    }
    var late = askAfterPush(type);
    var reconciled = new LinkedHashSet<>(seen);
    reconciled.addAll(dirty);
    touched = reconciled;
    return new TypeReport(
        type,
        report.plus(new SyncEngine.Report(adopted, 0, 0, 0)),
        pages,
        entries,
        pages == 0 && dirty.isEmpty() && ask.count() + late.count() == 0,
        null,
        fetchedBytes - fetchedBefore,
        sentBytes - sentBefore);
  }

  /** What asking main to erase one type's pending prunes came to. */
  private record Asked(int count, int adopted, List<String> refusals) {
    static final Asked NONE = new Asked(0, 0, List.of());
  }

  /**
   * What asking main to erase {@code type}'s pending prunes came to. The first type of a session
   * asks for every type, before main's tips are read, so the erasure rows main writes for them —
   * and for everything that belongs to them, in other types — page in this same round. A prune of
   * an entity with a change main has not taken yet waits for its type's push ({@link
   * #askAfterPush}): main decides on its own copy, which must first hold the archive the prune
   * relies on.
   */
  private Asked askedFor(String type) {
    if (asked == null) {
      asked = new HashMap<>();
      for (var entity :
          eraseRequests == null ? List.<SyncedEntities.Entity>of() : SyncedEntities.all()) {
        var ids =
            eraseRequests.pending(entity.type()).stream()
                .filter(id -> !erasure.unpushed(new Erasure.Target(entity.type(), id)))
                .toList();
        if (!ids.isEmpty()) {
          asked.put(entity.type(), askErasures(entity.type(), ids));
          tips = null;
        }
      }
    }
    return asked.getOrDefault(type, Asked.NONE);
  }

  /**
   * Offers main the prunes of {@code type} that waited for this type's push, now that main holds
   * what they rely on, and applies each erasure main answers. Later types read main's tips afresh,
   * so what belongs to the erased entities still pages this round. A refusal fails the type once
   * its pages and push are done.
   */
  private Asked askAfterPush(String type) {
    var ids = eraseRequests == null ? List.<String>of() : eraseRequests.pending(type);
    if (ids.isEmpty()) {
      return Asked.NONE;
    }
    var late = askErasures(type, ids);
    tips = null;
    adopted += late.adopted();
    if (!late.refusals().isEmpty()) {
      throw new SyncTransportException(
          "refused", type + ": main refused to prune " + String.join("; ", late.refusals()), null);
    }
    return late;
  }

  /**
   * Offers main the prunes this node asked for of {@code type}, in frame-bounded batches, and
   * applies each erasure main answers at its rev. A refusal drops its request — main decided on its
   * own copy — and fails the type's reconcile, naming the reason, once every answer is applied.
   */
  private Asked askErasures(String type, List<String> ids) {
    var asks = ids.stream().map(MainReplica.Offer::erasure).toList();
    var refusals = new ArrayList<String>();
    var applied = 0;
    for (var offset = 0; offset < asks.size(); ) {
      var offers = fitting(asks, offset, SyncWire::encodedLength);
      var batch = offers.stream().map(MainReplica.Offer::id).toList();
      var response = owed(() -> Rpc.exchange(in, out, new SyncWire.Push(type, offers)));
      if (!(response instanceof SyncWire.Results results)
          || results.results().size() != batch.size()) {
        throw unexpected(type, "a result per erase offer", response);
      }
      for (var i = 0; i < batch.size(); i++) {
        var id = batch.get(i);
        switch (results.results().get(i)) {
          case SyncWire.Accepted accepted when accepted.id().equals(id) -> {
            if (erasure.adopt(type, id, accepted.rev())) applied++;
            eraseRequests.drop(type, id);
          }
          case SyncWire.Refused refused when refused.id().equals(id) -> {
            eraseRequests.drop(type, id);
            refusals.add(id + ": " + refused.reason());
          }
          default ->
              throw new SyncTransportException(
                  "protocol",
                  type + ": main answered " + results.results().get(i) + " for erasing " + id,
                  null);
        }
      }
      offset += batch.size();
    }
    return new Asked(ids.size(), applied, List.copyOf(refusals));
  }

  /**
   * Applies every erasure among {@code entries} this node does not hold yet, at main's rev, and
   * returns what the engine still reconciles: every other entry, and each erasure this node already
   * held — as the absent entity at the erasure's rev, which this node's own erasure already
   * matches.
   */
  private List<SyncWire.Entry> adoptErasures(String type, List<SyncWire.Entry> entries) {
    var remaining = new ArrayList<SyncWire.Entry>(entries.size());
    for (var entry : entries) {
      if (!entry.erased()) {
        remaining.add(entry);
        continue;
      }
      if (erasure == null) {
        throw new SyncTransportException(
            "protocol",
            type + " " + entry.id() + ": an erasure arrived on a session with no store",
            null);
      }
      if (erasure.adopt(type, entry.id(), entry.rev())) {
        adopted++;
      } else {
        remaining.add(entry);
      }
    }
    return remaining;
  }

  /**
   * Asks main for its rows of the node's locally changed ids, as many per request as the frame
   * admits, continuing from wherever main stopped consuming, and reconciles each answer scoped to
   * exactly the ids asked — an id main omitted is one it has never seen, which the engine pushes as
   * new.
   */
  private SyncEngine.Report reconcileDirty(
      String type, LocalReplica local, List<String> ids, long checkpoint) {
    var reports = new ArrayList<SyncEngine.Report>();
    need(
        type,
        ids,
        (consumed, entries) -> {
          fetchContent(type, entries);
          var remaining = adoptErasures(type, entries);
          var erasedNow = ids(entries);
          erasedNow.removeAll(ids(remaining));
          var scope = new LinkedHashSet<>(consumed);
          scope.removeAll(erasedNow);
          reports.add(reconcile(local, scope, new PageView(type, remaining, checkpoint)));
        });
    return reports.stream().reduce(SyncEngine.Report.NONE, SyncEngine.Report::plus);
  }

  /**
   * Asks main for its rows of {@code ids}, as many per request as the frame admits, continuing from
   * wherever main stopped consuming, and hands each answer on with the ids it covers — an id main
   * omitted from its answer is one it has never seen.
   */
  private void need(
      String type, List<String> ids, BiConsumer<List<String>, List<SyncWire.Entry>> onAnswer) {
    var offset = 0;
    while (offset < ids.size()) {
      var asked = askable(ids, offset);
      var answer = page(new SyncWire.Need(type, asked), type);
      if (answer.next() <= 0) {
        throw new SyncTransportException(
            "protocol", type + ": main consumed none of " + asked.size() + " needed ids", null);
      }
      var consumed = asked.subList(0, (int) Math.min(answer.next(), asked.size()));
      onAnswer.accept(consumed, answer.entries());
      offset += consumed.size();
    }
  }

  private void fetchContent(String type, List<SyncWire.Entry> entries) {
    sawTombstone |= entries.stream().anyMatch(entry -> entry.deleted() && !entry.erased());
    var fields = contentFields.getOrDefault(type, Set.of());
    if (fields.isEmpty()) return;
    var hashes =
        BlobStore.referenced(entries.stream().map(SyncWire.Entry::snapshot).toList(), fields);
    for (var hash : blobs.missing(hashes)) {
      fetchBlob(type, hash);
    }
  }

  /**
   * Brings one blob home end to end — its manifest, the chunks this box lacks, the assembled whole
   * — before the next is asked for, so what a page's content costs in memory is one manifest,
   * however many files the page names and however many chunks each has. A chunk two blobs share
   * crosses once: the second blob's lookup finds it already held.
   */
  private void fetchBlob(String type, String hash) {
    var context = type + " blob " + hash;
    var receiver =
        new ContentReceiver(in, () -> content(Rpc.receive(in, context), context), blobs, context);
    var manifest =
        owed(
            () -> {
              Rpc.send(out, SyncWire.encode(new SyncWire.Fetch(List.of(hash))));
              return receiver.manifests(Set.of(hash), ignored -> {}).get(hash);
            });
    var pending = List.copyOf(blobs.missingChunks(manifest.chunkHashes()));
    var remainingBytes = manifest.size();
    for (var offset = 0; offset < pending.size(); ) {
      var batch = askable(pending, offset);
      remainingBytes -= receiveChunks(receiver, batch, remainingBytes);
      offset += batch.size();
    }
    try {
      blobs.assemble(manifest);
    } catch (RuntimeException e) {
      throw new SyncTransportException("protocol", context + ": " + e.getMessage(), e);
    }
  }

  private long receiveChunks(ContentReceiver receiver, List<String> batch, long remainingBytes) {
    var stored =
        owed(
            () -> {
              Rpc.send(out, SyncWire.encode(new SyncWire.FetchChunks(batch)));
              return receiver.chunks(new LinkedHashSet<>(batch), remainingBytes);
            });
    fetchedBytes += stored;
    return stored;
  }

  private static SyncWire.Content content(SyncWire.Response response, String context) {
    if (response instanceof SyncWire.Content content) return content;
    throw unexpected(context, "content line", response);
  }

  private void sendContent(String type, List<MainReplica.Offer> offers) {
    var fields = contentFields.getOrDefault(type, Set.of());
    if (fields.isEmpty()) return;
    var hashes =
        BlobStore.referenced(offers.stream().map(MainReplica.Offer::snapshot).toList(), fields);
    if (hashes.isEmpty()) return;
    var context = type + " " + offers.stream().map(MainReplica.Offer::id).toList();
    var response = owed(() -> Rpc.exchange(in, out, new SyncWire.Announce(List.copyOf(hashes))));
    if (!(response instanceof SyncWire.Lack lack)) throw unexpected(context, "lack", response);
    if (!hashes.containsAll(lack.hashes())
        || new LinkedHashSet<>(lack.hashes()).size() != lack.hashes().size())
      throw new SyncTransportException("Unexpected missing blobs " + lack.hashes());
    if (lack.hashes().isEmpty()) return;
    owed(
        () -> {
          uploadRun(lack.hashes(), context);
          return null;
        });
  }

  private void uploadRun(List<String> lacked, String context) {
    var chunks = new LinkedHashSet<String>();
    for (var hash : lacked) {
      var manifest = blobs.manifest(hash);
      Rpc.send(out, SyncWire.encode(new SyncWire.Manifest(manifest)));
      chunks.addAll(manifest.chunkHashes());
    }
    Rpc.send(out, SyncWire.encode(new SyncWire.Done()));
    var ready = Rpc.receive(in, context + " blobs " + lacked);
    if (!(ready instanceof SyncWire.Lack requested)) throw unexpected(context, "lack", ready);
    if (!chunks.containsAll(requested.hashes())
        || new LinkedHashSet<>(requested.hashes()).size() != requested.hashes().size())
      throw new SyncTransportException(
          context + ": unexpected missing chunks " + requested.hashes());
    for (var hash : requested.hashes()) {
      var bytes = blobs.chunk(hash);
      try {
        SyncWire.writeChunk(out, hash, bytes);
      } catch (IOException e) {
        throw new SyncTransportException("unreachable", "chunk " + hash + ": " + e.getMessage(), e);
      }
      sentBytes += bytes.length;
    }
    Rpc.send(out, SyncWire.encode(new SyncWire.Done()));
    var accepted = Rpc.receive(in, context + " blobs " + lacked);
    if (!(accepted instanceof SyncWire.Done)) throw unexpected(context, "done", accepted);
  }

  private List<String> askable(List<String> ids, int offset) {
    return fitting(ids, offset, SyncWire::encodedLength);
  }

  /** The items from {@code offset} on that fit one frame, at least one, by their encoded length. */
  private <T> List<T> fitting(List<T> items, int offset, ToIntFunction<T> length) {
    var budget = new SyncWire.Frame(frame);
    var fitting = new ArrayList<T>();
    for (var i = offset; i < items.size(); i++) {
      var bytes = length.applyAsInt(items.get(i));
      if (!fitting.isEmpty() && !budget.admits(bytes)) {
        break;
      }
      budget.add(bytes);
      fitting.add(items.get(i));
    }
    return fitting;
  }

  private SyncEngine.Report reconcile(LocalReplica local, Set<String> ids, PageView view) {
    return engine.reconcile(local.scopedTo(ids), view);
  }

  private static Set<String> ids(List<SyncWire.Entry> entries) {
    var ids = new LinkedHashSet<String>();
    for (var entry : entries) {
      ids.add(entry.id());
    }
    return ids;
  }

  private Map<String, Long> tips() {
    if (tips == null) {
      var response = owed(() -> Rpc.exchange(in, out, new SyncWire.Heads()));
      if (response instanceof SyncWire.Tips t) {
        tips = t.tips();
      } else {
        throw unexpected("heads", "tips", response);
      }
    }
    return tips;
  }

  private SyncWire.Page page(SyncWire.Request request, String type) {
    var response = owed(() -> Rpc.exchange(in, out, request));
    if (response instanceof SyncWire.Page page) {
      return page;
    }
    throw unexpected(type, "page", response);
  }

  private static SyncTransportException unexpected(
      String context, String expected, SyncWire.Response response) {
    if (response instanceof SyncWire.Failed failed) {
      return new SyncTransportException(failed.kind(), context + ": " + failed.message(), null);
    }
    if (response instanceof SyncWire.Refuse refuse) {
      return new SyncTransportException("refused", context + ": " + refuse.reason(), null);
    }
    return new SyncTransportException(context + ": Expected a " + expected + ", got: " + response);
  }

  @Override
  public List<Map<String, Object>> fetchFdes() {
    return onLiveChannel(
        () -> {
          var response = owed(() -> Rpc.exchange(in, out, new SyncWire.FetchFdes()));
          if (response instanceof SyncWire.Fdes roster) {
            return roster.fdes();
          }
          throw unexpected("fde", "fde roster", response);
        });
  }

  /**
   * Ends the session: {@code bye} on a channel whose position is known; on one that is not, both
   * streams are closed. A {@code bye} written into the middle of a message would be read as that
   * message's bytes, and main may be blocked writing the rest of a reply this side stopped reading
   * — closing the input is what unblocks it, exactly as a dead ssh child does. A stream that cannot
   * even be closed was reported when the channel broke; there is nothing left to say.
   */
  @Override
  public void close() {
    if (broken == null) {
      Rpc.send(out, SyncWire.encode(new SyncWire.Bye()));
      return;
    }
    for (var stream : List.of(in, out)) {
      try {
        stream.close();
      } catch (IOException alreadyBroken) {
        continue;
      }
    }
  }

  /**
   * The engine's view of main for one page: its ids, revs and snapshots come from the page, its
   * high-water is the page's {@code next}, and its pushes go out in frame-bounded batches whose
   * verdicts update the view, so a re-reconcile after a stale adoption sees main as it now is.
   */
  private final class PageView implements MainReplica {
    private final String type;
    private final Map<String, SyncWire.Entry> entries = new LinkedHashMap<>();
    private final long high;

    PageView(String type, List<SyncWire.Entry> page, long high) {
      this.type = type;
      for (var entry : page) {
        entries.put(entry.id(), entry);
      }
      this.high = high;
    }

    @Override
    public String id() {
      return mainId;
    }

    @Override
    public Set<String> entityIds() {
      return entries.keySet();
    }

    @Override
    public Map<String, Object> current(String entityId) {
      var entry = entries.get(entityId);
      return entry == null || entry.deleted() ? null : entry.snapshot();
    }

    @Override
    public String currentRev(String entityId) {
      var entry = entries.get(entityId);
      return entry == null ? null : entry.rev();
    }

    @Override
    public State state(String entityId) {
      return new State(current(entityId), currentRev(entityId));
    }

    @Override
    public long maxSeq() {
      return high;
    }

    @Override
    public long weigh(Offer offer) {
      var hashes =
          BlobStore.referenced(
              Collections.singletonList(offer.snapshot()),
              contentFields.getOrDefault(type, Set.of()));
      var inventory = 0L;
      for (var hash : hashes) inventory += SyncWire.inventoryWeight(blobs.manifest(hash)) + 2L;
      return Math.max(SyncWire.encodedLength(offer), inventory);
    }

    @Override
    public long offerBudget() {
      return frame;
    }

    @Override
    public CommitOutcome commit(String entityId, Map<String, Object> snapshot, String expectedRev) {
      return commitAll(List.of(new Offer(entityId, snapshot, expectedRev))).getFirst();
    }

    @Override
    public List<CommitOutcome> commitAll(List<Offer> offers) {
      var outcomes = new ArrayList<CommitOutcome>(offers.size());
      var batch = new ArrayList<Offer>();
      var budget = new SyncWire.Frame(frame);
      for (var offer : offers) {
        var length = Math.toIntExact(weigh(offer));
        if (!budget.canEverAdmit(length)) {
          throw new SyncTransportException(
              "protocol",
              type
                  + " "
                  + offer.id()
                  + ": snapshot or content inventory of "
                  + length
                  + " bytes exceeds the frame of "
                  + frame
                  + " bytes",
              null);
        }
        if (!budget.admits(length)) {
          outcomes.addAll(push(batch));
          batch.clear();
          budget = new SyncWire.Frame(frame);
        }
        budget.add(length);
        batch.add(offer);
      }
      if (!batch.isEmpty()) {
        outcomes.addAll(push(batch));
      }
      return outcomes;
    }

    private List<CommitOutcome> push(List<Offer> batch) {
      sendContent(type, batch);
      var response = owed(() -> Rpc.exchange(in, out, new SyncWire.Push(type, List.copyOf(batch))));
      if (!(response instanceof SyncWire.Results results)) {
        throw unexpected(type, "results", response);
      }
      if (results.results().size() != batch.size()) {
        throw new SyncTransportException(
            "protocol",
            type
                + ": main answered "
                + results.results().size()
                + " results for "
                + batch.size()
                + " offers",
            null);
      }
      var stale = new ArrayList<String>();
      for (var i = 0; i < batch.size(); i++) {
        var result = results.results().get(i);
        if (!Objects.equals(result.id(), batch.get(i).id())) {
          throw new SyncTransportException(
              "protocol",
              type + ": main answered " + result.id() + " for " + batch.get(i).id(),
              null);
        }
        if (result instanceof SyncWire.Stale) {
          stale.add(result.id());
        }
      }
      refresh(stale);
      var outcomes = new ArrayList<CommitOutcome>(batch.size());
      for (var i = 0; i < batch.size(); i++) {
        outcomes.add(settle(batch.get(i), results.results().get(i)));
      }
      return outcomes;
    }

    /**
     * Replaces the view's entries for the ids main called stale with main's present rows, fetched
     * through the frame-bounded need path; an id main no longer knows leaves the view.
     */
    private void refresh(List<String> ids) {
      if (ids.isEmpty()) {
        return;
      }
      need(
          type,
          ids,
          (consumed, fetched) -> {
            fetchContent(type, fetched);
            adoptErasures(type, fetched);
            consumed.forEach(entries::remove);
            fetched.forEach(entry -> entries.put(entry.id(), entry));
          });
    }

    private CommitOutcome settle(Offer offer, SyncWire.Result result) {
      return switch (result) {
        case SyncWire.Accepted accepted -> {
          entries.put(
              offer.id(),
              new SyncWire.Entry(
                  0, offer.id(), accepted.rev(), offer.snapshot() == null, offer.snapshot()));
          yield new CommitOutcome.Accepted(accepted.rev());
        }
        case SyncWire.Stale _ ->
            new CommitOutcome.Rejected(currentRev(offer.id()), current(offer.id()));
        case SyncWire.Refused refused ->
            throw new SyncTransportException(
                "refused", type + " " + offer.id() + ": " + refused.reason(), null);
      };
    }
  }
}
