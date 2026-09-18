/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.engine.SemVer;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncBoxes;
import ai.singlr.sail.store.SyncPeer;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Main's side of one sync session: a request loop over the SSH channel's stdio. Nothing is served
 * before a {@link SyncWire.Hello} has been welcomed — the protocol, the fleet floor and the box
 * identity are checked there, once. It then answers {@link SyncWire.Heads} with every type's
 * high-water, pages the change log for {@link SyncWire.Pull}, reads current rows for {@link
 * SyncWire.Need}, routes each offer of a {@link SyncWire.Push} to the authoritative {@link
 * MainReplica} for its entity type, serves the node's roster pull, and returns at {@link
 * SyncWire.Bye} or end of stream. The {@link SyncPrincipal} carries the push half of Door-2
 * authorization: a {@code viewer} opens a session and pulls every type, but its offers are refused
 * so only {@code member}+ work propagates. The principal's handle additionally binds run offers to
 * execution provenance — a session may create, update, or delete only runs stamped with its own
 * node, so no member can forge run metadata another box would treat as its own execution.
 */
public final class SyncRpcServer {

  private static final String RUN_ENTITY = "run";

  /** Main's change log as a paged pull reads it: the heads of one type after a checkpoint. */
  @FunctionalInterface
  public interface ChangeHeads {
    ChangeHeads NONE = (type, since, limit) -> List.of();

    List<ChangeLog.Head> after(String type, long since, int limit);
  }

  /** Binds a box id to the principal that first presented it; a mismatch is a refusal reason. */
  @FunctionalInterface
  public interface BoxBindings {
    BoxBindings NONE = (principal, boxId) -> Optional.empty();

    Optional<String> bind(String principal, String boxId);
  }

  private final Map<String, MainReplica> replicas;
  private final SyncPrincipal principal;
  private final FdeRoster fdeRoster;
  private final SyncTransitionSink transitionSink;
  private final ChangeHeads heads;
  private final BoxBindings bindings;
  private final String version;
  private boolean welcomed;

  public SyncRpcServer(MainReplica main, boolean writable) {
    this(Map.of("spec", main), new SyncPrincipal(null, writable), FdeRoster.EMPTY);
  }

  public SyncRpcServer(MainReplica main, boolean writable, FdeRoster fdeRoster) {
    this(Map.of("spec", main), new SyncPrincipal(null, writable), fdeRoster);
  }

  public SyncRpcServer(
      Map<String, MainReplica> replicas, SyncPrincipal principal, FdeRoster fdeRoster) {
    this(replicas, principal, fdeRoster, SyncTransitionSink.NONE);
  }

  public SyncRpcServer(
      Map<String, MainReplica> replicas,
      SyncPrincipal principal,
      FdeRoster fdeRoster,
      SyncTransitionSink transitionSink) {
    this(
        replicas,
        principal,
        fdeRoster,
        transitionSink,
        ChangeHeads.NONE,
        BoxBindings.NONE,
        SyncWire.UPGRADE_FLOOR);
  }

  public SyncRpcServer(
      Map<String, MainReplica> replicas,
      SyncPrincipal principal,
      FdeRoster fdeRoster,
      SyncTransitionSink transitionSink,
      ChangeHeads heads,
      BoxBindings bindings,
      String version) {
    this.replicas = Collections.unmodifiableMap(new LinkedHashMap<>(replicas));
    this.principal = Objects.requireNonNull(principal, "principal");
    this.fdeRoster = Objects.requireNonNull(fdeRoster, "fdeRoster");
    this.transitionSink = Objects.requireNonNull(transitionSink, "transitionSink");
    this.heads = Objects.requireNonNull(heads, "heads");
    this.bindings = Objects.requireNonNull(bindings, "bindings");
    this.version = Objects.requireNonNull(version, "version");
  }

