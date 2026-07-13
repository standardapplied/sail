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

class ProjectRenamePropagationIT {

  @TempDir Path root;

  @Test
  void renamePropagatesToEveryNode() throws Exception {
    try (var fleet = Fleet.of(root)) {
      var main = fleet.main("uday");
      var sumesh = fleet.node("sumesh", main);
      var mady = fleet.node("mady", main);
      main.createProject("p");
      fleet.syncAll(sumesh, mady);

      main.renameProject("p", "q");
      fleet.syncAll(sumesh, mady);

      for (var box : new Fleet.Box[] {main, sumesh, mady}) {
        assertFalse(box.hasProject("p"));
        assertTrue(box.hasProject("q"));
      }
    }
  }
}
