/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The run-delivery lane on real stores: the inbox is the spec room minus what the run was already
 * shown and what it said itself, and the watermark only ever moves forward, scoped by the run's own
 * spec.
 */
class RunDeliveryOperationsTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private EventBus bus;
  private RunStore runStore;
  private MessageStore messages;
  private SailOperations operations;
  private String runId;
  private String principal;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("run-delivery.db"));
    new SchemaManager(db).migrate();
    db.execute(
        """
        INSERT INTO specs
            (id, title, project, assignee, created_by, created_at, updated_at)
        VALUES ('room', 'Room', 'acme', 'ada', 'ada', 'now', 'now')""");
    db.execute(
        """
        INSERT INTO specs
            (id, title, project, assignee, created_by, created_at, updated_at)
        VALUES ('other', 'Other', 'acme', 'ada', 'ada', 'now', 'now')""");
    bus = new EventBus();
    runStore = new RunStore(db);
    messages = new MessageStore(db);
    operations =
        new SailOperations(
                new ShellExecutor(false),
                "sail.yaml",
                bus,
                null,
                new SpecStore(db),
                new ReviewStore(db),
                runStore,
                new ProjectStore(db),
                SyncScheduler.disabled(),
                null)
            .useMessages(messages);
    runId = newRun("room");
    principal = runStore.findById(runId).orElseThrow().principal();
  }

  @AfterEach
  void tearDown() {
    bus.close();
    db.close();
  }

  private String newRun(String specId) {
    var id = DateTimeUtils.newId().toString();
    return runStore.create(
        id,
        "acme",
        specId,
        "node-a",
        "ada",
        "build",
        "claude-code",
        "b",
        "t",
        null,
        null,
        "l",
        "u");
  }

  @Test
  void inboxListsUndeliveredForeignMessagesAndNamesTheNewestConsidered() {
    var fromAda = messages.append("room", "ada", "please rename the flag", null);
    var own = messages.append("room", principal, "on it", null);

    var inbox = operations.runInbox(runId).orThrow();

    assertEquals(runId, inbox.runId());
    assertEquals("room", inbox.specId());
    assertEquals(List.of(fromAda.id()), inbox.messages().stream().map(m -> m.id()).toList());
    assertEquals(own.id(), inbox.latest(), "own posts count toward the ack, never the delivery");

    var map = inbox.toMap();
    assertEquals(runId, map.get("run_id"));
    assertEquals("room", map.get("spec_id"));
    assertEquals(own.id(), map.get("latest"));
  }

  @Test
  void anEmptyRoomYieldsAnEmptyInbox() {
    var inbox = operations.runInbox(runId).orThrow();

    assertTrue(inbox.messages().isEmpty());
    assertNull(inbox.latest());
    assertFalse(inbox.toMap().containsKey("latest"));
  }

  @Test
  void aRunWithoutASpecHasAnEmptyInbox() {
    var adhoc =
        runStore.create(
            DateTimeUtils.newId().toString(),
            "acme",
            "",
            "node-a",
            "ada",
            "adhoc",
            "claude-code",
            null,
            "t",
            null,
            null,
            "l",
            "u");

    var inbox = operations.runInbox(adhoc).orThrow();

    assertTrue(inbox.messages().isEmpty());
    assertNull(inbox.specId());
    assertFalse(inbox.toMap().containsKey("spec_id"));
  }

  @Test
  void anUnknownRunIsRefused() {
    assertEquals(
        ErrorCode.RUN_NOT_FOUND, operations.runInbox("missing-run").asFailure().errorCode());
    assertEquals(
        ErrorCode.RUN_NOT_FOUND,
        operations.advanceRunWatermark("missing-run", "x").asFailure().errorCode());
  }

  @Test
  void acknowledgingAdvancesTheWatermarkAndEmptiesTheInbox() {
    var first = messages.append("room", "ada", "one", null);
    var second = messages.append("room", "ada", "two", null);

    var acked = operations.advanceRunWatermark(runId, second.id()).orThrow();
    assertEquals(second.id(), acked.delivered());
    assertEquals(second.id(), acked.toMap().get("delivered"));

    assertTrue(operations.runInbox(runId).orThrow().messages().isEmpty());

    var stale = operations.advanceRunWatermark(runId, first.id()).orThrow();
    assertEquals(
        second.id(), stale.delivered(), "a stale ack is an idempotent no-op, never a rewind");
  }

  @Test
  void theWatermarkOnlyAcceptsMessagesOnTheRunsOwnSpec() {
    var foreign = messages.append("other", "ada", "wrong room", null);

    assertEquals(
        ErrorCode.BAD_REQUEST,
        operations.advanceRunWatermark(runId, foreign.id()).asFailure().errorCode());
    assertEquals(
        ErrorCode.BAD_REQUEST, operations.advanceRunWatermark(runId, null).asFailure().errorCode());
    assertEquals(
        ErrorCode.BAD_REQUEST,
        operations.advanceRunWatermark(runId, "not-a-message").asFailure().errorCode());
  }

  @Test
  void afterReadsForwardAndExcludesBothCursorsAtOnce() {
    var first = messages.append("room", "ada", "one", null);
    var second = messages.append("room", "ada", "two", null);

    var page = operations.specMessages("room", null, first.id(), 50).orThrow();
    assertEquals(List.of(second.id()), page.messages().stream().map(m -> m.id()).toList());

    assertEquals(
        ErrorCode.BAD_REQUEST,
        operations.specMessages("room", first.id(), second.id(), 50).asFailure().errorCode(),
        "before and after are exclusive");
    assertEquals(
        ErrorCode.BAD_REQUEST,
        operations.specMessages("room", null, "not-a-uuid", 50).asFailure().errorCode());
  }
}
