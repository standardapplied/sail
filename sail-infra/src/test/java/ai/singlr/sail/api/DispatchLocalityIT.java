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

/** Regression guard for dispatch locality shipped in 461d126. */
class DispatchLocalityIT {

  @TempDir Path root;

  @Test
  void onlyTheAssignedBoxCanDispatch() throws Exception {
    try (var fleet = Fleet.of(root)) {
      var main = fleet.main("uday");
      var sumesh = fleet.node("sumesh", main);
      var mady = fleet.node("mady", main);
      fleet.scenario("s", "mady", sumesh, mady);

      var refusal = sumesh.dispatchRefusal("s");
      assertEquals(ErrorCode.RUNS_ON_OTHER_NODE, refusal.failure().errorCode());
      mady.dispatch("s");
      fleet.sync(mady);
      fleet.sync(sumesh);

      main.assertSpecStatus("s", SpecStatus.IN_PROGRESS);
      sumesh.assertSpecStatus("s", SpecStatus.IN_PROGRESS);
      mady.assertSpecStatus("s", SpecStatus.IN_PROGRESS);
    }
  }
}