  /**
   * The server for main's database: every registered entity's authoritative replica, its change log
   * as the page source, and its box bindings, identified as {@code boxId} and built {@code
   * version}.
   */
  public static SyncRpcServer over(
      Sqlite db,
      String boxId,
      SyncPrincipal principal,
      FdeRoster fdeRoster,
      SyncTransitionSink transitionSink,
      String version) {
    var changes = new ChangeLog(db);
    return new SyncRpcServer(
        new LinkedHashMap<>(SyncedEntities.replicas(db, boxId, boxId)),
        principal,
        fdeRoster,
        transitionSink,
        changes::headsAfter,
        new SyncBoxes(db)::bind,
        version);
  }

  public void serve(Reader in, Writer out) throws IOException {
    serve(in, out, SyncWire.MAX_FRAME);
  }

  void serve(Reader in, Writer out, int frame) throws IOException {
    welcomed = false;
    var context = "session";
    while (true) {
      SyncWire.Request request;
      try {
        var line = SyncWire.readFramed(in, frame);
        if (line == null) return;
        request = SyncWire.decodeRequest(line);
        context = SyncWire.context(request);
      } catch (RuntimeException e) {
        reply(
            out,
            welcomed
                ? new SyncWire.Failed(context + ": " + rootMessage(e), "protocol")
                : new SyncWire.Refuse(upgradeRemedy()));
        continue;
      }
      if (request instanceof SyncWire.Bye) return;
      reply(out, respondTo(request, frame));
    }
  }

  /**
   * Computes one response, converting any store-side failure into a {@link SyncWire.Failed} the
   * client can read, rather than letting it propagate and drop the session with no reply — the
   * client must always be able to tell a refused offer from a broken connection. The clean {@link
   * SyncWire.Rejected} staleness path is unaffected; only thrown failures land here.
   */
  private SyncWire.Response respondTo(SyncWire.Request request, int frame) {
    var context = SyncWire.context(request);
    try {
      var response =
          switch (request) {
            case SyncWire.Hello hello -> onHello(hello);
            case SyncWire.Heads ignored -> welcomed ? tips() : helloRequired();
            case SyncWire.Pull pull -> welcomed ? page(pull, frame) : helloRequired();
            case SyncWire.Need need -> welcomed ? current(need, frame) : helloRequired();
            case SyncWire.Push push -> welcomed ? results(push) : helloRequired();
            case SyncWire.FetchFdes ignored ->
                welcomed ? new SyncWire.Fdes(fdeRoster.entries()) : helloRequired();
            case SyncWire.Bye ignored -> throw new IllegalStateException("Bye ends the session");
          };
      return response instanceof SyncWire.Failed failure
          ? new SyncWire.Failed(context + ": " + failure.message(), failure.kind())
          : response;
    } catch (RuntimeException e) {
      System.err.println("  [sync] " + context + ": " + rootMessage(e));
      return new SyncWire.Failed(context + ": " + rootMessage(e), "store");
    }
  }

  private static String rootMessage(Throwable t) {
    var root = t;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return Objects.toString(root.getMessage(), root.getClass().getSimpleName());
  }

  private static void reply(Writer out, SyncWire.Response response) throws IOException {
    out.write(SyncWire.encode(response));
    out.write('\n');
    out.flush();
  }

  private String upgradeRemedy() {
    return "upgrade to " + version + ": sail upgrade";
  }

  private static SyncWire.Refuse helloRequired() {
    return new SyncWire.Refuse("hello required");
  }

