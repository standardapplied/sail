/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fleet invariant behind the clean-stop lane: an operator cancel is terminal
 * <em>everywhere</em>. The stop records the intent (spec {@code cancelled}, run {@code stopped}) as
 * ordinary synced revisions, so every peer adopts a terminal spec it will never resume — no box
 * holds it {@code in_progress}/{@code review}, no review is ever created, and a maximally
 * aggressive missed-stop reconciler pass on every box replays nothing. Before the clean-stop lane
 * existed, a killed agent left its spec {@code in_progress} and the executing box's reconciler
 * replayed the stop a sweep later, firing a review for deliberately abandoned work — reverting the
 * cancel's status write makes this test fail exactly that way.
 */
class CleanStopNoResumeIT {

  @TempDir Path root;

  @Test
  void anOperatorCancelIsTerminalOnEveryBoxAndNothingResumesIt() throws Exception {
    try (var fleet = Fleet.of(root)) {
      var main = fleet.main("uday");
      var sumesh = fleet.node("sumesh", main);
      var mady = fleet.node("mady", main);
      fleet.scenario("s", "sumesh", sumesh, mady);

      sumesh.dispatch("s");
      fleet.sync(sumesh);
      main.assertSpecStatus("s", SpecStatus.IN_PROGRESS);

      var outcome = sumesh.stop();
      var notRunning = assertInstanceOf(StopOperations.NotRunning.class, outcome);
      assertTrue(notRunning.specCancelled(), "the stop must cancel the in-progress spec");
      assertTrue(notRunning.runReleased(), "the stop must release the running run");

      fleet.sync(sumesh);
      fleet.sync(mady);

      for (var box : new Fleet.Box[] {main, sumesh, mady}) {
        box.assertSpecStatus("s", SpecStatus.CANCELLED);
        assertEquals(
            "stopped",
            box.runs("s").getFirst().status(),
            "the cancelled run must be terminal on " + box.handle());
        assertEquals(
            0,
            box.reviewCount("s"),
            "no review may ever be created for a cancelled spec on " + box.handle());
        assertEquals(
            0,
            box.reconcileSweep(),
            "the reaper must replay nothing for a cancelled spec on " + box.handle());
        box.assertSpecStatus("s", SpecStatus.CANCELLED);
      }
    }
  }

  @Test
  void aRepeatedStopAfterTheCancelIsAPureNoOp() throws Exception {
    try (var fleet = Fleet.of(root)) {
      var main = fleet.main("uday");
      var sumesh = fleet.node("sumesh", main);
      fleet.scenario("s", "sumesh", sumesh);

      sumesh.dispatch("s");
      sumesh.stop();

      var second = sumesh.stop();

      var notRunning = assertInstanceOf(StopOperations.NotRunning.class, second);
      assertEquals(false, notRunning.mutated(), "a repeated stop must write nothing");
      sumesh.assertSpecStatus("s", SpecStatus.CANCELLED);
    }
  }
}
