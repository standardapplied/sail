/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.util.Map;

/**
 * The result of a compare-and-set commit on a synced store ({@link SpecStore}, {@link FileStore}):
 * {@link Accepted} with the freshly minted rev, {@link Stale} carrying the store's present state
 * when the pusher's expected rev no longer matches — so a concurrent change is never overwritten —
 * or {@link Denied} when the pusher may not make the change at all.
 */
public sealed interface PushOutcome {

  /** Accepted; {@code rev} is the newly minted authoritative revision. */
  record Accepted(String rev) implements PushOutcome {}

  /** Rejected; {@code current*} is the store's present state, untouched. */
  record Stale(String currentRev, Map<String, Object> currentSnapshot) implements PushOutcome {}

  /** Refused on authority, naming why; {@code current*} is the store's present state, untouched. */
  record Denied(String reason, String currentRev, Map<String, Object> currentSnapshot)
      implements PushOutcome {}
}
