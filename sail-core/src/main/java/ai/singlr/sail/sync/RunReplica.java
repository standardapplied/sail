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
import java.util.function.Supplier;

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
  private final String handle;
  private final RunStore runs;
  private final ChangeLog changeLog;
  private final SyncConflicts conflicts;
  private final SyncState syncState;

  /**
   * {@code id} is the box's sync-mesh identity (the checkpoint peer key); {@code handle} is the
   * box's FDE handle, the value a run's {@code node} carries when this box executed it. The two
   * differ in production — {@code id} is the machine hostname, {@code handle} the configured sync
   * handle — so run ownership is checked against {@code handle}, never {@code id}. A blank handle
   * (a box with no sync identity) defers ownership to main's guard.
   */
  public RunReplica(
      String id,
      String handle,
      RunStore runs,
      ChangeLog changeLog,
      SyncConflicts conflicts,
      SyncState syncState) {
    this.id = Objects.requireNonNull(id, "id");
    this.handle = Objects.requireNonNull(handle, "handle");
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

  /**
   * A box may push only the runs it executed. A run stamped with another node is a foreign copy
   * this box holds purely as a reader, so it is never pushed: the engine adopts main's version
   * instead. A blank or absent stamp (a run from before node-stamping, or a tombstone whose owner
   * is no longer readable), or a box with no handle of its own, is left to main's own ownership
   * guard rather than denied here.
   */
  @Override
  public boolean mayPush(String entityId) {
    if (handle.isBlank()) {
      return true;
    }
    return runs.findById(entityId)
        .map(run -> run.node() == null || run.node().isBlank() || handle.equals(run.node()))
        .orElse(true);
  }

  @Override
  public <T> T atomically(Supplier<T> work) {
    return changeLog.transaction(work);
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
