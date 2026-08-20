/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunActivityStamperTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private RunStore runStore;
  private RunActivityStamper stamper;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    runStore = new RunStore(db);
    stamper = new RunActivityStamper(runStore);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private String runningRun() {
    var id = DateTimeUtils.newId().toString();
    return runStore.create(
        id,
        "backend",
        "auth",
        "node-a",
        "node-a",
        "build",
        "claude-code",
        "feat/x",
        "do it",
        123,
        null,
        "/home/dev/.sail/runs/" + id + "/agent.log",
        "sail-agent-" + id);
  }

  private static Event progress(String type, Map<String, Object> data) {
    return Event.of("backend", "auth", type, "claude-code", "host", data);
  }

  private long journaledRevisions(String runId) {
    return db.queryOne(
            "SELECT COUNT(*) FROM change_log WHERE entity_type = 'run' AND entity_id = ?",
            row -> row.integer(0),
            runId)
        .orElseThrow();
  }

  @Test
  void nameReturnsRunActivity() {
    assertEquals("run-activity", stamper.name());
  }

  @Test
  void filterAcceptsExactlyTheWatcherProgressSet() {
    assertTrue(stamper.filter().test(progress(Event.WellKnownTypes.AGENT_TOOL_STARTED, Map.of())));
    assertTrue(stamper.filter().test(progress(Event.WellKnownTypes.AGENT_TOOL_FINISHED, Map.of())));
    assertTrue(stamper.filter().test(progress(Event.WellKnownTypes.AGENT_LOG_CHUNK, Map.of())));
    assertFalse(
        stamper.filter().test(progress(Event.WellKnownTypes.AGENT_SESSION_STARTED, Map.of())),
        "session lifecycle is not progress — the stall timer and presence must agree");
    assertFalse(stamper.filter().test(progress(Event.WellKnownTypes.AGENT_PRESENCE, Map.of())));
  }

  @Test
  void aProgressEventStampsTheRunItAddresses() {
    var id = runningRun();

    stamper.onEvent(
        progress(Event.WellKnownTypes.AGENT_TOOL_STARTED, Map.of(Event.WellKnownData.RUN_ID, id)));

    assertNotNull(runStore.findById(id).orElseThrow().lastActivityAt());
  }

  @Test
  void aChunkBurstYieldsOneWriteAndJournalsNothing() {
    var id = runningRun();
    var revisionsBefore = journaledRevisions(id);
    var revBefore = runStore.latestRev(id);

    stamper.onEvent(
        progress(Event.WellKnownTypes.AGENT_LOG_CHUNK, Map.of(Event.WellKnownData.RUN_ID, id)));
    var firstStamp = runStore.findById(id).orElseThrow().lastActivityAt();
    for (var i = 0; i < 200; i++) {
      stamper.onEvent(
          progress(Event.WellKnownTypes.AGENT_LOG_CHUNK, Map.of(Event.WellKnownData.RUN_ID, id)));
    }

    assertEquals(
        firstStamp,
        runStore.findById(id).orElseThrow().lastActivityAt(),
        "a burst inside the coalescing floor is one write, not one per chunk");
    assertEquals(
        revisionsBefore,
        journaledRevisions(id),
        "stamping must never mint revisions — a chunk stream would flood the ChangeLog");
    assertEquals(revBefore, runStore.latestRev(id));
  }

  @Test
  void anEventWithoutARunIdIsIgnored() {
    var id = runningRun();

    stamper.onEvent(progress(Event.WellKnownTypes.AGENT_TOOL_STARTED, Map.of()));

    assertNull(runStore.findById(id).orElseThrow().lastActivityAt());
  }

  @Test
  void aTerminalRunIsNeverStamped() {
    var id = runningRun();
    runStore.complete(id, "completed", 0);
    var revisions = journaledRevisions(id);

    stamper.onEvent(
        progress(Event.WellKnownTypes.AGENT_TOOL_STARTED, Map.of(Event.WellKnownData.RUN_ID, id)));

    assertNull(
        runStore.findById(id).orElseThrow().lastActivityAt(),
        "a late event must not dirty a row whose final revision is already journaled");
    assertEquals(revisions, journaledRevisions(id));
  }

  @Test
  void anUnknownRunIdIsANoOp() {
    assertDoesNotThrow(
        () ->
            stamper.onEvent(
                progress(
                    Event.WellKnownTypes.AGENT_TOOL_STARTED,
                    Map.of(Event.WellKnownData.RUN_ID, "ghost"))));
  }

  @Test
  void aStoreFailureIsSwallowedSoTheBusDrainSurvives() {
    var id = runningRun();
    db.close();
    var event =
        progress(Event.WellKnownTypes.AGENT_TOOL_STARTED, Map.of(Event.WellKnownData.RUN_ID, id));

    assertDoesNotThrow(() -> stamper.onEvent(event));
    db = null;
  }
}
