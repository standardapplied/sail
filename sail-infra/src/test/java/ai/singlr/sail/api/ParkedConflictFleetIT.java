/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import ai.singlr.sail.sync.StoreReplica;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The field's state, cleared by the command the field runs: a node holding a conflict parked over a
 * run's heartbeat — checkpoint already past main's entry, the stamp unjournaled, neither side with
 * news — runs {@code sail sync} as its own process and comes out with nothing left to decide.
 */
class ParkedConflictFleetIT {

  @TempDir Path root;

  @Test
  void aSyncClearsAConflictParkedOverAHeartbeat() throws Exception {
    try (var fleet = Fleet.of(root)) {
      var main = fleet.main("uday");
      var mady = fleet.node("mady", main);
      fleet.scenario("s", "mady", mady);
      mady.dispatch("s");
      fleet.sync(mady);
      var runId = mady.runs("s").getFirst().id();

      try (var mainDb = Sqlite.open(root.resolve("uday-home/.sail/sail.db"));
          var nodeDb = Sqlite.open(root.resolve("mady-home/.sail/sail.db"))) {
        var mainRuns = new RunStore(mainDb);
        var nodeRuns = new RunStore(nodeDb);
        var conflicts = new SyncConflicts(nodeDb);
        var mainReplica =
            new StoreReplica(
                "uday-box",
                mainRuns,
                new ChangeLog(mainDb),
                new SyncConflicts(mainDb),
                new SyncState(mainDb));
        var nodeReplica =
            new StoreReplica(
                "mady-box", nodeRuns, new ChangeLog(nodeDb), conflicts, new SyncState(nodeDb));
        var pushedButNeverAcknowledged = new LinkedHashMap<>(nodeReplica.current(runId));
        pushedButNeverAcknowledged.put("last_activity_at", "2000-01-01T00:00:00Z");
        Actor.call(
            Actor.sync("mady", Role.MEMBER),
            () ->
                mainReplica.commit(
                    runId, pushedButNeverAcknowledged, mainReplica.currentRev(runId)));
        nodeRuns.stampActivity(runId, Duration.ZERO);
        nodeReplica.recordConflict(
            runId,
            nodeReplica.base(runId),
            nodeReplica.current(runId),
            pushedButNeverAcknowledged,
            List.of("last_activity_at"));
        var mainPeer =
            nodeDb
                .queryOne(
                    "SELECT peer FROM sync_state WHERE entity_type = 'run'", row -> row.text(0))
                .orElseThrow();
        nodeReplica.advanceCheckpoint(mainPeer, mainReplica.maxSeq());
        assertEquals(1, conflicts.pending().size());

        fleet.sync(mady);

        assertEquals(List.of(), conflicts.pending(), "sail sync left a heartbeat to decide");
        assertEquals(
            nodeRuns.findById(runId).orElseThrow().lastActivityAt(),
            mainRuns.findById(runId).orElseThrow().lastActivityAt());
      }
    }
  }
}
