/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import java.util.Map;

/**
 * The result of a node pushing one entity to {@link MainReplica}. Main accepts the push only when
 * the node's view of the row is still current — its {@code expectedRev} equals main's live rev —
 * and mints a new {@link Accepted} rev. If another node changed the row since this node fetched,
 * the push is {@link Rejected} with main's current rev and snapshot, never overwriting it. This is
 * the compare-and-set that makes concurrent syncs safe: the {@link SyncEngine} re-reconciles the
 * entity against the returned state, auto-merging a disjoint concurrent edit and surfacing an
 * overlapping one as a conflict — so no write is ever silently lost. A push the principal may not
 * make at all is {@link Denied}: main's version stands, and the node adopts it rather than offering
 * the same change again every round.
 */
public sealed interface CommitOutcome
    permits CommitOutcome.Accepted, CommitOutcome.Rejected, CommitOutcome.Denied {

  /**
   * Main accepted the push and minted {@code rev}, recording {@code author} as who made it and
   * {@code creator} as the creator it holds, null when the entity carries none.
   */
  record Accepted(String rev, String author, String creator) implements CommitOutcome {
    public Accepted(String rev) {
      this(rev, null, null);
    }

    public Accepted(String rev, String author) {
      this(rev, author, null);
    }
  }

  /** Main moved since the node fetched; {@code current*} is its present state, left untouched. */
  record Rejected(String currentRev, Map<String, Object> currentSnapshot)
      implements CommitOutcome {}

  /**
   * Main decided this principal may not make this change, naming why. {@code rev} and {@code
   * snapshot} are main's version: a revision, a tombstone (a rev with no snapshot), or nothing when
   * main holds none; {@code author} is who main recorded as making it.
   */
  record Denied(String reason, String rev, Map<String, Object> snapshot, String author)
      implements CommitOutcome {
    public Denied(String reason, String rev, Map<String, Object> snapshot) {
      this(reason, rev, snapshot, null);
    }
  }
}
