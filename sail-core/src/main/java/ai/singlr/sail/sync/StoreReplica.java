/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Decidability;
import ai.singlr.sail.store.MainVersion;
import ai.singlr.sail.store.PushOutcome;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import ai.singlr.sail.store.SyncedStore;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Adapts any {@link SyncedStore} to the sync roles. One box acts as the node ({@link LocalReplica})
 * when it syncs up to main and as the authority ({@link MainReplica}) when another node syncs to
 * it, so the in-process two-node harness wires two {@code StoreReplica}s together and a transport
 * adapter swaps the {@link MainReplica} side without the engine changing. Pure delegation to the
 * store (revisions), {@link SyncConflicts} (parked conflicts), and {@link SyncState} (checkpoint) —
 * the one adapter behind every entity type, replacing the six hand-written per-store copies.
 *
 * <p>{@code handle} is this box's FDE handle: the store answers for it whether this node may push
 * its own change for an id up to main ({@link SyncedStore#mayPush}) — a multi-writer entity always
 * may, a run only when this box executed it — and whether an id is work still live on this box
 * ({@link SyncedStore#live}). Main's commit asks the store's {@link SyncedStore#authority} for
 * every revision a node pushes.
 */
public final class StoreReplica implements LocalReplica, MainReplica {

  private final String id;
  private final SyncedStore store;
  private final ChangeLog changeLog;
  private final SyncConflicts conflicts;
  private final SyncState syncState;
  private final String handle;
  private final Decidability decidability;

  public StoreReplica(
      String id,
      SyncedStore store,
      ChangeLog changeLog,
      SyncConflicts conflicts,
      SyncState syncState) {
    this(id, store, changeLog, conflicts, syncState, null);
  }

  public StoreReplica(
      String id,
      SyncedStore store,
      ChangeLog changeLog,
      SyncConflicts conflicts,
      SyncState syncState,
      String handle) {
    this.id = Objects.requireNonNull(id, "id");
    this.store = Objects.requireNonNull(store, "store");
    this.changeLog = Objects.requireNonNull(changeLog, "changeLog");
    this.conflicts = Objects.requireNonNull(conflicts, "conflicts");
    this.syncState = Objects.requireNonNull(syncState, "syncState");
    this.handle = handle;
    this.decidability = new Decidability(changeLog.db());
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
    dirty.removeAll(decidability.pending(store.entityType(), dirty, handle));
    return dirty;
  }

  @Override
  public boolean mayPush(String entityId) {
    return store.mayPush(entityId, handle);
  }

  @Override
  public boolean live(String entityId) {
    return store.live(entityId, handle);
  }

  @Override
  public String lastHeardRev(String entityId) {
    return changeLog.latestHeard(store.entityType(), entityId).orElse(null);
  }

  @Override
  public void offering(String entityId, Map<String, Object> offered, Map<String, Object> from) {
    changeLog.recordOffer(store.entityType(), entityId, offered, from);
  }

  @Override
  public void settled(String entityId) {
    changeLog.settleOffer(store.entityType(), entityId);
  }

  /**
   * A base that moved settles a conflict parked on the entity, as adopting main's version does: the
   * round re-parks it if what the row holds now still clashes with main.
   */
  @Override
  public boolean acknowledge(String entityId, Map<String, Object> accepted, String rev) {
    var moved = store.acknowledge(entityId, accepted, rev);
    if (moved) {
      conflicts.settle(store.entityType(), entityId, rev);
    }
    return moved;
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
                .map(
                    erased ->
                        new MainReplica.State(
                            null, erased.rev(), ChangeLog.Kind.ERASURE, erased.actor()))
                .orElseGet(
                    () ->
                        new MainReplica.State(
                            current(entityId), currentRev(entityId), author(entityId))));
  }

  @Override
  public Optional<MainReplica.State> acceptedFrom(String entityId, String peer, String baseRev) {
    if (Strings.isBlank(peer)) {
      return Optional.empty();
    }
    return snapshot(
        () ->
            changeLog
                .latestFrom(store.entityType(), entityId, peer)
                .filter(entry -> tookAfter(entityId, entry, baseRev))
                .map(
                    entry ->
                        new MainReplica.State(
                            entry.deleted() ? null : store.comparableAtRev(entityId, entry.rev()),
                            entry.rev(),
                            entry.kind(),
                            entry.actor())));
  }

  private boolean tookAfter(String entityId, ChangeLog.Entry taken, String baseRev) {
    if (Strings.isBlank(baseRev)) {
      return true;
    }
    return changeLog
        .latestAt(store.entityType(), entityId, baseRev)
        .map(base -> taken.seq() > base.seq())
        .orElseGet(() -> changeLog.amongNewest(store.entityType(), entityId, taken.seq()));
  }

  private Snapshots.Creator recordedCreator(String entityId) {
    var committed = current(entityId);
    return committed == null || !committed.containsKey(Snapshots.CREATOR)
        ? null
        : new Snapshots.Creator(Snapshots.text(committed, Snapshots.CREATOR));
  }

  @Override
  public String author(String entityId) {
    return changeLog.head(store.entityType(), entityId).map(ChangeLog.Entry::actor).orElse(null);
  }

  @Override
  public <T> T snapshot(Supplier<T> work) {
    return changeLog.read(work);
  }

  /**
   * Adopting main's version settles both a conflict parked on the entity and an offer of it whose
   * answer was never heard: main's version is the answer.
   */
  @Override
  public void adopt(String entityId, Map<String, Object> snapshot, String rev) {
    store.adoptForSync(entityId, snapshot, rev);
    conflicts.settle(store.entityType(), entityId, rev);
    changeLog.settleOffer(store.entityType(), entityId);
  }

  /**
   * Main's compare-and-set commit. An erased entity takes no commit at all — every offer is stale
   * against its erasure, so the node adopts it and nothing brings the entity back — and a state
   * that would belong to an erased entity is refused by the journal ({@link ChangeLog.Pruned}),
   * inside the commit's own transaction, so a prune cannot slip between the two. Who may write is
   * decided in the same transaction, by the type's {@link WriteAuthority} the store asks: an offer
   * it refuses is {@linkplain CommitOutcome.Denied denied} with main's version.
   */
  @Override
  public CommitOutcome commit(String entityId, Map<String, Object> snapshot, String expectedRev) {
    return atomically(
        () -> {
          var erased = erasure(entityId);
          if (erased.isPresent()) {
            return new CommitOutcome.Rejected(erased.get().rev(), null);
          }
          var decidable =
              decidability.forMain(
                  store.entityType(), entityId, snapshot, Actor.current().handle());
          if (decidable.status() == Decidability.Status.GONE) {
            return new CommitOutcome.Denied(decidable.reason(), null, null, author(entityId), true);
          }
          if (decidable.status() == Decidability.Status.PENDING) {
            return new CommitOutcome.Refused(decidable.reason());
          }
          return switch (store.commitRevision(entityId, snapshot, expectedRev, store.authority())) {
            case PushOutcome.Accepted a ->
                new CommitOutcome.Accepted(a.rev(), author(entityId), recordedCreator(entityId));
            case PushOutcome.Stale s ->
                new CommitOutcome.Rejected(s.currentRev(), s.currentSnapshot());
            case PushOutcome.Denied d ->
                new CommitOutcome.Denied(
                    d.reason(), d.currentRev(), d.currentSnapshot(), author(entityId));
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
      MainVersion remote,
      List<String> fields) {
    conflicts.record(
        store.entityType(),
        entityId,
        json(base),
        json(local),
        json(remote.snapshot()),
        remote.rev(),
        remote.author(),
        fields);
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
