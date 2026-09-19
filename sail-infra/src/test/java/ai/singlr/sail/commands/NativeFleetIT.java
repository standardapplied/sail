/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The sync lane on the binaries engineers install: the released build upgrading to the build under
 * test, across real sshd and the gateway's forced command. See {@link NativeFleet} for how the
 * binaries arrive and why a box is a container.
 */
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class NativeFleetIT {

  private static final int BODY_BYTES = 48_000;
  private static final int SPECS_PAST_ONE_FRAME = 370;
  private static final int CUT_AFTER_BYTES = 3_000_000;

  @Test
  void aFleetUpgradedMainFirstKeepsEveryWriteAndSurfacesTheConflict() throws Exception {
    try (var fleet = NativeFleet.openOrSkip()) {
      var main = fleet.main("uday", fleet.released());
      var mady = fleet.node("mady", fleet.released());
      main.serving(() -> main.createSpecs("seed", 5, BODY_BYTES));
      mady.sailOk("sync");
      fleet.assertConverged(mady);

      main.serving(
          () -> {
            main.createSpecs("main-new", 1, BODY_BYTES);
            main.retitle("seed-1", "edited on main");
          });
      fleet.offline(
          mady,
          () -> {
            mady.createSpecs("node-new", 1, BODY_BYTES);
            mady.retitle("seed-1", "edited on the node");
          });

      main.install(fleet.candidate());
      var beforeTheNodeUpgrades = mady.replicated();
      var stale = mady.sail("sync");
      if (NativeFleet.belowFloor(mady.version())) {
        assertNotEquals(0, stale.exit(), stale::output);
        assertTrue(stale.output().contains("sail upgrade"), stale::output);
        assertEquals(beforeTheNodeUpgrades, mady.replicated(), "a refused round changed the node");
      } else {
        assertEquals(0, stale.exit(), stale::output);
      }

      mady.install(fleet.candidate());
      mady.sailOk("sync");
      assertEquals(List.of("seed-1"), pendingConflicts(mady));
      assertEquals("edited on main", main.title("seed-1"));
      assertEquals("edited on the node", mady.title("seed-1"));
      assertEquals("1", main.query("SELECT count(*) FROM specs WHERE id = 'node-new-1'").strip());
      assertEquals("1", mady.query("SELECT count(*) FROM specs WHERE id = 'main-new-1'").strip());

      mady.sailOk("conflicts", "resolve", "seed-1", "--mine");
      mady.sailOk("sync");
      assertEquals(List.of(), pendingConflicts(mady));
      assertEquals("edited on the node", main.title("seed-1"));
      fleet.assertConverged(mady);
    }
  }

  @Test
  void aFreshNodePullsPastOneFrameAndACutTransportLosesNothing() throws Exception {
    try (var fleet = NativeFleet.openOrSkip()) {
      var main = fleet.main("uday", fleet.candidate());
      main.serving(() -> main.createSpecs("bulk", SPECS_PAST_ONE_FRAME, BODY_BYTES));
      var rejesh = fleet.node("rejesh", fleet.candidate());
      rejesh.shOk(
          """
          install -d /opt/cut
          printf '#!/bin/sh\\n/usr/bin/ssh "$@" | { stdbuf -o0 head -c %d; pkill -x ssh; }\\n' > /opt/cut/ssh
          chmod 755 /opt/cut/ssh
          """
              .formatted(CUT_AFTER_BYTES));

      var cut = rejesh.sh("PATH=/opt/cut:$PATH sail sync");

      assertNotEquals(0, cut.exit(), cut::output);
      assertEquals("ok", rejesh.query("PRAGMA integrity_check").strip());
      assertEquals(
          "0",
          rejesh
              .query(
                  "SELECT count(*) FROM specs s LEFT JOIN spec_content c ON c.spec_id = s.id"
                      + " WHERE c.spec_id IS NULL")
              .strip(),
          "a cut round left a spec without its content");

      var round = NativeFleet.json(rejesh.sailOk("sync", "--json"));

      assertTrue(pagesOf(round, "spec") >= 2, () -> "the pull never left one frame: " + round);
      fleet.assertConverged(rejesh);
      assertEquals(0, NativeFleet.json(rejesh.sailOk("sync", "--json")).get("pulled"));
    }
  }

  @Test
  void aNodeUpgradedAheadOfMainRecoversOnceMainFollows() throws Exception {
    try (var fleet = NativeFleet.openOrSkip()) {
      var main = fleet.main("uday", fleet.released());
      main.serving(() -> main.createSpecs("seed", 3, BODY_BYTES));
      var mady = fleet.node("mady", fleet.candidate());

      var ahead = mady.sail("sync");

      if (ahead.exit() != 0) {
        assertTrue(ahead.output().contains("upgrade main"), ahead::output);
      }
      fleet.offline(mady, () -> mady.createSpecs("node-ahead", 1, BODY_BYTES));
      main.serving(() -> main.createSpecs("before-upgrade", 1, BODY_BYTES));

      main.install(fleet.candidate());
      main.serving(() -> main.createSpecs("after-upgrade", 1, BODY_BYTES));
      mady.sailOk("sync");

      assertEquals("6", main.query("SELECT count(*) FROM specs WHERE project = 'demo'").strip());
      fleet.assertConverged(mady);
    }
  }

  private static List<String> pendingConflicts(NativeFleet.Box box) throws Exception {
    return NativeFleet.jsonList(box.sailOk("conflicts", "--json")).stream()
        .map(conflict -> (String) conflict.get("entity"))
        .toList();
  }

  @SuppressWarnings("unchecked")
  private static int pagesOf(Map<String, Object> round, String type) {
    return ((List<Map<String, Object>>) round.get("types"))
        .stream()
            .filter(report -> type.equals(report.get("type")))
            .mapToInt(report -> (Integer) report.get("pages"))
            .sum();
  }
}