  /**
   * The one place a session's compatibility is decided. Floors compare as versions, never as
   * strings: a node below main's floor is told to upgrade, a node whose floor is above main's is
   * told the order — main first — and a node at the same floor is welcomed whatever its patch
   * level. The box id is then bound to the authenticated principal, refusing an identity another
   * key already presented.
   */
  private SyncWire.Response onHello(SyncWire.Hello hello) {
    if (welcomed) {
      return new SyncWire.Failed("hello already exchanged", "protocol");
    }
    if (hello.protocol() != SyncWire.PROTOCOL) {
      return new SyncWire.Refuse(
          "this main speaks sync protocol "
              + SyncWire.PROTOCOL
              + ", not "
              + hello.protocol()
              + ": "
              + upgradeRemedy());
    }
    var nodeFloor = parse(hello.floor());
    if (nodeFloor.isEmpty()) {
      return new SyncWire.Refuse("malformed floor '" + hello.floor() + "': " + upgradeRemedy());
    }
    var order = nodeFloor.get().compareTo(SemVer.parse(SyncWire.UPGRADE_FLOOR));
    if (order < 0) {
      return new SyncWire.Refuse(upgradeRemedy());
    }
    if (order > 0) {
      return new SyncWire.Refuse(
          "main is " + version + ", this node is " + hello.version() + ": upgrade main first");
    }
    if (hello.box() == null || hello.box().isBlank()) {
      return new SyncWire.Refuse("hello names no box id: " + upgradeRemedy());
    }
    if (principal.handle() != null) {
      var refusal = bindings.bind(principal.handle(), hello.box());
      if (refusal.isPresent()) {
        return new SyncWire.Refuse(refusal.get());
      }
    }
    welcomed = true;
    return new SyncWire.Welcome(SyncWire.PROTOCOL, version, mainId());
  }

  private static Optional<SemVer> parse(String version) {
    try {
      return Optional.of(SemVer.parse(Objects.requireNonNull(version)));
    } catch (RuntimeException malformed) {
      return Optional.empty();
    }
  }

  private String mainId() {
    return replicas.values().stream().findFirst().map(MainReplica::id).orElse("");
  }

  private SyncWire.Response tips() {
    var tips = new LinkedHashMap<String, Long>();
    replicas.forEach((type, main) -> tips.put(type, main.maxSeq()));
    return new SyncWire.Tips(tips);
  }

  /**
   * One page of {@code pull.type()}'s change log after {@code pull.since()}: the heads in seq
   * order, each read as its current row, until {@code limit} entries or the frame is full. {@code
   * next} is the last seq included, so the node checkpoints exactly what it has seen.
   */
  private SyncWire.Response page(SyncWire.Pull pull, int frame) {
    var main = replicas.get(pull.type());
    if (main == null) {
      return new SyncWire.Failed("Unknown entity type: " + pull.type());
    }
    if (pull.limit() <= 0) {
      return new SyncWire.Failed("limit must be positive, not " + pull.limit(), "protocol");
    }
    var maxSeq = main.maxSeq();
    var budget = new SyncWire.Frame(frame);
    var entries = new ArrayList<SyncWire.Entry>();
    var next = pull.since();
    for (var head : heads.after(pull.type(), pull.since(), pull.limit())) {
      var entry = entryOf(main, head.entityId(), head.seq());
      var length = SyncWire.encodedLength(entry);
      if (!budget.canEverAdmit(length)) {
        return oversize(entry.id(), length, frame);
      }
      if (!budget.admits(length)) {
        break;
      }
      budget.add(length);
      entries.add(entry);
      next = head.seq();
    }
    return new SyncWire.Page(entries, next, next >= maxSeq, maxSeq);
  }

  /**
   * Main's current rows for the ids the node changed locally, in request order, as many as fit the
   * frame; {@code next} counts the ids consumed so the node continues from there. An id main has
   * never seen is consumed and omitted.
   */
  private SyncWire.Response current(SyncWire.Need need, int frame) {
    var main = replicas.get(need.type());
    if (main == null) {
      return new SyncWire.Failed("Unknown entity type: " + need.type());
    }
    var budget = new SyncWire.Frame(frame);
    var entries = new ArrayList<SyncWire.Entry>();
    var consumed = 0;
    for (var id : need.ids()) {
      if (main.currentRev(id) == null) {
        consumed++;
        continue;
      }
      var entry = entryOf(main, id, 0);
      var length = SyncWire.encodedLength(entry);
      if (!budget.canEverAdmit(length)) {
        return oversize(id, length, frame);
      }
      if (!budget.admits(length)) {
        break;
      }
      budget.add(length);
      entries.add(entry);
      consumed++;
    }
    return new SyncWire.Page(entries, consumed, consumed == need.ids().size(), main.maxSeq());
  }

