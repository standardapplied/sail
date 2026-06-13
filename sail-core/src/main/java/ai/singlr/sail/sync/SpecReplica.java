/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Adapts a single box's spec stores to the sync roles. The same box can act as the node ({@link
 * LocalReplica}) when it syncs up to main, and as the authority ({@link MainReplica}) when another
 * node syncs to it — so the in-process two-node harness wires two {@code SpecReplica}s together,
 * and brick 4 swaps the {@link MainReplica} side for a transport adapter without the engine
 * changing. Pure delegation to {@link SpecStore} (revisions), {@link SyncConflicts} (parked
 * conflicts), and {@link SyncState} (checkpoint) — no logic of its own beyond serialization.
 */
public final class SpecReplica implements LocalReplica, MainReplica {

  private static final String ENTITY = "spec";

  private final String id;
  private final SpecStore specs;
  private final ChangeLog changeLog;
  private final SyncConflicts conflicts;
  private final SyncState syncState;

  public SpecReplica(
      String id,
      SpecStore specs,
      ChangeLog changeLog,
      SyncConflicts conflicts,
      SyncState syncState) {
    this.id = Objects.requireNonNull(id, "id");
    this.specs = Objects.requireNonNull(specs, "specs");
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
    return specs.syncEntityIds();
  }

  @Override
  public Map<String, Object> current(String entityId) {
    return specs.comparableSnapshot(entityId);
  }

  @Override
  public Map<String, Object> base(String entityId) {
    return specs.comparableAtRev(entityId, specs.baseRevOf(entityId));
  }

  @Override
  public String currentRev(String entityId) {
    return specs.latestRev(entityId);
  }

  @Override
  public void adopt(String entityId, Map<String, Object> snapshot, String rev) {
    specs.applyRevision(entityId, snapshot, rev);
  }

  @Override
  public String commit(String entityId, Map<String, Object> snapshot) {
    return specs.commitRevision(entityId, snapshot);
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
    syncState.advance(peerId, seq);
  }

  private static String json(Map<String, Object> snapshot) {
    return snapshot == null ? null : YamlUtil.dumpJson(snapshot);
  }
}
