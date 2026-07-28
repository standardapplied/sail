/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.PushOutcome;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Sync adapter for immutable, append-only spec messages. */
public final class MessageReplica implements LocalReplica, MainReplica {

  private static final String ENTITY = "message";

  private final String id;
  private final MessageStore messages;
  private final ChangeLog changeLog;
  private final SyncConflicts conflicts;
  private final SyncState syncState;

  public MessageReplica(
      String id,
      MessageStore messages,
      ChangeLog changeLog,
      SyncConflicts conflicts,
      SyncState syncState) {
    this.id = Objects.requireNonNull(id, "id");
    this.messages = Objects.requireNonNull(messages, "messages");
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
    return messages.syncEntityIds();
  }

  @Override
  public Map<String, Object> current(String entityId) {
    return messages.comparableSnapshot(entityId);
  }

  @Override
  public Map<String, Object> base(String entityId) {
    return messages.comparableAtRev(entityId, messages.baseRevOf(entityId));
  }

  @Override
  public String currentRev(String entityId) {
    return messages.latestRev(entityId);
  }

  @Override
  public void adopt(String entityId, Map<String, Object> snapshot, String rev) {
    messages.applyRevision(entityId, snapshot, rev);
  }

  @Override
  public CommitOutcome commit(String entityId, Map<String, Object> snapshot, String expectedRev) {
    return switch (messages.commitRevision(entityId, snapshot, expectedRev)) {
      case PushOutcome.Accepted accepted -> new CommitOutcome.Accepted(accepted.rev());
      case PushOutcome.Stale stale ->
          new CommitOutcome.Rejected(stale.currentRev(), stale.currentSnapshot());
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
