/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.PushOutcome;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import ai.singlr.sail.store.SyncedStore;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Adapts any {@link SyncedStore} to the sync roles. One box acts as the node ({@link LocalReplica})
 * when it syncs up to main and as the authority ({@link MainReplica}) when another node syncs to
 * it, so the in-process two-node harness wires two {@code StoreReplica}s together and a transport
 * adapter swaps the {@link MainReplica} side without the engine changing. Pure delegation to the
 * store (revisions), {@link SyncConflicts} (parked conflicts), and {@link SyncState} (checkpoint) —
 * the one adapter behind every entity type, replacing the six hand-written per-store copies.
 *
 * <p>{@code pushPolicy} decides whether this node may push its own change for an id up to main: the
 * default always may (multi-writer entities — specs, files, projects), and a single-writer entity
 * like a run supplies a policy so a reader box never pushes a run it did not author.
 */
public final class StoreReplica implements LocalReplica, MainReplica {

  static final String READ_ONLY =
      "your role is read-only: it can pull the shared board but not push changes";

  private final String id;
  private final SyncedStore store;
  private final ChangeLog changeLog;
  private final SyncConflicts conflicts;
  private final SyncState syncState;
  private final Predicate<String> pushPolicy;

  public StoreReplica(
      String id,
      SyncedStore store,
      ChangeLog changeLog,
      SyncConflicts conflicts,
      SyncState syncState) {
    this(id, store, changeLog, conflicts, syncState, entityId -> true);
  }

  public StoreReplica(
      String id,
      SyncedStore store,
      ChangeLog changeLog,
      SyncConflicts conflicts,
      SyncState syncState,
      Predicate<String> pushPolicy) {
    this.id = Objects.requireNonNull(id, "id");
    this.store = Objects.requireNonNull(store, "store");
    this.changeLog = Objects.requireNonNull(changeLog, "changeLog");
    this.conflicts = Objects.requireNonNull(conflicts, "conflicts");
    this.syncState = Objects.requireNonNull(syncState, "syncState");
    this.pushPolicy = Objects.requireNonNull(pushPolicy, "pushPolicy");
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public Set<String> entityIds() {
    return store.syncEntityIds();
  }

  @Override
  public Set<String> dirtyIds() {
    var dirty = new LinkedHashSet<>(store.dirtyIds());
    dirty.addAll(conflicts.pendingIds(store.entityType()));
    return dirty;
  }

  @Override
  public boolean mayPush(String entityId) {
    return pushPolicy.test(entityId);
  }

  @Override
  public Set<String> latestWinsFields() {
    return store.latestWinsFields();
  }

  @Override
  public <T> T atomically(Supplier<T> work) {
    return changeLog.transaction(work);
  }

  @Override
  public Map<String, Object> current(String entityId) {
    return store.currentForSync(entityId);
  }

  @Override
  public Map<String, Object> base(String entityId) {
    return store.comparableAtRev(entityId, store.baseRevOf(entityId));
  }

  /**
   * The latest revision of an entity, including a tombstone — and, for an erased one, its erasure's
   * rev, whatever the store keeps on its rows: a message store reads revisions off its rows, and an
   * erased message has none.
   */
  @Override
  public String currentRev(String entityId) {
    return erasure(entityId).map(ChangeLog.Entry::rev).orElseGet(() -> store.latestRev(entityId));
  }

  @Override
  public MainReplica.State state(String entityId) {
    return snapshot(
        () ->
            erasure(entityId)
                .map(erased -> new MainReplica.State(null, erased.rev(), ChangeLog.Kind.ERASURE))
                .orElseGet(() -> new MainReplica.State(current(entityId), currentRev(entityId))));
  }

  @Override
  public <T> T snapshot(Supplier<T> work) {
    return changeLog.read(work);
  }

  @Override
  public void adopt(String entityId, Map<String, Object> snapshot, String rev) {
    store.adoptForSync(entityId, snapshot, rev);
    conflicts.settle(store.entityType(), entityId, rev);
  }

  /**
   * Main's compare-and-set commit. An erased entity takes no commit at all — every offer is stale
   * against its erasure, so the node adopts it and nothing brings the entity back — and a state
   * that would belong to an erased entity is refused by the journal ({@link ChangeLog.Pruned}),
   * inside the commit's own transaction, so a prune cannot slip between the two. Who may write is
   * decided in the same transaction: a read-only actor's offer is {@linkplain CommitOutcome.Denied
   * denied} with main's version, as is any change the store itself refuses the actor.
   */
  @Override
  public CommitOutcome commit(String entityId, Map<String, Object> snapshot, String expectedRev) {
    return atomically(
        () -> {
          var erased = erasure(entityId);
          if (erased.isPresent()) {
            return new CommitOutcome.Rejected(erased.get().rev(), null);
          }
          if (!Actor.current().canWrite()) {
            return new CommitOutcome.Denied(
                READ_ONLY, store.latestRev(entityId), current(entityId));
          }
          return switch (store.commitRevision(entityId, snapshot, expectedRev)) {
            case PushOutcome.Accepted a -> new CommitOutcome.Accepted(a.rev());
            case PushOutcome.Stale s ->
                new CommitOutcome.Rejected(s.currentRev(), s.currentSnapshot());
            case PushOutcome.Denied d ->
                new CommitOutcome.Denied(d.reason(), d.currentRev(), d.currentSnapshot());
          };
        });
  }

  @Override
  public long maxSeq() {
    return changeLog.maxSeq(store.entityType());
  }

  @Override
  public void recordConflict(
      String entityId,
      Map<String, Object> base,
      Map<String, Object> local,
      Map<String, Object> remote,
      List<String> fields) {
    conflicts.record(store.entityType(), entityId, json(base), json(local), json(remote), fields);
  }

  @Override
  public long checkpoint(String peerId) {
    return syncState.checkpoint(peerId, store.entityType());
  }

  @Override
  public void advanceCheckpoint(String peerId, long seq) {
    syncState.advance(peerId, store.entityType(), seq);
  }

  private Optional<ChangeLog.Entry> erasure(String entityId) {
    return changeLog.erasure(store.entityType(), entityId);
  }

  private static String json(Map<String, Object> snapshot) {
    return snapshot == null ? null : YamlUtil.dumpJson(snapshot);
  }
}
