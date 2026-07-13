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

/** Regression guard for the review-strand resilience shipped in 60950d7. */
class LiveRunReviewResilienceIT {

  @TempDir Path root;

  @Test
  void anOutOfBandReviewStatusCannotDropTheAuthoritativeStop() throws Exception {
    try (var fleet = Fleet.of(root)) {
      var main = fleet.main("uday");
      var sumesh = fleet.node("sumesh", main);
      fleet.scenario("s", "sumesh", sumesh);

      sumesh.dispatch("s");
      sumesh.updateStatus("s", SpecStatus.REVIEW);
      sumesh.authoritativeStop("s");

      assertEquals(1, sumesh.reviewCount("s"));
      sumesh.assertSpecStatus("s", SpecStatus.AWAITING_MERGE);
    }
  }
}
