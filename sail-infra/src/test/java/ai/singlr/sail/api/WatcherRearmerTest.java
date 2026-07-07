/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SessionStore;
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
  private SessionStore sessionStore;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("rearm.db"));
    new SchemaManager(db).migrate();
    specStore = new SpecStore(db);
    sessionStore = new SessionStore(db);
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
    return sessionStore.create(
        "test-project", specId, "claude-code", "feat/test", "task", 1, watcherPid);
  }

  private WatcherRearmer rearmer(
      MissedStopReconciler.UnitProbe agentUnitProbe,
      Predicate<String> watcherUnitActive,
      LongPredicate watcherAlive,
      WatcherRearmer.WatcherRelauncher relauncher) {
    return new WatcherRearmer(
        specStore, sessionStore, agentUnitProbe, watcherUnitActive, watcherAlive, relauncher);
  }

  @Test
  void rearmsAnUncoveredRunningAgentAsAUnit() {
    createInProgressSpec("auth");
    runningSession("auth", 5678);
    var relaunches = new AtomicInteger();

    var rearmed =
        rearmer(
                project -> true,
                NO_UNIT,
                DEAD,
                project -> {
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
                project -> {
                  probed.incrementAndGet();
                  return true;
                },
                UNIT_ACTIVE,
                DEAD,
                project -> Optional.of(LAUNCHED))
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
                project -> true,
                NO_UNIT,
                ALIVE,
                project -> {
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

    var rearmed = rearmer(project -> true, NO_UNIT, DEAD, project -> Optional.of(ADOPTED)).rearm();

    assertEquals(1, rearmed);
  }

  @Test
  void anInactiveAgentUnitIsTheSweepsJobNotARearm() {
    createInProgressSpec("auth");
    runningSession("auth", 5678);
    var relaunches = new AtomicInteger();

    var rearmed =
        rearmer(
                project -> false,
                NO_UNIT,
                DEAD,
                project -> {
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

    var rearmed = rearmer(project -> true, NO_UNIT, DEAD, project -> Optional.of(LAUNCHED)).rearm();

    assertEquals(0, rearmed);
  }

  @Test
  void anEmptyRelaunchIsLoggedNotCounted() {
    createInProgressSpec("auth");
    runningSession("auth", 5678);

    var rearmed = rearmer(project -> true, NO_UNIT, DEAD, project -> Optional.empty()).rearm();

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
                project -> true,
                NO_UNIT,
                DEAD,
                project -> {
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
    var rearmer = rearmer(project -> true, NO_UNIT, DEAD, project -> Optional.of(LAUNCHED));
    db.close();
    db = null;

    assertEquals(0, assertDoesNotThrow(rearmer::rearm));
  }

  @Test
  void periodicRearmSchedulesRunsAndClosesWithoutStackingPasses() {
    createInProgressSpec("auth");
    runningSession("auth", 5678);
    try (var rearmer = rearmer(project -> true, NO_UNIT, DEAD, project -> Optional.of(LAUNCHED))) {
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
