/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

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
 * SyncWire.Bye} or end of stream. The {@code writable} gate is the push half of Door-2
 * authorization: a {@code viewer} opens a session and pulls every type, but its commits are refused
 * so only {@code member}+ work propagates.
 */
public final class SyncRpcServer {

  private final Map<String, MainReplica> replicas;
  private final boolean writable;
  private final FdeRoster fdeRoster;

  public SyncRpcServer(MainReplica main, boolean writable) {
    this(Map.of("spec", main), writable, FdeRoster.EMPTY);
  }

  public SyncRpcServer(MainReplica main, boolean writable, FdeRoster fdeRoster) {
    this(Map.of("spec", main), writable, fdeRoster);
  }

  public SyncRpcServer(Map<String, MainReplica> replicas, boolean writable, FdeRoster fdeRoster) {
    this.replicas = Map.copyOf(replicas);
    this.writable = writable;
    this.fdeRoster = Objects.requireNonNull(fdeRoster, "fdeRoster");
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
    if (!writable) {
      return new SyncWire.Failed(
          "Your role is read-only: it can pull the shared board but not push changes.");
    }
    var main = replicas.get(commit.entityType());
    if (main == null) {
      return new SyncWire.Failed("Unknown entity type: " + commit.entityType());
    }
    return switch (main.commit(commit.entityId(), commit.snapshot(), commit.expectedRev())) {
      case CommitOutcome.Accepted accepted -> new SyncWire.Committed(accepted.rev(), main.maxSeq());
      case CommitOutcome.Rejected rejected ->
          new SyncWire.Rejected(rejected.currentRev(), rejected.currentSnapshot());
    };
  }
}
