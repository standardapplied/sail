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
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SessionStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WatcherRearmerTest {

  private static final LongPredicate DEAD = pid -> false;
  private static final LongPredicate ALIVE = pid -> true;

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
      MissedStopReconciler.UnitProbe unitProbe,
      LongPredicate watcherAlive,
      WatcherRearmer.WatcherRelauncher relauncher) {
    return new WatcherRearmer(specStore, sessionStore, unitProbe, watcherAlive, relauncher);
  }

  @Test
  void rearmsADeadWatcherOverAStillActiveUnitAndRecordsTheNewPid() {
    createInProgressSpec("auth");
    var sessionId = runningSession("auth", 5678);
    var relaunches = new AtomicInteger();

    var rearmed =
        rearmer(
                project -> true,
                DEAD,
                project -> {
                  relaunches.incrementAndGet();
                  return OptionalLong.of(9012);
                })
            .rearm();

    assertEquals(1, rearmed);
    assertEquals(1, relaunches.get());
    assertEquals(9012, sessionStore.findById(sessionId).orElseThrow().watcherPid());
  }

  @Test
  void aLiveWatcherIsLeftAloneWithoutProbingTheUnit() {
    createInProgressSpec("auth");
    runningSession("auth", 5678);
    var probed = new AtomicInteger();

    var rearmed =
        rearmer(
                project -> {
                  probed.incrementAndGet();
                  return true;
                },
                ALIVE,
                project -> OptionalLong.of(9012))
            .rearm();

    assertEquals(0, rearmed);
    assertEquals(0, probed.get());
  }

  @Test
  void aSessionWithoutARecordedWatcherPidIsSkippedNotDoubled() {
    createInProgressSpec("auth");
    var sessionId = runningSession("auth", null);

    var rearmed = rearmer(project -> true, DEAD, project -> OptionalLong.of(9012)).rearm();

    assertEquals(0, rearmed);
    assertTrue(sessionStore.findById(sessionId).orElseThrow().watcherPid() == null);
  }

  @Test
  void anInactiveUnitIsTheSweepsJobNotARearm() {
    createInProgressSpec("auth");
    runningSession("auth", 5678);
    var relaunches = new AtomicInteger();

    var rearmed =
        rearmer(
                project -> false,
                DEAD,
                project -> {
                  relaunches.incrementAndGet();
                  return OptionalLong.of(9012);
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

    var rearmed = rearmer(project -> true, DEAD, project -> OptionalLong.of(9012)).rearm();

    assertEquals(0, rearmed);
  }

  @Test
  void aProjectWithoutGuardrailsHasNothingToRearm() {
    createInProgressSpec("auth");
    var sessionId = runningSession("auth", 5678);

    var rearmed = rearmer(project -> true, DEAD, project -> OptionalLong.empty()).rearm();

    assertEquals(0, rearmed);
    assertEquals(5678, sessionStore.findById(sessionId).orElseThrow().watcherPid());
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
                DEAD,
                project -> {
                  if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("relaunch failed");
                  }
                  return OptionalLong.of(9012);
                })
            .rearm();

    assertEquals(1, rearmed);
    assertEquals(2, attempts.get());
  }

  @Test
  void aStoreErrorIsSwallowedSoStartupIsNeverBlocked() {
    createInProgressSpec("auth");
    runningSession("auth", 5678);
    var rearmer = rearmer(project -> true, DEAD, project -> OptionalLong.of(9012));
    db.close();
    db = null;

    assertEquals(0, assertDoesNotThrow(rearmer::rearm));
  }

  @Test
  void livingProcessSeesThisJvmAliveAndANonexistentPidDead() {
    var alive = WatcherRearmer.livingProcess();

    assertTrue(alive.test(ProcessHandle.current().pid()));
    assertFalse(alive.test(999_999_999L));
  }
}
