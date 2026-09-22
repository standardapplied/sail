/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.RunStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A parked conflict over a real paged session. A protocol-4 round examines what main changed since
 * the checkpoint and what this box journaled since its base — and a run parked over its heartbeat
 * is neither: main's entry is behind the checkpoint and the stamp carries no revision. The conflict
 * itself has to keep the entity in the round, or no round ever looks at it again.
 */
class ParkedConflictSyncTest {

  @TempDir Path tempDir;
  private SyncBox main;
  private SyncBox node;
  private RunStore nodeRuns;
  private StoreReplica nodeReplica;

  @BeforeEach
  void setUp() {
    main = new SyncBox(tempDir, "main");
    node = new SyncBox(tempDir, "node");
    new FdeStore(main.db).add("node", null, null, "admin");
    nodeRuns = new RunStore(node.db);
    nodeReplica =
        new StoreReplica(
            node.id,
            nodeRuns,
            new ChangeLog(node.db),
            node.conflicts,
            node.syncState,
            runId -> nodeRuns.pushableFrom(runId, node.id));
  }

  @AfterEach
  void tearDown() {
    node.close();
    main.close();
  }

  private SyncSession.TypeReport round() throws IOException {
    try (var link = SyncBox.connect(main.server(new SyncPrincipal("node", true)), node)) {
      return link.reconcile("run", nodeReplica);
    }
  }

  @Test
  void aRunParkedOverItsHeartbeatIsReexaminedAndHealsThoughNeitherSideHasNews() throws Exception {
    var id = DateTimeUtils.newId().toString();
    nodeRuns.create(
        id,
        "backend",
        "auth",
        node.id,
        node.id,
        "build",
        "claude-code",
        "feat/auth",
        "do it",
        123,
        null,
        "/home/dev/.sail/runs/" + id + "/agent.log",
        "sail-agent-" + id);
    round();
    var mainRuns = new RunStore(main.db);
    var mainReplica =
        new StoreReplica(main.id, mainRuns, new ChangeLog(main.db), main.conflicts, main.syncState);
    var pushedButNeverAcknowledged = new LinkedHashMap<>(nodeReplica.current(id));
    pushedButNeverAcknowledged.put("last_activity_at", "2000-01-01T00:00:00Z");
    mainReplica.commit(id, pushedButNeverAcknowledged, mainReplica.currentRev(id));
    nodeRuns.stampActivity(id, Duration.ZERO);
    nodeReplica.recordConflict(
        id,
        nodeReplica.base(id),
        nodeReplica.current(id),
        pushedButNeverAcknowledged,
        List.of("last_activity_at"));
    nodeReplica.advanceCheckpoint(main.id, mainReplica.maxSeq());

    var report = round();

    assertEquals(List.of(), node.conflicts.pending(), "nothing is left to decide");
    assertEquals(1, report.report().merged());
    assertEquals(
        nodeRuns.findById(id).orElseThrow().lastActivityAt(),
        mainRuns.findById(id).orElseThrow().lastActivityAt());
  }
}
