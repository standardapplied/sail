/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.store.ChangeLog;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The authoritative (main devbox) side of a sync round, as the {@link SyncEngine} sees it. Narrow
 * by design (interface segregation): main only ever reads its current state and commits a new
 * authoritative revision — it never adopts, tracks a base, or records conflicts (those are a
 * node-local concern). In process it is backed by a {@link StoreReplica}; across the wire by the
 * page views a {@link PagedSyncSession} hands the engine.
 */
public interface MainReplica {

  /**
   * One compare-and-set offer: the state to commit against the rev the node last saw. An {@code
   * erase} offer carries no snapshot and asks main to erase the entity and what belongs to it; it
   * is decided on authority, not on the rev.
   */
  record Offer(String id, Map<String, Object> snapshot, String expectedRev, boolean erase) {
    public Offer {
      if (erase && snapshot != null) {
        throw new IllegalArgumentException("An erase offer for " + id + " carries no snapshot");
      }
    }

    public Offer(String id, Map<String, Object> snapshot, String expectedRev) {
      this(id, snapshot, expectedRev, false);
    }

    /** The offer asking main to erase {@code id}. */
    public static Offer erasure(String id) {
      return new Offer(id, null, null, true);
    }
  }

  /** Stable identity of this main, used as the node's checkpoint key. */
  String id();

  /** Every entity id main knows of, including tombstoned ones. */
  Set<String> entityIds();

  /** Main's current comparable state for an entity; {@code null} if deleted or absent. */
  Map<String, Object> current(String id);

  /** Main's latest revision for an entity (including a tombstone); {@code null} if unknown. */
  String currentRev(String id);

  /**
   * One entity's current state and its revision, read together, and the kind of the entry they come
   * from: an erased entity has no snapshot and its erasure's rev.
   */
  record State(Map<String, Object> snapshot, String rev, ChangeLog.Kind kind) {
    public State(Map<String, Object> snapshot, String rev) {
      this(snapshot, rev, snapshot == null ? ChangeLog.Kind.TOMBSTONE : ChangeLog.Kind.REVISION);
    }
  }

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
   * reconcile against the concurrent change rather than clobber it. A change the acting principal
   * may not make is {@linkplain CommitOutcome.Denied denied} with main's version, decided in the
   * same transaction.
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

  /**
   * What {@code offer} costs to hold before it is committed, in whatever unit {@link #offerBudget}
   * is measured in. An in-process authority holds nothing, so an offer weighs nothing; a remote one
   * weighs an offer as the chars it will take on the wire.
   */
  default long weigh(Offer offer) {
    return 0;
  }

  /**
   * How much the engine may hold in pending offers before it must commit them: the bound on memory
   * a first upload of a large table can take, so an offer is never retained longer than one batch.
   * Unbounded for an in-process authority, the frame for a remote one.
   */
  default long offerBudget() {
    return Long.MAX_VALUE;
  }

  /** Main's highest change sequence — the node advances its checkpoint to this after a round. */
  long maxSeq();

  /**
   * Runs {@code work} against one consistent state of main, so a page reads main as it was at one
   * instant whatever lands meanwhile. A store-backed main takes a read snapshot that never makes a
   * writer wait; a cached view already is one.
   */
  default <T> T snapshot(Supplier<T> work) {
    return work.get();
  }
}
