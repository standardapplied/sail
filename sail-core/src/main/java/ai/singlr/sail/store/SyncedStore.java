/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.authority.WriteAuthority;
import java.util.Map;
import java.util.Set;

/**
 * The store surface a sync replica drives — everything the {@code StoreReplica} needs to act as
 * both the node ({@code LocalReplica}) and the authority ({@code MainReplica}) for one entity type,
 * without knowing the concrete store. Implemented by every synced store (specs, runs, reviews,
 * projects, files, messages), which collapses the six near-identical hand-written replicas into one
 * generic adapter.
 *
 * <p>A store that rides the {@link RevisionJournal} answers {@link #currentForSync}, {@link
 * #latestWinsFields} and {@link #acknowledge} through it, so each rule is declared once, in its
 * {@link EntitySchema}. The {@code default} hooks cover the stores that diverge: {@link
 * #adoptForSync} lets a store settle what goes with a row main holds none of (only {@code RunStore}
 * does), and {@link #mayPush} and {@link #live} let a single-writer store answer for the box whose
 * handle is asking (runs and reviews do).
 */
public interface SyncedStore {

  /**
   * A pushed change main cannot decide yet, because it names something main does not hold, such as
   * the run that posted a message. Refused rather than denied, so the node keeps the change and
   * offers it again once what it names has arrived. Thrown inside the commit's transaction, so
   * nothing of the change lands.
   */
  final class Unheld extends IllegalStateException {
    public Unheld(String message) {
      super(message);
    }
  }

  /** The {@code change_log.entity_type} discriminator, e.g. {@code "spec"}. */
  String entityType();

  /**
   * Instant-valued fields that only ever move forward and so can never be a conflict: when both
   * sides moved one, the later instant wins ({@link EntitySchema#latestWinsFields}).
   */
  Set<String> latestWinsFields();

  /** The content hashes this store's live rows reference; empty unless it has content fields. */
  default Set<String> liveContentHashes() {
    return Set.of();
  }

  /** Snapshot fields whose values name verified blobs. */
  default Set<String> contentFields() {
    return Set.of();
  }

  /** Every entity id this replica knows of, including tombstoned ones. */
  Set<String> syncEntityIds();

  /** Current comparable state; {@code null} if deleted or absent. */
  Map<String, Object> comparableSnapshot(String id);

  /** Comparable state recorded at a revision (the merge base); {@code null} if not recorded. */
  Map<String, Object> comparableAtRev(String id, String rev);

  /** The revision this row last synced from main; {@code null} if never. */
  String baseRevOf(String id);

  /** Latest revision, including a tombstone; {@code null} if unknown. */
  String latestRev(String id);

  /**
   * Every id carrying a change this box made that main has not yet acknowledged: a live row whose
   * revision is not the one it last synced from main, and an entity whose latest journal entry is a
   * deletion this box decided itself. What a sync round must reconcile even when main's change log
   * has nothing new for it.
   */
  Set<String> dirtyIds();

  /** Adopts an authoritative state at main's exact rev ({@code null} snapshot = delete). */
  void applyRevision(String id, Map<String, Object> snapshot, String rev);

  /**
   * Removes the live row of {@code id} and every child row the database cascades from it, writing
   * no journal entry: the only caller is {@link Erasure}, which records the erasure itself. A no-op
   * for an id with no live row.
   */
  void eraseRow(String id);

  /** Who may write this type on this box: the one rule its doors and main's commit decide by. */
  WriteAuthority authority();

  /**
   * Compare-and-set commit of an authoritative state ({@code null} = delete). After the
   * compare-and-set and the store's integrity checks, and before its first write, {@code authority}
   * decides the revision for the bound actor; a refusal is {@link PushOutcome.Denied} with the
   * store's present state.
   */
  PushOutcome commitRevision(
      String id, Map<String, Object> snapshot, String expectedRev, WriteAuthority authority);

  /**
   * The state the replica reports as "current" to the sync engine: {@link #comparableSnapshot}, or
   * for a deleted entity whose tombstone carries marks, those marks under its deleter ({@link
   * RevisionJournal#currentForSync}).
   */
  Map<String, Object> currentForSync(String id);

  /**
   * Adopts an authoritative state as the sync engine settles it — {@link #applyRevision} for every
   * store except one that removes, with a row main holds none of, what could only land with it.
   */
  default void adoptForSync(String id, Map<String, Object> snapshot, String rev) {
    applyRevision(id, snapshot, rev);
  }

  /**
   * Whether the box whose FDE handle is {@code handle} may push its own change to {@code id} up to
   * main, rather than only pull main's version. Every box may by default.
   */
  default boolean mayPush(String id, String handle) {
    return true;
  }

  /**
   * Whether {@code id} is work still under way on the box whose FDE handle is {@code handle}, which
   * main's version, by denial or by pull, never rewrites or removes: the local row stays as it is,
   * its next change is offered again, and once the work has finished main's version settles it like
   * any other's. Another box's work is never live here. None by default.
   */
  default boolean live(String id, String handle) {
    return false;
  }

  /**
   * Adopts {@code accepted}, main's version of {@code id} at {@code rev} that it took from this
   * box, as the row's merge base when it is newer than the base held here — main took the box's
   * offer and its answer never came back — keeping what the box changed since the {@link
   * #currentForSync} its offer was made from ({@link ChangeLog#recordOffer}) as a change main has
   * not taken. Returns whether the base moved. A store whose rows are never edited after they are
   * made recovers by converging instead, and moves nothing.
   */
  default boolean acknowledge(String id, Map<String, Object> accepted, String rev) {
    return false;
  }
}
