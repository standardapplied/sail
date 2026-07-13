/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression guard for run ownership shipped in e0a8048 and hardened in c750d69. */
class CrossBoxRunOwnershipIT {

  @TempDir Path root;

  @Test
  void foreignRunsAreVisibleButNeverAdopted() throws Exception {
    try (var fleet = Fleet.of(root)) {
      var main = fleet.main("uday");
      var sumesh = fleet.node("sumesh", main);
      var mady = fleet.node("mady", main);
      fleet.scenario("s", "sumesh", sumesh, mady);

      var runId = sumesh.dispatch("s").runId();
      fleet.sync(sumesh);
      fleet.sync(mady);

      assertEquals("sumesh", main.runs("s").getFirst().node());
      assertEquals("sumesh", mady.runs("s").getFirst().node());
      assertEquals(0, main.reviewCount("s"));
      assertEquals(0, mady.reviewCount("s"));
      main.assertSpecStatus("s", SpecStatus.IN_PROGRESS);
      mady.assertSpecStatus("s", SpecStatus.IN_PROGRESS);
      for (var result : List.of(main.runLog(runId), mady.runLog(runId))) {
        assertTrue(result.isFailure());
        assertEquals(ErrorCode.RUN_ON_OTHER_NODE, result.errorCode());
      }
    }
  }
}
