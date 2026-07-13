/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.store.SyncPeer;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Main's side of one sync session: a stateless request loop over the SSH channel's stdio. It routes
 * each {@link SyncWire.Fetch}/{@link SyncWire.Commit} to the authoritative {@link MainReplica} for
 * its entity type (specs, files), serves the node's roster pull, and returns at {@link
 * SyncWire.Bye} or end of stream. The {@link SyncPrincipal} carries the push half of Door-2
 * authorization: a {@code viewer} opens a session and pulls every type, but its commits are refused
 * so only {@code member}+ work propagates. The principal's handle additionally binds run commits to
 * execution provenance — a session may create, update, or delete only runs stamped with its own
 * node, so no member can forge run metadata another box would treat as its own execution.
 */
public final class SyncRpcServer {

  private static final String RUN_ENTITY = "run";

  private final Map<String, MainReplica> replicas;
  private final SyncPrincipal principal;
  private final FdeRoster fdeRoster;
  private final SyncTransitionSink transitionSink;

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
    this.replicas = Map.copyOf(replicas);
    this.principal = Objects.requireNonNull(principal, "principal");
    this.fdeRoster = Objects.requireNonNull(fdeRoster, "fdeRoster");
    this.transitionSink = Objects.requireNonNull(transitionSink, "transitionSink");
  }

  public void serve(Reader in, Writer out) throws IOException {
    for (var line = SyncWire.readFramed(in); line != null; line = SyncWire.readFramed(in)) {
      var request = SyncWire.decodeRequest(line);
      if (request instanceof SyncWire.Bye) {
        return;
      }
      reply(out, respondTo(request));
    }
  }

  /**
   * Computes one response, converting any store-side failure into a {@link SyncWire.Failed} the
   * client can read, rather than letting it propagate and drop the session with no reply — the
   * client must always be able to tell a refused commit from a broken connection. The clean {@link
   * SyncWire.Rejected} staleness path is unaffected; only thrown failures land here.
   */
  private SyncWire.Response respondTo(SyncWire.Request request) {
    try {
      return switch (request) {
        case SyncWire.Fetch fetch -> fetched(fetch.entityType());
        case SyncWire.FetchFdes ignored -> new SyncWire.Fdes(fdeRoster.entries());
        case SyncWire.Commit commit -> onCommit(commit);
        case SyncWire.Bye ignored -> throw new IllegalStateException("Bye ends the session loop");
      };
    } catch (RuntimeException e) {
      System.err.println("  [sync] request failed, returning Failed to client: " + e);
      return new SyncWire.Failed(
          "Main could not apply the request and made no change; retry the sync. Cause: "
              + rootMessage(e));
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

  private SyncWire.Response fetched(String entityType) {
    var main = replicas.get(entityType);
    if (main == null) {
      return new SyncWire.Failed("Unknown entity type: " + entityType);
    }
    var entities = new LinkedHashMap<String, SyncWire.Snapshot>();
    for (var id : main.entityIds()) {
      entities.put(id, new SyncWire.Snapshot(main.currentRev(id), main.current(id)));
    }
    return new SyncWire.Fetched(main.id(), main.maxSeq(), entities);
  }

  private SyncWire.Response onCommit(SyncWire.Commit commit) {
    if (!principal.canWrite()) {
      return new SyncWire.Failed(
          "Your role is read-only: it can pull the shared board but not push changes.");
    }
    var main = replicas.get(commit.entityType());
    if (main == null) {
      return new SyncWire.Failed("Unknown entity type: " + commit.entityType());
    }
    if (RUN_ENTITY.equals(commit.entityType()) && !ownsRunCommit(commit, main)) {
      return new SyncWire.Failed(
          "A sync session may push only runs executed on its own node; run "
              + commit.entityId()
              + " is not "
              + principal.handle()
              + "'s.");
    }
    var before = main.current(commit.entityId());
    var outcome =
        SyncPeer.with(
            principal.handle(),
            () -> main.commit(commit.entityId(), commit.snapshot(), commit.expectedRev()));
    return switch (outcome) {
      case CommitOutcome.Accepted accepted -> {
        emitTransitions(commit, before, main);
        yield new SyncWire.Committed(accepted.rev(), main.maxSeq());
      }
      case CommitOutcome.Rejected rejected ->
          new SyncWire.Rejected(rejected.currentRev(), rejected.currentSnapshot());
    };
  }

  /**
   * Hands each real transition of an accepted commit to the sink. Shielded: the commit is already
   * durable, so a sink failure must degrade to a logged warning — letting it propagate would return
   * {@link SyncWire.Failed} for a change main actually applied and make the node retry it forever.
   */
  private void emitTransitions(
      SyncWire.Commit commit, Map<String, Object> before, MainReplica main) {
    try {
      var after = main.current(commit.entityId());
      for (var transition :
          SyncTransitions.detect(commit.entityType(), commit.entityId(), before, after)) {
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
   * longer readable and fetch hands every session the tombstone's revision to replay against.
   */
  private boolean ownsRunCommit(SyncWire.Commit commit, MainReplica main) {
    var incoming = commit.snapshot();
    var current = main.current(commit.entityId());
    if (incoming != null && current == null && main.currentRev(commit.entityId()) != null) {
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
