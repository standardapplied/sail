/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.store.Snapshots;
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
    permits CommitOutcome.Accepted,
        CommitOutcome.Rejected,
        CommitOutcome.Refused,
        CommitOutcome.Denied {

  /**
   * Main accepted the push and minted {@code rev}, recording {@code author} as who made it and
   * holding {@code creator}, null when the entity carries no creator or main did not say.
   */
  record Accepted(String rev, String author, Snapshots.Creator creator) implements CommitOutcome {}

  /** Main moved since the node fetched; {@code current*} is its present state, left untouched. */
  record Rejected(String currentRev, Map<String, Object> currentSnapshot)
      implements CommitOutcome {}

  /**
   * Main could not take the offer because a dependency it awaits has not arrived, naming why. Not a
   * decision about who may write: the node leaves its row untouched and offers it again once the
   * dependency lands, and this one offer never fails the type.
   */
  record Refused(String reason) implements CommitOutcome {}

  /**
   * Main decided this principal may not make this change, naming why. {@code rev} and {@code
   * snapshot} are main's version: a revision, a tombstone (a rev with no snapshot), or nothing when
   * main holds none; {@code author} is who main recorded as making it. {@code gone} marks a denial
   * main made because a dependency it has erased can never arrive: the node settles it rather than
   * offering it again.
   */
  record Denied(
      String reason, String rev, Map<String, Object> snapshot, String author, boolean gone)
      implements CommitOutcome {
    public Denied(String reason, String rev, Map<String, Object> snapshot, String author) {
      this(reason, rev, snapshot, author, false);
    }
  }
}
