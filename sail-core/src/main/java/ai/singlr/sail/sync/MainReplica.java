/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import java.util.Map;
import java.util.Set;

/**
 * The authoritative (main devbox) side of a sync round, as the {@link SyncEngine} sees it. Narrow
 * by design (interface segregation): main only ever reads its current state and commits a new
 * authoritative revision — it never adopts, tracks a base, or records conflicts (those are a
 * node-local concern). In brick 3b this is backed in-process by a {@code SpecReplica}; in brick 4 a
 * transport adapter over the SSH gateway implements the same contract.
 */
public interface MainReplica {

  /** Stable identity of this main, used as the node's checkpoint key. */
  String id();

  /** Every entity id main knows of, including tombstoned ones. */
  Set<String> entityIds();

  /** Main's current comparable state for an entity; {@code null} if deleted or absent. */
  Map<String, Object> current(String id);

  /** Main's latest revision for an entity (including a tombstone); {@code null} if unknown. */
  String currentRev(String id);

  /** Commits an authoritative state ({@code null} = delete), minting and returning the new rev. */
  String commit(String id, Map<String, Object> snapshot);

  /** Main's highest change sequence — the node advances its checkpoint to this after a round. */
  long maxSeq();
}
