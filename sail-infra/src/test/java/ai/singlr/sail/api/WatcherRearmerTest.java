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
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
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
  private RunStore sessionStore;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("rearm.db"));
    new SchemaManager(db).migrate();
    sessionStore = new RunStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private String runningSession(String specId, Integer watcherPid) {
    return session(specId, "build", watcherPid, true);
  }

  private String adhocSession(Integer watcherPid) {
    return session("", "adhoc", watcherPid, true);
  }

  private String session(String specId, String role, Integer watcherPid, boolean withUnit) {
    var id = DateTimeUtils.newId().toString();
    return sessionStore.create(
        id,
        "test-project",
        specId,
        "node-a",
        role,
        "claude-code",
        "feat/test",
        "task",
        1,
        watcherPid,
        "/home/dev/.sail/runs/" + id + "/agent.log",
        withUnit ? "sail-agent-" + id : "");
  }

  private WatcherRearmer rearmer(
      MissedStopReconciler.UnitProbe agentUnitActive,
      Predicate<String> watcherUnitActive,
      LongPredicate watcherAlive,
      WatcherRearmer.WatcherRelauncher relauncher) {
    return new WatcherRearmer(
        sessionStore, agentUnitActive, watcherUnitActive, watcherAlive, () -> "node-a", relauncher);
  }

  @Test
  void theProbeCoverageCheckAndRelaunchAllAddressTheRunItself() {
    var runId = runningSession("auth", null);
    var probes = new ConcurrentLinkedQueue<String>();
    var covered = new ConcurrentLinkedQueue<String>();
    var relaunched = new ConcurrentLinkedQueue<String>();

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
    runningSession("auth", 5678);
    var relaunches = new AtomicInteger();
    var rearmer =
        new WatcherRearmer(
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
  void anUncoveredAdhocBackgroundSessionIsRearmedLikeABuildRun() {
    var runId = adhocSession(5678);
    var relaunched = new ConcurrentLinkedQueue<String>();

    var rearmed =
        rearmer(
                (project, id, unit) -> true,
                NO_UNIT,
                DEAD,
                run -> {
                  relaunched.add(run.id());
                  return Optional.of(LAUNCHED);
                })
            .rearm();

    assertEquals(1, rearmed);
    assertEquals(List.of(runId), List.copyOf(relaunched));
  }

  @Test
  void aSessionWhoseUnitIsNotActiveInSystemdIsNeverArmed() {
    runningSession("auth", null);
    adhocSession(null);
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

    assertEquals(0, rearmed, "a foreground or exited session runs as no unit a watcher could own");
    assertEquals(0, relaunches.get());
  }

  @Test
  void aLegacyRowWithNoRecordedUnitHasNothingToSupervise() {
    session("", "adhoc", null, false);
    var probes = new AtomicInteger();

    var rearmed =
        rearmer(
                (project, runId, unit) -> {
                  probes.incrementAndGet();
                  return true;
                },
                NO_UNIT,
                DEAD,
                run -> Optional.of(LAUNCHED))
            .rearm();

    assertEquals(0, rearmed);
    assertEquals(0, probes.get());
  }

  @Test
  void anActiveWatcherUnitCoversTheRunWithoutConsultingPidOrAgentUnit() {
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
  void aLiveRecordedWatcherPidCoversTheRun() {
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
    runningSession("auth", null);

    var rearmed =
        rearmer((project, runId, unit) -> true, NO_UNIT, DEAD, run -> Optional.of(ADOPTED)).rearm();

    assertEquals(1, rearmed);
  }

  @Test
  void terminalSessionsAreIgnored() {
    var completed = runningSession("finished", 5678);
    sessionStore.complete(completed, "stopped", 0);

    var rearmed =
        rearmer((project, runId, unit) -> true, NO_UNIT, DEAD, run -> Optional.of(LAUNCHED))
            .rearm();

    assertEquals(0, rearmed);
  }

  @Test
  void anEmptyRelaunchIsLoggedNotCounted() {
    runningSession("auth", 5678);

    var rearmed =
        rearmer((project, runId, unit) -> true, NO_UNIT, DEAD, run -> Optional.empty()).rearm();

    assertEquals(0, rearmed);
  }

  @Test
  void aFailingRelaunchIsLoggedAndDoesNotShadowOtherRuns() {
    runningSession("broken", 1111);
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
    runningSession("auth", 5678);
    var rearmer =
        rearmer((project, runId, unit) -> true, NO_UNIT, DEAD, run -> Optional.of(LAUNCHED));
    db.close();
    db = null;

    assertEquals(0, assertDoesNotThrow(rearmer::rearm));
  }

  @Test
  void periodicRearmSchedulesRunsAndClosesWithoutStackingPasses() {
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
