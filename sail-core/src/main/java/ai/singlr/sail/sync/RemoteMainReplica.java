/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import java.io.Reader;
import java.io.Writer;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The {@link MainReplica} the {@link SyncEngine} drives when main is remote, bound to one entity
 * type ({@code spec}, {@code file}): it speaks {@link SyncWire} over the SSH channel instead of
 * touching a database, so the engine reconciles against a box across the network with no change to
 * its logic, and several typed replicas (created by a {@link SyncSession}) can ride one channel.
 *
 * <p>One {@link SyncWire.Fetch} pulls main's whole state for this type up front; every read the
 * engine makes ({@link #entityIds}, {@link #current}, {@link #currentRev}) is then served from that
 * snapshot with no further round trip. Only {@link #commit} talks to main again, and each accepted
 * commit carries back main's new high-water so {@link #maxSeq} — read once at the end of the round
 * to set the checkpoint — reflects this node's own pushes, exactly as the in-process engine sees
 * it.
 */
public final class RemoteMainReplica implements MainReplica {

  private final Reader in;
  private final Writer out;
  private final String entityType;

  private SyncWire.Fetched fetched;
  private long maxSeq;

  public RemoteMainReplica(Reader in, Writer out, String entityType) {
    this.in = Objects.requireNonNull(in, "in");
    this.out = Objects.requireNonNull(out, "out");
    this.entityType = Objects.requireNonNull(entityType, "entityType");
  }

  @Override
  public String id() {
    return fetched().mainId();
  }

  @Override
  public Set<String> entityIds() {
    return fetched().entities().keySet();
  }

  @Override
  public Map<String, Object> current(String entityId) {
    var snapshot = fetched().entities().get(entityId);
    return snapshot == null ? null : snapshot.snapshot();
  }

  @Override
  public String currentRev(String entityId) {
    var snapshot = fetched().entities().get(entityId);
    return snapshot == null ? null : snapshot.rev();
  }

  @Override
  public long maxSeq() {
    fetched();
    return maxSeq;
  }

  @Override
  public CommitOutcome commit(String entityId, Map<String, Object> snapshot, String expectedRev) {
    var response =
        Rpc.exchange(in, out, new SyncWire.Commit(entityType, entityId, snapshot, expectedRev));
    return switch (response) {
      case SyncWire.Committed committed -> {
        maxSeq = committed.maxSeq();
        yield new CommitOutcome.Accepted(committed.rev());
      }
      case SyncWire.Rejected rejected ->
          new CommitOutcome.Rejected(rejected.currentRev(), rejected.currentSnapshot());
      case SyncWire.Failed failed -> throw new SyncTransportException(failed.message());
      default -> throw new SyncTransportException("Unexpected response to commit: " + response);
    };
  }

  private SyncWire.Fetched fetched() {
    if (fetched == null) {
      var response = Rpc.exchange(in, out, new SyncWire.Fetch(entityType));
      if (!(response instanceof SyncWire.Fetched f)) {
        throw new SyncTransportException("Expected a fetch response, got: " + response);
      }
      fetched = f;
      maxSeq = f.maxSeq();
    }
    return fetched;
  }
}
