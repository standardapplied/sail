/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.PushOutcome;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Adapts one box's {@link FileStore} to the sync roles, so the {@link SyncEngine} reconciles shared
 * project files bidirectionally with main exactly as it does specs — a different entity type
 * ({@code file}) over the same channel. Pure delegation to {@link FileStore} (revisions), {@link
 * SyncConflicts} (parked conflicts), and {@link SyncState} (checkpoint).
 */
public final class FileReplica implements LocalReplica, MainReplica {

  private static final String ENTITY = "file";

  private final String id;
  private final FileStore files;
  private final ChangeLog changeLog;
  private final SyncConflicts conflicts;
  private final SyncState syncState;

  public FileReplica(
      String id,
      FileStore files,
      ChangeLog changeLog,
      SyncConflicts conflicts,
      SyncState syncState) {
    this.id = Objects.requireNonNull(id, "id");
    this.files = Objects.requireNonNull(files, "files");
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
    return files.syncEntityIds();
  }

  @Override
  public Map<String, Object> current(String entityId) {
    return files.comparableSnapshot(entityId);
  }

  @Override
  public Map<String, Object> base(String entityId) {
    return files.comparableAtRev(entityId, files.baseRevOf(entityId));
  }

  @Override
  public String currentRev(String entityId) {
    return files.latestRev(entityId);
  }

  @Override
  public void adopt(String entityId, Map<String, Object> snapshot, String rev) {
    files.applyRevision(entityId, snapshot, rev);
  }

  @Override
  public CommitOutcome commit(String entityId, Map<String, Object> snapshot, String expectedRev) {
    return switch (files.commitRevision(entityId, snapshot, expectedRev)) {
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
