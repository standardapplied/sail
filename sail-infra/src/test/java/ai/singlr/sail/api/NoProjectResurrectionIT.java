/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NoProjectResurrectionIT {

  @TempDir Path root;

  @Test
  void aStaleNodesOldIdentityCannotResurrectARenamedProject() throws Exception {
    try (var fleet = Fleet.of(root)) {
      var main = fleet.main("uday");
      var sumesh = fleet.node("sumesh", main);
      main.createProject("p");
      sumesh.createProject("p");

      main.renameProject("p", "q");
      fleet.sync(sumesh);

      assertFalse(main.hasProject("p"));
      assertFalse(sumesh.hasProject("p"));
      assertTrue(main.hasProject("q"));
    }
  }
}
