/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.config.SpecStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FullFleetLoopIT {

  @TempDir Path root;

  @Test
  void dispatchReviewPassAndAwaitingMergeConvergeAcrossTheFleet() throws Exception {
    try (var fleet = Fleet.of(root)) {
      var main = fleet.main("uday");
      var sumesh = fleet.node("sumesh", main);
      var mady = fleet.node("mady", main);
      fleet.scenario("s", "sumesh", sumesh, mady);

      sumesh.dispatch("s");
      fleet.sync(sumesh);
      assertEquals(1, main.awaitSlackPosts(1).size());
      sumesh.authoritativeStop("s");
      fleet.sync(sumesh);
      fleet.sync(mady);

      for (var box : new Fleet.Box[] {main, sumesh, mady}) {
        box.assertSpecStatus("s", SpecStatus.AWAITING_MERGE);
      }
      main.awaitSlackPosts(2);
      assertEquals(0, sumesh.slackPosts());
      assertEquals(0, mady.slackPosts());
    }
  }
}
