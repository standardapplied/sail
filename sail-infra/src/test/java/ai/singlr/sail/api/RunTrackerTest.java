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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunTrackerTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private RunStore runStore;
  private SyncScheduler scheduler;
  private final AtomicInteger reconciles = new AtomicInteger();
  private CountDownLatch reconciled;
  private RunTracker tracker;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    runStore = new RunStore(db);
    reconciled = new CountDownLatch(1);
    scheduler =
        new SyncScheduler(
            () -> {
              reconciles.incrementAndGet();
              reconciled.countDown();
            },
            Duration.ofMillis(1),
            Duration.ofMillis(1));
    tracker = new RunTracker(runStore, scheduler, () -> "node-a");
  }

  @AfterEach
  void tearDown() {
    scheduler.close();
    if (db != null) db.close();
  }

  private String runningRun(String project, String specId) {
    return runStore.create(
        DateTimeUtils.newId().toString(),
        project,
        specId,
        "node-a",
        "build",
        "claude-code",
        "feat/x",
        "do it",
        123,
        null,
        "/home/dev/.sail/runs/r/agent.log");
  }

  private String runningRunOn(String project, String specId, String node) {
    return runStore.create(
        DateTimeUtils.newId().toString(),
        project,
        specId,
        node,
        "build",
        "claude-code",
        "feat/x",
        "do it",
        123,
        null,
        "/home/dev/.sail/runs/r/agent.log");
  }

  private static Event stopped(String project, Map<String, Object> data) {
    return Event.of(
        project, "auth", Event.WellKnownTypes.AGENT_SESSION_STOPPED, "claude-code", "host", data);
  }

  private static Event stopped(String project, String runId, Map<String, Object> data) {
    var merged = new LinkedHashMap<String, Object>(data);
    merged.put(Event.WellKnownData.RUN_ID, runId);
    return stopped(project, merged);
  }

  @Test
  void nameReturnsRunTracker() {
    assertEquals("run-tracker", tracker.name());
  }

  @Test
  void filterAcceptsTerminalEventsAndRejectsTheRest() {
    assertTrue(
        tracker
            .filter()
            .test(Event.of("p", "s", Event.WellKnownTypes.AGENT_SESSION_STOPPED, "a", "h")));
    assertTrue(
        tracker
            .filter()
            .test(Event.of("p", "s", Event.WellKnownTypes.AGENT_SESSION_COMPLETED, "a", "h")));
    assertFalse(
        tracker
            .filter()
            .test(Event.of("p", "s", Event.WellKnownTypes.AGENT_SESSION_STARTED, "a", "h")),
        "the launcher writes the run row; the tracker only closes it out");
    assertFalse(tracker.filter().test(Event.of("p", "s", "spec_dispatched", "a", "h")));
  }

  @Test
  void stoppedCompletesTheRunningRunWithItsExitCode() {
    var id = runningRun("backend", "auth");

    tracker.onEvent(stopped("backend", id, Map.of(Event.WellKnownData.EXIT_CODE, 137)));

    var run = runStore.findById(id).orElseThrow();
    assertEquals("stopped", run.status());
    assertEquals(137, run.exitCode());
    assertNotNull(run.completedAt());
  }

  @Test
  void completedSetsCompletedStatus() {
    var id = runningRun("backend", "auth");

    tracker.onEvent(
        Event.of(
            "backend",
            "auth",
            Event.WellKnownTypes.AGENT_SESSION_COMPLETED,
            "claude-code",
            "host",
            Map.of(Event.WellKnownData.RUN_ID, id)));

    assertEquals("completed", runStore.findById(id).orElseThrow().status());
  }

  @Test
  void aStaleStopAddressedToAnOldRunLeavesANewerRunUntouched() {
    var older = runningRun("backend", "auth");
    var newer = runningRun("backend", "auth");

    tracker.onEvent(stopped("backend", older, Map.of(Event.WellKnownData.EXIT_CODE, 0)));

    assertEquals("stopped", runStore.findById(older).orElseThrow().status());
    assertEquals(
        "running",
        runStore.findById(newer).orElseThrow().status(),
        "a delayed stop for the old run must not complete the newer run of the same project");
  }

  @Test
  void aStopCarryingNoRunIdIsIgnored() {
    var id = runningRun("backend", "auth");

    tracker.onEvent(stopped("backend", Map.of(Event.WellKnownData.EXIT_CODE, 0)));

    assertEquals(
        "running",
        runStore.findById(id).orElseThrow().status(),
        "a stop with no run correlation cannot complete a run");
  }

  @Test
  void stoppedWithoutExitCodeLeavesItNull() {
    runningRun("backend", "auth");

    tracker.onEvent(stopped("backend", Map.of()));

    assertNull(runStore.latestForProjectOnNode("backend", "node-a").orElseThrow().exitCode());
  }

  @Test
  void aWatcherStopArrivingAfterAnOperatorCancelDoesNotReopenTheRun() {
    var id = runningRun("backend", "auth");
    runStore.complete(id, "stopped", null);

    tracker.onEvent(
        stopped(
            "backend",
            id,
            Map.of(
                Event.WellKnownData.EXIT_CODE,
                143,
                Event.WellKnownData.SOURCE,
                Event.WellKnownData.SOURCE_WATCHER)));

    var run = runStore.findById(id).orElseThrow();
    assertEquals("stopped", run.status());
    assertEquals(143, run.exitCode());
  }

  @Test
  void anAuthoritativeStopUpgradesTheExitCodeOfAnAlreadyFinishedRun() {
    var id = runningRun("backend", "auth");
    runStore.complete(id, "stopped", null);

    tracker.onEvent(
        stopped(
            "backend",
            id,
            Map.of(
                Event.WellKnownData.EXIT_CODE,
                137,
                Event.WellKnownData.SOURCE,
                Event.WellKnownData.SOURCE_WATCHER)));

    assertEquals(137, runStore.findById(id).orElseThrow().exitCode());
  }

  @Test
  void stringExitCodeIsParsed() {
    var id = runningRun("backend", "auth");
    runStore.complete(id, "stopped", null);

    tracker.onEvent(stopped("backend", id, Map.of(Event.WellKnownData.EXIT_CODE, "9")));

    assertEquals(9, runStore.findById(id).orElseThrow().exitCode());
  }

  @Test
  void anInvalidStringExitCodeIsIgnored() {
    var id = runningRun("backend", "auth");
    runStore.complete(id, "stopped", null);

    tracker.onEvent(stopped("backend", id, Map.of(Event.WellKnownData.EXIT_CODE, "nope")));

    assertNull(runStore.findById(id).orElseThrow().exitCode());
  }

  @Test
  void aStopWithNoRunningRunAndNoExitCodeIsANoOp() {
    tracker.onEvent(stopped("backend", Map.of()));

    assertTrue(runStore.latestForProjectOnNode("backend", "node-a").isEmpty());
  }

  @Test
  void aStopWithNoRunningRunAndAnAlreadyStampedLatestIsLeftAlone() {
    var id = runningRun("backend", "auth");
    runStore.complete(id, "stopped", 0);

    tracker.onEvent(stopped("backend", id, Map.of(Event.WellKnownData.EXIT_CODE, 137)));

    assertEquals(0, runStore.findById(id).orElseThrow().exitCode(), "the recorded exit stands");
  }

  @Test
  void completingARunFiresTheSyncOnWriteTrigger() throws Exception {
    var id = runningRun("backend", "auth");

    tracker.onEvent(stopped("backend", id, Map.of(Event.WellKnownData.EXIT_CODE, 0)));

    assertTrue(reconciled.await(5, TimeUnit.SECONDS), "a completion propagates to main");
    assertTrue(reconciles.get() >= 1);
  }

  @Test
  void anUnhandledTypeIsIgnored() {
    runningRun("backend", "auth");

    tracker.onEvent(
        Event.of(
            "backend", "auth", Event.WellKnownTypes.AGENT_SESSION_STARTED, "claude-code", "host"));

    assertEquals(
        "running", runStore.latestForProjectOnNode("backend", "node-a").orElseThrow().status());
  }

  @Test
  void exceptionInHandlerDoesNotPropagate() {
    db.close();
    db = null;
    assertDoesNotThrow(
        () -> tracker.onEvent(stopped("p", "some-run", Map.of(Event.WellKnownData.EXIT_CODE, 0))));
  }

  @Test
  void aForeignNodesRunningRunIsNeverCompletedByThisNodesStop() {
    var foreign = runningRunOn("backend", "theirs", "node-b");

    tracker.onEvent(stopped("backend", foreign, Map.of(Event.WellKnownData.EXIT_CODE, 0)));

    var run = runStore.findById(foreign).orElseThrow();
    assertEquals("running", run.status(), "another box's live run must be left untouched");
    assertNull(run.exitCode());
  }

  @Test
  void onlyThisNodesRunIsClosedWhenBothNodesRunTheSameProject() {
    var mine = runningRunOn("backend", "mine", "node-a");
    var theirs = runningRunOn("backend", "theirs", "node-b");

    tracker.onEvent(stopped("backend", mine, Map.of(Event.WellKnownData.EXIT_CODE, 0)));

    assertEquals("stopped", runStore.findById(mine).orElseThrow().status());
    assertEquals("running", runStore.findById(theirs).orElseThrow().status());
  }

  @Test
  void aStandaloneBoxWithNoHandleClosesOutItsOwnBlankNodeRun() {
    var standalone = new RunTracker(runStore, scheduler, () -> null);
    var id = runningRunOn("backend", "auth", "");

    standalone.onEvent(stopped("backend", id, Map.of(Event.WellKnownData.EXIT_CODE, 0)));

    assertEquals("stopped", runStore.findById(id).orElseThrow().status());
  }

  @Test
  void integrationWithEventBus() throws Exception {
    var id = runningRun("backend", "auth");
    try (var bus = new EventBus()) {
      var latch = new CountDownLatch(1);
      bus.subscribe(BusTesting.latching(tracker, latch));
      bus.publish(stopped("backend", id, Map.of(Event.WellKnownData.EXIT_CODE, 5)));

      BusTesting.awaitDelivery(latch);

      assertEquals(5, runStore.findById(id).orElseThrow().exitCode());
    }
  }
}
