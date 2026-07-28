/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Messages posted through the production API on any box ride the real sync lane — the {@code sync}
 * subprocess, the ssh shim, and main's {@code _sync} RPC server with its authenticated
 * peer-attribution check — and converge on every box with author and order intact.
 */
class MessageFleetIT {

  @TempDir Path root;

  @Test
  void messagesPostedOnNodeAndMainConvergeAcrossTheFleet() throws Exception {
    try (var fleet = Fleet.of(root)) {
      var main = fleet.main("uday");
      var sumesh = fleet.node("sumesh", main);
      var mady = fleet.node("mady", main);
      fleet.scenario("s", "sumesh", sumesh, mady);

      var fromNode = sumesh.postMessage("s", "starting on the build");
      var fromMain = main.postMessage("s", "prefer the batched delete");
      fleet.sync(sumesh);
      fleet.sync(mady);
      fleet.sync(sumesh);

      for (var box : new Fleet.Box[] {main, sumesh, mady}) {
        var room = box.listMessages("s");
        assertEquals(2, room.size(), "room must converge on " + box.handle());
        var byId =
            room.stream().collect(Collectors.toMap(SpecMessageView::id, Function.identity()));
        assertEquals("sumesh", byId.get(fromNode.id()).author());
        assertEquals("uday", byId.get(fromMain.id()).author());
        assertEquals(fromNode.body(), byId.get(fromNode.id()).body());
        assertEquals(
            List.of(fromNode.id(), fromMain.id()).stream().sorted().toList(),
            room.stream().map(SpecMessageView::id).toList(),
            "every box must page the room in the same id order");
      }
    }
  }
}
