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

/** Regression guard for synced revision peer attribution shipped in aabdd6b. */
class SyncProvenanceIT {

  @TempDir Path root;

  @Test
  void aPushedRevisionNamesThePushingPeer() throws Exception {
    try (var fleet = Fleet.of(root)) {
      var main = fleet.main("uday");
      var sumesh = fleet.node("sumesh", main);
      fleet.scenario("s", "sumesh", sumesh);

      sumesh.updateStatus("s", SpecStatus.IN_PROGRESS);
      fleet.sync(sumesh);

      assertEquals("sumesh", main.latestPeer("s"));
    }
  }
}
