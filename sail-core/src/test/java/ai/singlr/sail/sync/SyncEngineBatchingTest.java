/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.ActingAs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The engine offers pending pushes as soon as they weigh the authority's budget, not only at the
 * end of the walk: a first upload of a large table holds one batch of snapshots at a time.
 */
@ActingAs
class SyncEngineBatchingTest {

  private static final long WEIGHT = 3;
  private static final long BUDGET = 7;

  @Test
  void pendingOffersAreCommittedOnceTheyWeighTheBudgetAndTheRestAtTheEnd() throws Exception {
    try (var node = new SyncBox("node")) {
      for (var i = 1; i <= 5; i++) {
        node.specs.create(SyncBox.spec("spec-" + i, "Spec " + i, "pending"));
      }
      var main = new WeighingMain();

      var report = new SyncEngine().reconcile(node.replica, main);

      assertEquals(List.of(3, 2), main.batches, "flushed at the budget, then the remainder");
      assertEquals(5, report.pushed());
      for (var i = 1; i <= 5; i++) {
        assertTrue(node.specs.latestRev("spec-" + i).startsWith("rev-"), "adopted main's rev");
      }
      assertEquals(5, main.accepted.size());
    }
  }

  @Test
  void anUnweighedAuthorityStillTakesTheWholeWalkAsOneBatch() throws Exception {
    try (var node = new SyncBox("node")) {
      for (var i = 1; i <= 5; i++) {
        node.specs.create(SyncBox.spec("spec-" + i, "Spec " + i, "pending"));
      }
      var main =
          new WeighingMain() {
            @Override
            public long weigh(Offer offer) {
              return 0;
            }

            @Override
            public long offerBudget() {
              return Long.MAX_VALUE;
            }
          };

      new SyncEngine().reconcile(node.replica, main);

      assertEquals(List.of(5), main.batches);
    }
  }

  /** An empty authority that accepts every offer and records how the engine batched them. */
  private static class WeighingMain implements MainReplica {
    final List<Integer> batches = new ArrayList<>();
    final List<String> accepted = new ArrayList<>();

    @Override
    public String id() {
      return "main";
    }

    @Override
    public Set<String> entityIds() {
      return Set.of();
    }

    @Override
    public Map<String, Object> current(String id) {
      return null;
    }

    @Override
    public String currentRev(String id) {
      return null;
    }

    @Override
    public State state(String id) {
      return new State(null, null);
    }

    @Override
    public CommitOutcome commit(String id, Map<String, Object> snapshot, String expectedRev) {
      accepted.add(id);
      return new CommitOutcome.Accepted("rev-" + accepted.size());
    }

    @Override
    public List<CommitOutcome> commitAll(List<Offer> offers) {
      batches.add(offers.size());
      return MainReplica.super.commitAll(offers);
    }

    @Override
    public long weigh(Offer offer) {
      return WEIGHT;
    }

    @Override
    public long offerBudget() {
      return BUDGET;
    }

    @Override
    public long maxSeq() {
      return 0;
    }
  }
}
