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

class ProjectRenameOnNodePropagationIT {

  @TempDir Path root;

  @Test
  void aNodeInitiatedRenamePropagatesToMainAndEveryPeer() throws Exception {
    try (var fleet = Fleet.of(root)) {
      var main = fleet.main("uday");
      var sumesh = fleet.node("sumesh", main);
      var mady = fleet.node("mady", main);
      main.createProject("p");
      fleet.syncAll(sumesh, mady);

      sumesh.renameProject("p", "q");
      fleet.sync(sumesh);
      fleet.syncAll(mady);

      for (var box : new Fleet.Box[] {main, sumesh, mady}) {
        assertFalse(box.hasProject("p"), "the old name is gone on " + box.handle());
        assertTrue(box.hasProject("q"), "the new name reached " + box.handle());
      }
    }
  }
}