  private static SyncWire.Entry entryOf(MainReplica main, String id, long seq) {
    var current = main.current(id);
    return new SyncWire.Entry(seq, id, main.currentRev(id), current == null, current);
  }

  private static SyncWire.Failed oversize(String id, int length, int frame) {
    return new SyncWire.Failed(
        id + ": entry of " + length + " chars exceeds the frame of " + frame + " chars",
        "protocol");
  }

  private SyncWire.Response results(SyncWire.Push push) {
    if (!principal.canWrite()) {
      return new SyncWire.Failed(
          "Your role is read-only: it can pull the shared board but not push changes.");
    }
    var main = replicas.get(push.type());
    if (main == null) {
      return new SyncWire.Failed("Unknown entity type: " + push.type());
    }
    var results = new ArrayList<SyncWire.Result>();
    for (var offer : push.offers()) {
      results.add(result(push.type(), main, offer));
    }
    return new SyncWire.Results(results, main.maxSeq());
  }

  private SyncWire.Result result(String type, MainReplica main, MainReplica.Offer offer) {
    if (RUN_ENTITY.equals(type)) {
      if (principal.handle() == null || principal.handle().isBlank()) {
        return new SyncWire.Refused(
            offer.id(),
            "This sync session has no node handle, so a run cannot be attributed to it; "
                + "set the node's sync handle before pushing runs.");
      }
      if (!ownsRun(offer, main)) {
        return new SyncWire.Rejected(
            offer.id(), main.currentRev(offer.id()), main.current(offer.id()));
      }
    }
    var before = main.current(offer.id());
    var outcome =
        SyncPeer.with(
            principal.handle(),
            () -> main.commit(offer.id(), offer.snapshot(), offer.expectedRev()));
    return switch (outcome) {
      case CommitOutcome.Accepted accepted -> {
        emitTransitions(type, offer.id(), before, main);
        yield new SyncWire.Accepted(offer.id(), accepted.rev());
      }
      case CommitOutcome.Rejected rejected ->
          new SyncWire.Rejected(offer.id(), rejected.currentRev(), rejected.currentSnapshot());
    };
  }

  /**
   * Hands each real transition of an accepted offer to the sink. Shielded: the commit is already
   * durable, so a sink failure must degrade to a logged warning — letting it propagate would return
   * {@link SyncWire.Failed} for a change main actually applied and make the node retry it forever.
   */
  private void emitTransitions(
      String type, String id, Map<String, Object> before, MainReplica main) {
    try {
      var after = main.current(id);
      for (var transition : SyncTransitions.detect(type, id, before, after)) {
        transitionSink.onTransition(transition);
      }
    } catch (RuntimeException e) {
      System.err.println("  [sync] transition notification failed; the sync is unaffected: " + e);
    }
  }

  /**
   * Whether this session may commit the run: both the incoming snapshot's {@code node} and the run
   * main already holds must be the principal's own handle. Checking both sides refuses a forged
   * foreign stamp and the clobbering of another node's run with a re-stamped one; a missing or
   * blank stamp fails closed. A null current snapshot alongside a non-null revision is a tombstone,
   * not a fresh ID — resurrecting it is refused outright, since the deleted run's owner is no
   * longer readable and every session is handed the tombstone's revision to replay against.
   */
  private boolean ownsRun(MainReplica.Offer offer, MainReplica main) {
    var incoming = offer.snapshot();
    var current = main.current(offer.id());
    if (incoming != null && current == null && main.currentRev(offer.id()) != null) {
      return false;
    }
    return ownedByPrincipal(incoming) && ownedByPrincipal(current);
  }

  private boolean ownedByPrincipal(Map<String, Object> run) {
    if (run == null) {
      return true;
    }
    var owner = Objects.toString(run.get("node"), "");
    return !owner.isBlank() && owner.equals(principal.handle());
  }
}
