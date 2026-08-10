/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.PushOutcome;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Adapts a box's {@link ReviewStore} to the sync roles, exactly as {@link RunReplica} does for
 * runs: the same box is the node ({@link LocalReplica}) when it syncs up to main, and the authority
 * ({@link MainReplica}) when another node syncs to it. The review reconciles as an aggregate — the
 * review row plus its stages and each stage's finding counts — over the same channel as a different
 * entity type ({@code review}), so main sees the whole review loop (started, stage passed/failed
 * with counts, iteration, escalation) and can narrate it. Full finding rows stay on the executing
 * node; only the aggregate's shape and counts sync.
 *
 * <p><strong>Single-writer.</strong> Only the executing node mutates its own reviews, so this
 * replica's role on every other box is purely a reader: it pulls foreign reviews and never pushes a
 * change it did not author. Reconciliation is therefore conflict-free in practice; the {@link
 * SyncConflicts} wiring exists only to honour the shared engine contract.
 */
public final class ReviewReplica implements LocalReplica, MainReplica {

  private static final String ENTITY = "review";

  private final String id;
  private final ReviewStore reviews;
  private final ChangeLog changeLog;
  private final SyncConflicts conflicts;
  private final SyncState syncState;

  public ReviewReplica(
      String id,
      ReviewStore reviews,
      ChangeLog changeLog,
      SyncConflicts conflicts,
      SyncState syncState) {
    this.id = Objects.requireNonNull(id, "id");
    this.reviews = Objects.requireNonNull(reviews, "reviews");
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
    return reviews.syncEntityIds();
  }

  @Override
  public <T> T atomically(Supplier<T> work) {
    return changeLog.transaction(work);
  }

  @Override
  public Map<String, Object> current(String entityId) {
    return reviews.comparableSnapshot(entityId);
  }

  @Override
  public Map<String, Object> base(String entityId) {
    return reviews.comparableAtRev(entityId, reviews.baseRevOf(entityId));
  }

  @Override
  public String currentRev(String entityId) {
    return reviews.latestRev(entityId);
  }

  @Override
  public void adopt(String entityId, Map<String, Object> snapshot, String rev) {
    reviews.applyRevision(entityId, snapshot, rev);
  }

  @Override
  public CommitOutcome commit(String entityId, Map<String, Object> snapshot, String expectedRev) {
    return switch (reviews.commitRevision(entityId, snapshot, expectedRev)) {
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
