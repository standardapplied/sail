/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.PushOutcome;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Adapts a box's {@link ProjectStore} to the sync roles, exactly as {@link SpecReplica} does for
 * specs: the same box is the node ({@link LocalReplica}) when it syncs up to main, and the
 * authority ({@link MainReplica}) when another node syncs to it. Pure delegation to {@link
 * ProjectStore} (revisions/CAS), {@link SyncConflicts} (parked conflicts), and {@link SyncState}
 * (checkpoint) — no logic of its own beyond serialization. Project definitions reconcile through
 * the same engine, so a project created on main replicates to every box.
 */
public final class ProjectReplica implements LocalReplica, MainReplica {

  private static final String ENTITY = "project";

  private final String id;
  private final ProjectStore projects;
  private final ChangeLog changeLog;
  private final SyncConflicts conflicts;
  private final SyncState syncState;

  public ProjectReplica(
      String id,
      ProjectStore projects,
      ChangeLog changeLog,
      SyncConflicts conflicts,
      SyncState syncState) {
    this.id = Objects.requireNonNull(id, "id");
    this.projects = Objects.requireNonNull(projects, "projects");
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
    return projects.syncEntityIds();
  }

  @Override
  public Map<String, Object> current(String entityId) {
    return projects.comparableSnapshot(entityId);
  }

  @Override
  public Map<String, Object> base(String entityId) {
    return projects.comparableAtRev(entityId, projects.baseRevOf(entityId));
  }

  @Override
  public String currentRev(String entityId) {
    return projects.latestRev(entityId);
  }

  @Override
  public void adopt(String entityId, Map<String, Object> snapshot, String rev) {
    projects.applyRevision(entityId, snapshot, rev);
  }

  @Override
  public CommitOutcome commit(String entityId, Map<String, Object> snapshot, String expectedRev) {
    return switch (projects.commitRevision(entityId, snapshot, expectedRev)) {
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
    syncState.advance(peerId, seq);
  }

  private static String json(Map<String, Object> snapshot) {
    return snapshot == null ? null : YamlUtil.dumpJson(snapshot);
  }
}
