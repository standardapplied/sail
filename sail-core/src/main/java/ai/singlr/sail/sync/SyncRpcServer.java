/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Main's side of one sync session: a stateless request loop over the SSH channel's stdio. It serves
 * a node's {@link SyncWire.Fetch} from the authoritative {@link MainReplica}, mints a rev for each
 * accepted {@link SyncWire.Commit}, and returns at {@link SyncWire.Bye} or end of stream. The
 * {@code writable} gate is the push half of Door-2 authorization: a {@code viewer} opens a session
 * and pulls, but its commits are refused so only {@code member}+ work propagates to the shared
 * board.
 */
public final class SyncRpcServer {

  private final MainReplica main;
  private final boolean writable;
  private final FdeRoster fdeRoster;

  public SyncRpcServer(MainReplica main, boolean writable) {
    this(main, writable, FdeRoster.EMPTY);
  }

  public SyncRpcServer(MainReplica main, boolean writable, FdeRoster fdeRoster) {
    this.main = Objects.requireNonNull(main, "main");
    this.writable = writable;
    this.fdeRoster = Objects.requireNonNull(fdeRoster, "fdeRoster");
  }

  public void serve(Reader in, Writer out) throws IOException {
    for (var line = SyncWire.readFramed(in); line != null; line = SyncWire.readFramed(in)) {
      switch (SyncWire.decodeRequest(line)) {
        case SyncWire.Bye ignored -> {
          return;
        }
        case SyncWire.Fetch ignored -> reply(out, fetched());
        case SyncWire.FetchFdes ignored -> reply(out, new SyncWire.Fdes(fdeRoster.entries()));
        case SyncWire.Commit commit -> reply(out, onCommit(commit));
      }
    }
  }

  private static void reply(Writer out, SyncWire.Response response) throws IOException {
    out.write(SyncWire.encode(response));
    out.write('\n');
    out.flush();
  }

  private SyncWire.Fetched fetched() {
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
    return switch (main.commit(commit.entityId(), commit.snapshot(), commit.expectedRev())) {
      case CommitOutcome.Accepted accepted -> new SyncWire.Committed(accepted.rev(), main.maxSeq());
      case CommitOutcome.Rejected rejected ->
          new SyncWire.Rejected(rejected.currentRev(), rejected.currentSnapshot());
    };
  }
}
