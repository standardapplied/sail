/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.engine.SemVer;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The protocol-4 session: main's change log arrives one bounded page at a time and each page is its
 * own engine round, so a round costs O(what changed) and a node can be seeded from any history
 * size. For one type the session reads {@link SyncWire.Tips} once, pulls pages after the local
 * checkpoint only when main's tip has moved past it, and reconciles each page inside one local
 * transaction — adoptions, conflicts and the checkpoint advance land together or not at all, so a
 * round that dies resumes at the next page with nothing re-adopted. It then asks main for the rows
 * of everything this node changed that no page covered ({@link SyncWire.Need}), so the engine sees
 * main's real state for a local edit rather than guessing, and pushes in batches bounded by the
 * frame.
 *
 * <p>A page's view of main answers the engine from the page alone; a checkpoint only ever advances
 * to a seq whose entries this node has actually seen, never to main's high-water after its own
 * pushes, so a change another node lands between two exchanges can never be skipped. The view
 * weighs every offer as its chars on the wire and budgets the engine one frame of them, so a first
 * upload of a large table holds one batch of snapshots at a time, never the whole table.
 */
public final class PagedSyncSession implements SyncSession {

  /** Entries asked for per pull; the frame bound, not this, is what caps a page's size. */
  static final int PAGE_LIMIT = 2000;

  private final Reader in;
  private final Writer out;
  private final String mainId;
  private final int frame;
  private final SyncEngine engine = new SyncEngine();
  private Map<String, Long> tips;

  PagedSyncSession(Reader in, Writer out, String mainId, int frame) {
    this.in = Objects.requireNonNull(in, "in");
    this.out = Objects.requireNonNull(out, "out");
    this.mainId = Objects.requireNonNull(mainId, "mainId");
    this.frame = frame;
  }

  /** This session with pushes and need requests cut at {@code frame} chars; a test seam. */
  PagedSyncSession frame(int frame) {
    var copy = new PagedSyncSession(in, out, mainId, frame);
    copy.tips = tips;
    return copy;
  }

  static PagedSyncSession open(
      Reader in,
      Writer out,
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
    var mainVersion = parse(welcome.version());
    var nodeVersion = parse(hello.version());
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

  private static Optional<SemVer> parse(String version) {
    try {
      return Optional.of(SemVer.parse(Objects.requireNonNull(version)));
    } catch (RuntimeException malformed) {
      return Optional.empty();
    }
  }

  @Override
  public TypeReport reconcile(String type, LocalReplica local) {
    var tip = tips().get(type);
    if (tip == null) {
      throw new SyncTransportException("refused", type + ": main does not sync this type", null);
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
        var ids = ids(page);
        seen.addAll(ids);
        report =
            report.plus(reconcile(local, ids, new PageView(type, page.entries(), page.next())));
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
    return new TypeReport(type, report, pages, entries, pages == 0 && dirty.isEmpty(), null);
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
        (consumed, entries) ->
            reports.add(
                reconcile(
                    local,
                    new LinkedHashSet<>(consumed),
                    new PageView(type, entries, checkpoint))));
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

  private List<String> askable(List<String> ids, int offset) {
    var budget = new SyncWire.Frame(frame);
    var asked = new ArrayList<String>();
    for (var i = offset; i < ids.size(); i++) {
      var length = ids.get(i).length() + 2;
      if (!asked.isEmpty() && !budget.admits(length)) {
        break;
      }
      budget.add(length);
      asked.add(ids.get(i));
    }
    return asked;
  }

  private SyncEngine.Report reconcile(LocalReplica local, Set<String> ids, PageView view) {
    return local.atomically(() -> engine.reconcile(local.scopedTo(ids), view));
  }

  private static Set<String> ids(SyncWire.Page page) {
    var ids = new LinkedHashSet<String>();
    for (var entry : page.entries()) {
      ids.add(entry.id());
    }
    return ids;
  }

  private Map<String, Long> tips() {
    if (tips == null) {
      var response = Rpc.exchange(in, out, new SyncWire.Heads());
      if (response instanceof SyncWire.Tips t) {
        tips = t.tips();
      } else {
        throw unexpected("heads", "tips", response);
      }
    }
    return tips;
  }

  private SyncWire.Page page(SyncWire.Request request, String type) {
    var response = Rpc.exchange(in, out, request);
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
    var response = Rpc.exchange(in, out, new SyncWire.FetchFdes());
    if (response instanceof SyncWire.Fdes roster) {
      return roster.fdes();
    }
    throw unexpected("fde", "fde roster", response);
  }

  @Override
  public void close() {
    Rpc.send(out, SyncWire.encode(new SyncWire.Bye()));
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
      return SyncWire.encodedLength(offer);
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
        var length = SyncWire.encodedLength(offer);
        if (!budget.canEverAdmit(length)) {
          throw new SyncTransportException(
              "protocol",
              type
                  + " "
                  + offer.id()
                  + ": snapshot of "
                  + length
                  + " chars exceeds the frame of "
                  + frame
                  + " chars",
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
      var response = Rpc.exchange(in, out, new SyncWire.Push(type, List.copyOf(batch)));
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
