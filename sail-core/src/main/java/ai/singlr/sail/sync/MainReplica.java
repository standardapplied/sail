/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The authoritative (main devbox) side of a sync round, as the {@link SyncEngine} sees it. Narrow
 * by design (interface segregation): main only ever reads its current state and commits a new
 * authoritative revision — it never adopts, tracks a base, or records conflicts (those are a
 * node-local concern). In process it is backed by a {@link StoreReplica}; across the wire by the
 * page views a {@link PagedSyncSession} hands the engine.
 */
public interface MainReplica {

  /** One compare-and-set offer: the state to commit against the rev the node last saw. */
  record Offer(String id, Map<String, Object> snapshot, String expectedRev) {}

  /** Stable identity of this main, used as the node's checkpoint key. */
  String id();

  /** Every entity id main knows of, including tombstoned ones. */
  Set<String> entityIds();

  /** Main's current comparable state for an entity; {@code null} if deleted or absent. */
  Map<String, Object> current(String id);

  /** Main's latest revision for an entity (including a tombstone); {@code null} if unknown. */
  String currentRev(String id);

  /** One entity's current state and its revision, read together. */
  record State(Map<String, Object> snapshot, String rev) {}

  /**
   * Samples {@link #current} and {@link #currentRev} as one atomic read. A writer landing between
   * two separate reads would pair an old snapshot with the newer rev; a node handed that pair
   * merges against stale content yet offers the fresh rev as its expectation, and the CAS lets it
   * overwrite the concurrent change. A store-backed main reads both inside one transaction; a
   * cached view answers from the one entry it holds.
   */
  State state(String id);

  /**
   * Compare-and-set push of an authoritative state ({@code null} = delete). Accepts and mints a new
   * rev only if {@code expectedRev} still matches main's current rev for the entity (a brand-new
   * entity expects {@code null}); otherwise rejects with main's present state so the engine can
   * reconcile against the concurrent change rather than clobber it.
   */
  CommitOutcome commit(String id, Map<String, Object> snapshot, String expectedRev);

  /**
   * Commits several offers, answering one outcome per offer in order, each with exactly the
   * semantics of {@link #commit}. The in-process authority commits one by one; a remote one puts
   * the whole batch on the wire at once.
   */
  default List<CommitOutcome> commitAll(List<Offer> offers) {
    return offers.stream()
        .map(offer -> commit(offer.id(), offer.snapshot(), offer.expectedRev()))
        .toList();
  }

  /** Main's highest change sequence — the node advances its checkpoint to this after a round. */
  long maxSeq();
}
