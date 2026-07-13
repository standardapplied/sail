/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression guard for main-authoritative Slack narration shipped in 9eecc8d. */
class MainOnlySlackNarrationIT {

  @TempDir Path root;

  @Test
  void mainIsTheSoleNarratorWithoutDuplicates() throws Exception {
    try (var fleet = Fleet.of(root)) {
      var main = fleet.main("uday");
      var sumesh = fleet.node("sumesh", main);
      fleet.scenario("s", "sumesh", sumesh);

      sumesh.dispatch("s");
      fleet.sync(sumesh);
      assertEquals(1, main.awaitSlackPosts(1).size());
      assertEquals(0, sumesh.slackPosts());

      sumesh.authoritativeStop("s");
      fleet.sync(sumesh);
      var narrated = main.awaitSlackPosts(4).size();
      assertEquals(4, narrated);
      fleet.sync(sumesh);
      assertEquals(narrated, main.slackPosts());
      assertEquals(0, sumesh.slackPosts());
    }
  }
}
