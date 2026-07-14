/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WatcherRearmerTest {

  private static final LongPredicate DEAD = pid -> false;
  private static final LongPredicate ALIVE = pid -> true;
  private static final Predicate<String> NO_UNIT = project -> false;
  private static final Predicate<String> UNIT_ACTIVE = project -> true;

  private static final WatcherSpawner.Unit LAUNCHED =
      new WatcherSpawner.Unit("sail-watch-test-project", "user", false);
  private static final WatcherSpawner.Unit ADOPTED =
      new WatcherSpawner.Unit("sail-watch-test-project", "system", true);

  @TempDir Path tempDir;
  private Sqlite db;
  private SpecStore specStore;
  private RunStore sessionStore;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("rearm.db"));
    new SchemaManager(db).migrate();
    specStore = new SpecStore(db);
    sessionStore = new RunStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private void createInProgressSpec(String id) {
    specStore.create(
        new SpecStore.SpecRow(
            id,
            "test-project",
            "Test spec",
            SpecStatus.IN_PROGRESS,
            null,
            "claude-code",
            null,
            null,
            "feat/test",
            0,
            null,
            "",
            "",
            null,
            List.of(),
            List.of()));
  }

  private String runningSession(String specId, Integer watcherPid) {
    var id = DateTimeUtils.newId().toString();
    return sessionStore.create(
        id,
        "test-project",
        specId,
        "node-a",
        "build",
        "claude-code",
        "feat/test",
        "task",
        1,
        watcherPid,
        "/home/dev/.sail/runs/" + id + "/agent.log",
        "sail-agent-" + id);
  }

  private WatcherRearmer rearmer(
      MissedStopReconciler.UnitProbe agentUnitProbe,
      Predicate<String> watcherUnitActive,
      LongPredicate watcherAlive,
      WatcherRearmer.WatcherRelauncher relauncher) {
    return new WatcherRearmer(
        specStore,
        sessionStore,
        agentUnitProbe,
        watcherUnitActive,
        watcherAlive,
        () -> "node-a",
        relauncher);
  }

  private String preUpgradeRunningSession(String specId) {
    return sessionStore.create(
        DateTimeUtils.newId().toString(),
        "test-project",
        specId,
        "node-a",
        "build",
        "claude-code",
        "feat/test",
        "task",
        1,
        null,
        "/home/dev/.sail/agent.log");
  }

  @Test
  void aPreUpgradeRunWithNoRecordedUnitIsLeftToItsOwnDetachedWatcher() {
    createInProgressSpec("auth");
    preUpgradeRunningSession("auth");
    var probed = new AtomicInteger();
    var relaunches = new AtomicInteger();

    var rearmed =
        rearmer(
                (project, runId, unit) -> {
                  probed.incrementAndGet();
                  return true;
                },
                NO_UNIT,
                DEAD,
                run -> {
                  relaunches.incrementAndGet();
                  return Optional.of(LAUNCHED);
                })
            .rearm();

    assertEquals(0, rearmed);
    assertEquals(0, probed.get(), "this pass cannot know that unit's liveness");
    assertEquals(0, relaunches.get());
  }

  @Test
  void theProbeCoverageCheckAndRelaunchAllAddressTheRunItself() {
    createInProgressSpec("auth");
    var runId = runningSession("auth", null);
    var probes = new java.util.concurrent.ConcurrentLinkedQueue<String>();
    var covered = new java.util.concurrent.ConcurrentLinkedQueue<String>();
    var relaunched = new java.util.concurrent.ConcurrentLinkedQueue<String>();

    var rearmed =
        rearmer(
                (project, id, unit) -> {
                  probes.add(id + "|" + unit);
                  return true;
                },
                id -> {
                  covered.add(id);
                  return false;
                },
                DEAD,
                run -> {
                  relaunched.add(run.id() + "|" + run.unit());
                  return Optional.of(LAUNCHED);
                })
            .rearm();

    assertEquals(1, rearmed);
    assertEquals(List.of(runId + "|sail-agent-" + runId), List.copyOf(probes));
    assertEquals(List.of(runId), List.copyOf(covered));
    assertEquals(List.of(runId + "|sail-agent-" + runId), List.copyOf(relaunched));
  }

  @Test
  void aForeignNodesRunIsNeverRearmedHere() {
    createInProgressSpec("auth");
    runningSession("auth", 5678);
    var relaunches = new AtomicInteger();
    var rearmer =
        new WatcherRearmer(
            specStore,
            sessionStore,
            (project, runId, unit) -> true,
            NO_UNIT,
            DEAD,
            () -> "node-b",
            run -> {
              relaunches.incrementAndGet();
              return Optional.of(LAUNCHED);
            });

    assertEquals(0, rearmer.rearm());
    assertEquals(0, relaunches.get());
  }

  @Test
  void rearmsAnUncoveredRunningAgentAsAUnit() {
    createInProgressSpec("auth");
    runningSession("auth", 5678);
    var relaunches = new AtomicInteger();

    var rearmed =
        rearmer(
                (project, runId, unit) -> true,
                NO_UNIT,
                DEAD,
                run -> {
                  relaunches.incrementAndGet();
                  return Optional.of(LAUNCHED);
                })
            .rearm();

    assertEquals(1, rearmed);
    assertEquals(1, relaunches.get());
  }

  @Test
  void anActiveWatcherUnitCoversTheProjectWithoutConsultingPidOrAgentUnit() {
    createInProgressSpec("auth");
    runningSession("auth", null);
    var probed = new AtomicInteger();

    var rearmed =
        rearmer(
                (project, runId, unit) -> {
                  probed.incrementAndGet();
                  return true;
                },
                UNIT_ACTIVE,
                DEAD,
                run -> Optional.of(LAUNCHED))
            .rearm();

    assertEquals(0, rearmed);
    assertEquals(0, probed.get());
  }

  @Test
  void aLiveRecordedWatcherPidCoversTheProject() {
    createInProgressSpec("auth");
    runningSession("auth", 5678);
    var relaunches = new AtomicInteger();

    var rearmed =
        rearmer(
                (project, runId, unit) -> true,
                NO_UNIT,
                ALIVE,
                run -> {
                  relaunches.incrementAndGet();
                  return Optional.of(LAUNCHED);
                })
            .rearm();

    assertEquals(0, rearmed);
    assertEquals(0, relaunches.get());
  }

  @Test
  void aSessionWithNoRecordedPidAndNoUnitIsRearmedBecauseDoublingIsUnrepresentable() {
    createInProgressSpec("auth");
    runningSession("auth", null);

    var rearmed =
        rearmer((project, runId, unit) -> true, NO_UNIT, DEAD, run -> Optional.of(ADOPTED)).rearm();

    assertEquals(1, rearmed);
  }

  @Test
  void anInactiveAgentUnitIsTheSweepsJobNotARearm() {
    createInProgressSpec("auth");
    runningSession("auth", 5678);
    var relaunches = new AtomicInteger();

    var rearmed =
        rearmer(
                (project, runId, unit) -> false,
                NO_UNIT,
                DEAD,
                run -> {
                  relaunches.incrementAndGet();
                  return Optional.of(LAUNCHED);
                })
            .rearm();

    assertEquals(0, rearmed);
    assertEquals(0, relaunches.get());
  }

  @Test
  void terminalSessionsAndSpecsWithoutSessionsAreIgnored() {
    createInProgressSpec("finished");
    var completed = runningSession("finished", 5678);
    sessionStore.complete(completed, "stopped", 0);
    createInProgressSpec("bare");

    var rearmed =
        rearmer((project, runId, unit) -> true, NO_UNIT, DEAD, run -> Optional.of(LAUNCHED))
            .rearm();

    assertEquals(0, rearmed);
  }

  @Test
  void anEmptyRelaunchIsLoggedNotCounted() {
    createInProgressSpec("auth");
    runningSession("auth", 5678);

    var rearmed =
        rearmer((project, runId, unit) -> true, NO_UNIT, DEAD, run -> Optional.empty()).rearm();

    assertEquals(0, rearmed);
  }

  @Test
  void aFailingRelaunchIsLoggedAndDoesNotShadowOtherSpecs() {
    createInProgressSpec("broken");
    runningSession("broken", 1111);
    createInProgressSpec("auth");
    runningSession("auth", 2222);
    var attempts = new AtomicInteger();

    var rearmed =
        rearmer(
                (project, runId, unit) -> true,
                NO_UNIT,
                DEAD,
                run -> {
                  if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("relaunch failed");
                  }
                  return Optional.of(LAUNCHED);
                })
            .rearm();

    assertEquals(1, rearmed);
    assertEquals(2, attempts.get());
  }

  @Test
  void aStoreErrorIsSwallowedSoStartupIsNeverBlocked() {
    createInProgressSpec("auth");
    runningSession("auth", 5678);
    var rearmer =
        rearmer((project, runId, unit) -> true, NO_UNIT, DEAD, run -> Optional.of(LAUNCHED));
    db.close();
    db = null;

    assertEquals(0, assertDoesNotThrow(rearmer::rearm));
  }

  @Test
  void periodicRearmSchedulesRunsAndClosesWithoutStackingPasses() {
    createInProgressSpec("auth");
    runningSession("auth", 5678);
    try (var rearmer =
        rearmer((project, runId, unit) -> true, NO_UNIT, DEAD, run -> Optional.of(LAUNCHED))) {
      rearmer.start();
      rearmer.start(Duration.ofHours(1));

      assertTrue(rearmer.rearmIfIdle());
    }
  }

  @Test
  void livingProcessSeesThisJvmAliveAndANonexistentPidDead() {
    var alive = WatcherRearmer.livingProcess();

    assertTrue(alive.test(ProcessHandle.current().pid()));
    assertFalse(alive.test(999_999_999L));
  }
}
