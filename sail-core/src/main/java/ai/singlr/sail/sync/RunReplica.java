/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.PushOutcome;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Adapts a box's {@link RunStore} to the sync roles, exactly as {@link SpecReplica} does for specs:
 * the same box is the node ({@link LocalReplica}) when it syncs up to main, and the authority
 * ({@link MainReplica}) when another node syncs to it. Run metadata reconciles through the same
 * engine — a different entity type ({@code run}) over the same channel — so "which node is running
 * spec X" reaches main and on to every box. Log content stays on the executing box; only the
 * metadata syncs.
 *
 * <p><strong>Single-writer.</strong> Only the executing node ever mutates its own runs, so this
 * replica's role on every other box is purely a reader: it pulls foreign runs and never pushes a
 * change it did not author. Reconciliation is therefore conflict-free in practice; the {@link
 * SyncConflicts} wiring exists only to honour the shared engine contract.
 */
public final class RunReplica implements LocalReplica, MainReplica {

  private static final String ENTITY = "run";

  private final String id;
  private final RunStore runs;
  private final ChangeLog changeLog;
  private final SyncConflicts conflicts;
  private final SyncState syncState;

  public RunReplica(
      String id, RunStore runs, ChangeLog changeLog, SyncConflicts conflicts, SyncState syncState) {
    this.id = Objects.requireNonNull(id, "id");
    this.runs = Objects.requireNonNull(runs, "runs");
    this.changeLog = Objects.requireNonNull(changeLog, "changeLog");
    this.conflicts = Objects.requireNonNull(conflicts, "conflicts");
    this.syncState = Objects.requireNonNull(syncState, "syncState");
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public Set<String> entityIds() {
    return runs.syncEntityIds();
  }

  @Override
  public Map<String, Object> current(String entityId) {
    return runs.comparableSnapshot(entityId);
  }

  @Override
  public Map<String, Object> base(String entityId) {
    return runs.comparableAtRev(entityId, runs.baseRevOf(entityId));
  }

  @Override
  public String currentRev(String entityId) {
    return runs.latestRev(entityId);
  }

  @Override
  public void adopt(String entityId, Map<String, Object> snapshot, String rev) {
    runs.applyRevision(entityId, snapshot, rev);
  }

  @Override
  public CommitOutcome commit(String entityId, Map<String, Object> snapshot, String expectedRev) {
    return switch (runs.commitRevision(entityId, snapshot, expectedRev)) {
      case PushOutcome.Accepted a -> new CommitOutcome.Accepted(a.rev());
      case PushOutcome.Stale s -> new CommitOutcome.Rejected(s.currentRev(), s.currentSnapshot());
    };
  }

  @Override
  public long maxSeq() {
    return changeLog.maxSeq(ENTITY);
  }

  @Override
  public void recordConflict(
      String entityId,
      Map<String, Object> base,
      Map<String, Object> local,
      Map<String, Object> remote,
      List<String> fields) {
    conflicts.record(ENTITY, entityId, json(base), json(local), json(remote), fields);
  }

  @Override
  public void advanceCheckpoint(String peerId, long seq) {
    syncState.advance(peerId + ":" + ENTITY, seq);
  }

  private static String json(Map<String, Object> snapshot) {
    return snapshot == null ? null : YamlUtil.dumpJson(snapshot);
  }
}
