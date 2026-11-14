/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.RunPlace;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.harness.RunUsage;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.ReviewRuns;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TestLogins;
import ai.singlr.sail.store.TestRuns;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MissedStopReconcilerTest {

  private static final Supplier<Instant> PAST_GRACE =
      () -> Instant.now().plus(Duration.ofMinutes(3));

  @TempDir Path tempDir;
  private Sqlite db;
  private SpecStore specStore;
  private ReviewStore reviewStore;
  private RunStore sessionStore;
  private EventStore eventStore;
  private EventBus bus;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("recon.db"));
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "test-project");
    specStore = new SpecStore(db);
    reviewStore = new ReviewStore(db);
    sessionStore = new RunStore(db);
    eventStore = new EventStore(db);
    bus = new EventBus();
  }

  @AfterEach
  void tearDown() {
    bus.close();
    if (db != null) db.close();
  }

  private static class CountingProbe implements MissedStopReconciler.UnitProbe {
    final AtomicInteger calls = new AtomicInteger();
    volatile boolean active;
    volatile String lastUnit;

    CountingProbe(boolean active) {
      this.active = active;
    }

    @Override
    public boolean active(RunPlace place, String runId, String unit) {
      calls.incrementAndGet();
      lastUnit = unit;
      return active;
    }
  }

  private MissedStopReconciler reconciler(
      MissedStopReconciler.UnitProbe probe, Supplier<Instant> clock) {
    return reconciler(probe, () -> "node-a", clock);
  }

  private MissedStopReconciler reconciler(
      MissedStopReconciler.UnitProbe probe, Supplier<String> localHandle, Supplier<Instant> clock) {
    return new MissedStopReconciler(
        specStore,
        sessionStore,
        eventStore,
        reviewStore,
        bus,
        probe,
        new WatcherCoverage(watched::contains, pid -> false),
        localHandle,
        clock,
        new FdeStore(db));
  }

  private void createInProgressSpec(String id) {
    Acting.as(
        null,
        () ->
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
                    null,
                    null,
                    null,
                    List.of(),
                    List.of())));
  }

  private void createPendingSpec(String id) {
    createSpec(id, SpecStatus.PENDING);
  }

  private void createSpec(String id, SpecStatus status) {
    Acting.as(
        null,
        () ->
            specStore.create(
                new SpecStore.SpecRow(
                    id,
                    "test-project",
                    "Test spec",
                    status,
                    null,
                    "claude-code",
                    null,
                    null,
                    "feat/test",
                    0,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of())));
  }

  @Test
  void releasesAStrandedReservationForAPendingSpecPastGrace() {
    createPendingSpec("auth");
    runningSession("auth");

    var released = reconciler(new CountingProbe(false), PAST_GRACE).sweep();

    assertEquals(1, released);
    assertEquals(
        "stopped",
        sessionStore.listForSpec("auth").getFirst().status(),
        "a run left running by a crash between reserve and claim is freed");
    assertEquals(
        SpecStatus.PENDING,
        specStore.findById("auth").orElseThrow().status(),
        "the never-claimed spec stays dispatchable");
  }

  @Test
  void releasesAStrandedReservationForADoneSpecPastGrace() {
    createSpec("auth", SpecStatus.DONE);
    runningSession("auth");

    var released = reconciler(new CountingProbe(false), PAST_GRACE).sweep();

    assertEquals(1, released);
    assertEquals(
        "stopped",
        sessionStore.listForSpec("auth").getFirst().status(),
        "a run left running for a finished spec is freed so it stops blocking the dispatch gate");
  }

  @Test
  void aCancelledSpecWithItsReleasedRunIsNeverReconciledOrReplayed() {
    createSpec("auth", SpecStatus.CANCELLED);
    finishedSession("auth", "stopped", null);

    var replayed = reconciler(new CountingProbe(false), PAST_GRACE).sweep();

    assertEquals(0, replayed);
    assertEquals(
        SpecStatus.CANCELLED,
        specStore.findById("auth").orElseThrow().status(),
        "an operator cancel is terminal; no sweep may drive the spec forward");
  }

  @Test
  void finalizesAnInterruptedStopOnceItsUnitIsGone() throws Exception {
    createSpec("auth", SpecStatus.CANCELLED);
    var runId = interruptedStopSession("auth");
    var latch = new CountDownLatch(1);
    var cancels = captureCancels(latch);

    var finalized = reconciler(new CountingProbe(false), PAST_GRACE).sweep();

    assertEquals(1, finalized);
    assertEquals("stopped", sessionStore.findById(runId).orElseThrow().status());
    BusTesting.awaitDelivery(latch);
    assertEquals(1, cancels.size());
    assertEquals(
        Event.WellKnownData.SOURCE_RECONCILE,
        cancels.peek().data().get(Event.WellKnownData.SOURCE));
    assertEquals(runId, cancels.peek().data().get(Event.WellKnownData.RUN_ID));
  }

  @Test
  void anInterruptedStopWithALiveUnitKeepsItsClaim() {
    createSpec("auth", SpecStatus.CANCELLED);
    var runId = interruptedStopSession("auth");

    var finalized = reconciler(new CountingProbe(true), PAST_GRACE).sweep();

    assertEquals(0, finalized);
    assertEquals(
        "stopping",
        sessionStore.findById(runId).orElseThrow().status(),
        "an active unit means a stop is mid-halt or a retry owns the kill; the claim stays");
  }

  @Test
  void aForeignInterruptedStopIsLeftToItsExecutingBox() {
    createSpec("auth", SpecStatus.CANCELLED);
    var runId = interruptedStopSession("auth");
    var probe = new CountingProbe(false);

    var finalized = reconciler(probe, () -> "node-b", PAST_GRACE).sweep();

    assertEquals(0, finalized);
    assertEquals(0, probe.calls.get());
    assertEquals("stopping", sessionStore.findById(runId).orElseThrow().status());
  }

  @Test
  void aProbeFailureOnAnInterruptedStopIsLoggedAndRetriedNextSweep() {
    createSpec("auth", SpecStatus.CANCELLED);
    var runId = interruptedStopSession("auth");
    MissedStopReconciler.UnitProbe failing =
        (project, id, unit) -> {
          throw new IOException("container unreachable");
        };

    var finalized = reconciler(failing, PAST_GRACE).sweep();

    assertEquals(0, finalized);
    assertEquals("stopping", sessionStore.findById(runId).orElseThrow().status());
  }

  @Test
  void aStrandedReservationWithALiveAgentIsNeverReleased() {
    createSpec("auth", SpecStatus.DONE);
    runningSession("auth");

    var released = reconciler(new CountingProbe(true), PAST_GRACE).sweep();

    assertEquals(0, released);
    assertEquals(
        "running",
        sessionStore.listForSpec("auth").getFirst().status(),
        "a reservation is never freed under an agent whose identity still probes live");
  }

  @Test
  void releasesADeadAdhocRunPastGrace() {
    var runId = adhocSession(77);

    var released = reconciler(new CountingProbe(false), PAST_GRACE).sweep();

    assertEquals(1, released);
    assertEquals("stopped", sessionStore.findById(runId).orElseThrow().status());
  }

  @Test
  void aLiveAdhocRunKeepsItsReservation() {
    var runId = adhocSession(77);

    var released = reconciler(new CountingProbe(true), PAST_GRACE).sweep();

    assertEquals(0, released);
    assertEquals("running", sessionStore.findById(runId).orElseThrow().status());
  }

  @Test
  void anAdhocRunWithNoRecordedPidIsStillProbedAndReleasedWhenDead() {
    var runId = adhocSession(null);
    var probe = new CountingProbe(false);

    var released = reconciler(probe, PAST_GRACE).sweep();

    assertEquals(1, released);
    assertEquals(1, probe.calls.get());
    assertEquals(
        "stopped",
        sessionStore.findById(runId).orElseThrow().status(),
        "a crashed launcher must not retain an empty-container reservation forever");
  }

  @Test
  void aLiveAdhocRunWithNoRecordedPidKeepsItsReservation() {
    var runId = adhocSession(null);

    var released = reconciler(new CountingProbe(true), PAST_GRACE).sweep();

    assertEquals(0, released);
    assertEquals("running", sessionStore.findById(runId).orElseThrow().status());
  }

  @Test
  void anUnprobeableAdhocRunIsLeftAlone() {
    var runId = adhocSession(77);
    MissedStopReconciler.UnitProbe failing =
        (project, id, unit) -> {
          throw new IOException("container unreachable");
        };

    var released = reconciler(failing, PAST_GRACE).sweep();

    assertEquals(0, released);
    assertEquals("running", sessionStore.findById(runId).orElseThrow().status());
  }

  @Test
  void aFreshAdhocRunInsideTheLaunchGraceIsNeverProbed() {
    var runId = adhocSession(77);
    var probe = new CountingProbe(false);

    var released = reconciler(probe, Instant::now).sweep();

    assertEquals(0, released);
    assertEquals(0, probe.calls.get());
    assertEquals("running", sessionStore.findById(runId).orElseThrow().status());
  }

  @Test
  void aReleaseThatLosesToTheWatcherCompletionNeverOverwritesTheExit() {
    var runId = adhocSession(77);
    MissedStopReconciler.UnitProbe completingProbe =
        (project, id, unit) -> {
          Acting.system(() -> sessionStore.finish(runId, "running", "completed", 0, null));
          return false;
        };

    var released = reconciler(completingProbe, PAST_GRACE).sweep();

    assertEquals(0, released);
    var run = sessionStore.findById(runId).orElseThrow();
    assertEquals("completed", run.status());
    assertEquals(0, run.exitCode());
  }

  @Test
  void anInterruptedAdhocStopIsFinalizedOnceItsUnitIsGone() throws Exception {
    var runId = adhocSession(77);
    Acting.system(() -> TestRuns.transition(sessionStore, runId, "running", "stopping"));
    var latch = new CountDownLatch(1);
    var cancels = captureCancels(latch);

    var finalized = reconciler(new CountingProbe(false), PAST_GRACE).sweep();

    assertEquals(1, finalized);
    assertEquals("stopped", sessionStore.findById(runId).orElseThrow().status());
    BusTesting.awaitDelivery(latch);
    assertEquals(1, cancels.size());
    assertNull(cancels.peek().spec());
    assertEquals(runId, cancels.peek().data().get(Event.WellKnownData.RUN_ID));
  }

  @Test
  void aBlankUnitStoppingClaimStillProbesThroughItsPidFile() {
    var runId = DateTimeUtils.newId().toString();
    Acting.system(
        () ->
            sessionStore.reserveDispatch(
                runId,
                "test-project",
                "",
                "node-a",
                "adhoc",
                List.of(),
                "claude-code",
                null,
                "task",
                "/home/dev/.sail/runs/" + runId + "/agent.log",
                ""));
    Acting.system(() -> TestRuns.transition(sessionStore, runId, "running", "stopping"));
    var probe = new CountingProbe(false);

    var finalized = reconciler(probe, PAST_GRACE).sweep();

    assertEquals(1, finalized);
    assertEquals("", probe.lastUnit);
    assertEquals("stopped", sessionStore.findById(runId).orElseThrow().status());
  }

  /**
   * HL4 and HL9 on the pass: every container this box ran a run in is told which of its runs are
   * live, so a launch file only a dead launch left goes; and the logins of a person the roster no
   * longer holds are deleted, while those of a person it holds stay.
   */
  @Test
  void eachPassRemovesLaunchFilesOfRunsNotLiveAndTheLoginsOfPeopleRemoved() {
    var live = session(null, "node-a", "adhoc");
    var ended = session(null, "node-a", "adhoc");
    Acting.system(() -> TestRuns.complete(sessionStore, ended, "failed", 1));
    session(null, "node-b", "adhoc");
    var told = new ArrayList<String>();
    var probe =
        new CountingProbe(true) {
          @Override
          public void removeLaunchFiles(String project, List<String> liveRuns) {
            told.add(project + " " + liveRuns);
          }
        };
    var fdes = new FdeStore(db);
    var kept = TestLogins.store(db, "ada");
    var claude = Harnesses.of("claude-code");
    fdes.logins().store("fde_gone000000000000000", claude, null, null, "gone-token");

    reconciler(probe, PAST_GRACE).sweep();

    assertEquals(List.of("test-project [" + live + "]"), told, "node-b's run is not this box's");
    assertEquals(List.of(), fdes.logins().list("fde_gone000000000000000"));
    assertEquals(2, fdes.logins().list(kept).size());
  }

  /** A container that cannot be asked fails nothing: the next pass tries again. */
  @Test
  void aContainerThatCannotBeToldIsLeftForTheNextPass() {
    var live = session(null, "node-a", "adhoc");
    var asked = new ArrayList<String>();
    var probe =
        new CountingProbe(true) {
          @Override
          public void removeLaunchFiles(String project, List<String> liveRuns) throws Exception {
            asked.add(project);
            if (asked.size() == 1) {
              throw new IOException("Error: Instance is not running");
            }
          }
        };
    var reconciler = reconciler(probe, Instant::now);

    reconciler.sweep();
    reconciler.removeLeftovers();

    assertEquals("running", sessionStore.findById(live).orElseThrow().status());
    assertEquals(List.of("test-project", "test-project"), asked);
  }

  @Test
  void aPassAsksOnlyTheContainersARunStartedOrFinishedInSinceTheLastOne() {
    var run = session(null, "node-a", "adhoc");
    var asked = new ArrayList<String>();
    var probe =
        new CountingProbe(true) {
          @Override
          public void removeLaunchFiles(String project, List<String> liveRuns) {
            asked.add(project + " " + liveRuns);
          }
        };
    var reconciler = reconciler(probe, Instant::now);

    reconciler.removeLeftovers();
    reconciler.removeLeftovers();
    Acting.system(() -> TestRuns.complete(sessionStore, run, "failed", 1));
    reconciler.removeLeftovers();
    reconciler.removeLeftovers();

    assertEquals(List.of("test-project [" + run + "]", "test-project []"), asked);
  }

  @Test
  void keepsARunningReservationForAnInProgressSpec() {
    createInProgressSpec("auth");
    runningSession("auth");

    var released = reconciler(new CountingProbe(true), PAST_GRACE).sweep();

    assertEquals(0, released);
    assertEquals(
        "running",
        sessionStore.listForSpec("auth").getFirst().status(),
        "a run whose spec is actively in progress is left to the in-progress sweep, not reaped");
  }

  private String session(String specId, String node, String role) {
    return Acting.system(
        () -> {
          var id = DateTimeUtils.newId().toString();
          return sessionStore.create(
              id,
              "test-project",
              specId,
              node,
              role,
              "claude-code",
              "feat/test",
              "task",
              1,
              null,
              "/home/dev/.sail/runs/" + id + "/agent.log",
              "sail-agent-" + id);
        });
  }

  /**
   * Review 1, major 3: a finished run whose report was not read (its container stopped as it ended,
   * or an operator stopped it) is read again on each sweep of the box that ran it until the read is
   * answered: an unanswered read changes nothing, a report is recorded once, and a report that is
   * gone is recorded none. A run another box ran is never read here.
   */
  @Test
  void aSweepReadsTheUsageOfItsFinishedUnreadRunsUntilTheContainerAnswers() {
    var stopped = runningSession("auth");
    var gone = runningSession("auth");
    var foreign = session("auth", "node-b", "build");
    Acting.system(
        () -> {
          for (var id : List.of(stopped, gone, foreign)) {
            assertTrue(sessionStore.claimStop(id, "stopped", () -> {}));
          }
        });
    var answers = new AtomicReference<RunUsage>(null);
    var asked = new CopyOnWriteArrayList<String>();
    MissedStopReconciler.UnitProbe probe =
        new MissedStopReconciler.UnitProbe() {
          @Override
          public boolean active(RunPlace place, String runId, String unit) {
            return false;
          }

          @Override
          public RunUsage usage(RunStore.RunRow run) {
            asked.add(run.id());
            return run.id().equals(gone) && answers.get() != null ? RunUsage.NONE : answers.get();
          }
        };
    var reconciler = reconciler(probe, Instant::now);

    reconciler.sweep();
    assertNull(sessionStore.findById(stopped).orElseThrow().usage(), "unanswered: still unread");
    answers.set(new RunUsage("m", 1L, 2L, 3L, 4L, 0.5, RunUsage.Source.RESULT));
    reconciler.sweep();
    assertEquals(
        0, (int) Acting.system(() -> reconciler.readUnreadUsage()), "each is recorded once");

    assertEquals(0.5, sessionStore.findById(stopped).orElseThrow().usage().costUsd());
    assertEquals(RunUsage.NONE, sessionStore.findById(gone).orElseThrow().usage());
    assertNull(sessionStore.findById(foreign).orElseThrow().usage());
    assertEquals(false, asked.contains(foreign), "another box's run is its own to read");
  }

  @Test
  void aFinishedRunWhoseReportHoldsNoUsageIsRecordedNoneOnceAndNotReadAgain() {
    var silent = runningSession("auth");
    Acting.system(() -> assertTrue(sessionStore.claimStop(silent, "stopped", () -> {})));
    var asked = new CopyOnWriteArrayList<String>();
    MissedStopReconciler.UnitProbe probe =
        new MissedStopReconciler.UnitProbe() {
          @Override
          public boolean active(RunPlace place, String runId, String unit) {
            return false;
          }

          @Override
          public RunUsage usage(RunStore.RunRow run) {
            asked.add(run.id());
            return RunUsage.NONE;
          }
        };
    var reconciler = reconciler(probe, Instant::now);

    assertEquals(1, (int) Acting.system(() -> reconciler.readUnreadUsage()));
    assertEquals(0, (int) Acting.system(() -> reconciler.readUnreadUsage()));

    assertEquals(RunUsage.NONE, sessionStore.findById(silent).orElseThrow().usage());
    assertEquals(List.of(silent), asked, "a run recorded none is not read again");
  }

  /**
   * Review 2, finding 1, through the box's own reader: a log holding no result line however long, a
   * last result cut mid-write and a last result holding a raw C1 control are each recorded in one
   * sweep and never asked about again; only a container that does not answer is asked again.
   */
  @Test
  void aReportItsContainerAnsweredIsRecordedInOneSweepAndOnlyASilentContainerIsAskedAgain() {
    var noResult = runningSession("auth");
    var cut = runningSession("auth");
    var control = runningSession("auth");
    var silent = runningSession("auth");
    Acting.system(
        () -> {
          for (var id : List.of(noResult, cut, control, silent)) {
            assertTrue(sessionStore.claimStop(id, "stopped", () -> {}));
          }
        });
    var result =
        "{\"type\":\"result\",\"result\":\"ok \u009b done\",\"total_cost_usd\":1.5,"
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}\n";
    var answers =
        Map.of(
            noResult, new ShellExec.Result(0, "", ""),
            cut, new ShellExec.Result(0, result.substring(0, 40) + "\n", ""),
            control, new ShellExec.Result(0, result, ""),
            silent, new ShellExec.Result(1, "", "Error: Instance is not running\n"));
    var asked = new CopyOnWriteArrayList<String>();
    var container =
        new ShellExec() {
          @Override
          public Result exec(List<String> command) {
            throw new AssertionError("every read is bounded");
          }

          @Override
          public Result exec(List<String> command, Path workDir, Duration timeout) {
            var run =
                answers.keySet().stream()
                    .filter(id -> command.stream().anyMatch(arg -> arg.contains(id)))
                    .findFirst()
                    .orElseThrow();
            asked.add(run);
            return answers.get(run);
          }

          @Override
          public boolean isDryRun() {
            return false;
          }
        };
    var reconciler = reconciler(MissedStopReconciler.systemdUnitProbe(container), Instant::now);

    assertEquals(3, (int) Acting.system(() -> reconciler.readUnreadUsage()));
    assertEquals(0, (int) Acting.system(() -> reconciler.readUnreadUsage()));

    assertEquals(RunUsage.NONE, sessionStore.findById(noResult).orElseThrow().usage());
    assertEquals(RunUsage.NONE, sessionStore.findById(cut).orElseThrow().usage());
    assertEquals(1.5, sessionStore.findById(control).orElseThrow().usage().costUsd());
    assertNull(sessionStore.findById(silent).orElseThrow().usage());
    assertEquals(List.of(noResult, cut, control, silent, silent), asked);
  }

  @Test
  void aReadThatFailsLeavesTheRunUnreadForTheNextSweepAndTheOthersAreStillRead() {
    var broken = runningSession("auth");
    var whole = runningSession("billing");
    Acting.system(() -> assertTrue(sessionStore.claimStop(broken, "stopped", () -> {})));
    Acting.system(() -> assertTrue(sessionStore.claimStop(whole, "stopped", () -> {})));
    var failing = new AtomicBoolean(true);
    MissedStopReconciler.UnitProbe probe =
        new MissedStopReconciler.UnitProbe() {
          @Override
          public boolean active(RunPlace place, String runId, String unit) {
            return false;
          }

          @Override
          public RunUsage usage(RunStore.RunRow run) {
            if (run.id().equals(broken) && failing.get()) {
              throw new IllegalStateException("the report could not be parsed");
            }
            return RunUsage.NONE;
          }
        };
    var reconciler = reconciler(probe, Instant::now);

    assertEquals(1, (int) Acting.system(() -> reconciler.readUnreadUsage()));
    assertNull(
        sessionStore.findById(broken).orElseThrow().usage(), "a failed read records nothing");
    assertEquals(RunUsage.NONE, sessionStore.findById(whole).orElseThrow().usage());

    failing.set(false);
    assertEquals(1, (int) Acting.system(() -> reconciler.readUnreadUsage()));
    assertEquals(RunUsage.NONE, sessionStore.findById(broken).orElseThrow().usage());
  }

  private static MissedStopReconciler.UnitProbe aliveOnly(String runId) {
    return (project, id, unit) -> id.equals(runId);
  }

  @Test
  void aDeadSupersededSessionOfASpecUnderWayIsFinishedWithoutReplayingItsSpecsStop() {
    createInProgressSpec("auth");
    var superseded = runningSession("auth");
    var newest = runningSession("auth");

    assertEquals(1, reconciler(aliveOnly(newest), PAST_GRACE).sweep());

    assertEquals("stopped", sessionStore.findById(superseded).orElseThrow().status());
    assertEquals("running", sessionStore.findById(newest).orElseThrow().status());
    assertEquals(0, bus.publishedCount(), "a spec stop is replayed only for its newest session");
  }

  @Test
  void deadRoomAndRoomFullRunsOfSpecsUnderWayAreFinished() {
    createInProgressSpec("auth");
    createSpec("docs", SpecStatus.REVIEW);
    var build = runningSession("auth");
    var room = session("auth", "node-a", DispatchGate.ROOM_ROLE);
    var full = session("docs", "node-a", DispatchGate.ROOM_FULL_ROLE);

    reconciler(aliveOnly(build), PAST_GRACE).sweep();

    assertEquals("stopped", sessionStore.findById(room).orElseThrow().status());
    assertEquals("stopped", sessionStore.findById(full).orElseThrow().status());
    assertEquals("running", sessionStore.findById(build).orElseThrow().status());
  }

  @Test
  void aDeadNewestBuildRunOfASpecInReviewIsFinished() {
    createSpec("auth", SpecStatus.REVIEW);
    var newest = runningSession("auth");

    assertEquals(1, reconciler(new CountingProbe(false), PAST_GRACE).sweep());

    assertEquals("stopped", sessionStore.findById(newest).orElseThrow().status());
    assertEquals(0, bus.publishedCount(), "finished, not replayed as a stop");
  }

  @Test
  void aLiveSupersededSessionIsLeftAloneAndAForeignOneIsNeverProbed() {
    createInProgressSpec("auth");
    var live = runningSession("auth");
    session("auth", "node-b", DispatchGate.ROOM_ROLE);
    runningSession("auth");
    var probe = new CountingProbe(true);

    reconciler(probe, PAST_GRACE).sweep();

    assertEquals("running", sessionStore.findById(live).orElseThrow().status());
    assertEquals(2, probe.calls.get(), "only this box's two sessions are probed");
  }

  @Test
  void aDeadRunStampedBeforeTheBoxHadAHandleIsFinishedOnceTheHandleChangeStampsIt() {
    var adhoc = session("", null, "adhoc");
    var probe = new CountingProbe(false);
    reconciler(probe, PAST_GRACE).sweep();
    assertEquals("running", sessionStore.findById(adhoc).orElseThrow().status());
    assertEquals(0, probe.calls.get(), "no box's run is probed");

    sessionStore.stamp("node-a", sessionStore.unacknowledged());

    assertEquals(1, reconciler(probe, PAST_GRACE).sweep());
    assertEquals("stopped", sessionStore.findById(adhoc).orElseThrow().status());
  }

  @Test
  void keepsAFreshReservationInsideTheLaunchGrace() {
    createPendingSpec("auth");
    runningSession("auth");

    var released = reconciler(new CountingProbe(true), Instant::now).sweep();

    assertEquals(0, released);
    assertEquals(
        "running",
        sessionStore.listForSpec("auth").getFirst().status(),
        "a run still inside the reserve-then-claim window is not disturbed");
  }

  private String finishedSession(String specId, String status, Integer exitCode) {
    return Acting.system(
        () -> {
          var id = runningSession(specId);
          TestRuns.complete(sessionStore, id, status, exitCode);
          return id;
        });
  }

  private String adhocSession(Integer pid) {
    return Acting.system(
        () -> {
          var id = DateTimeUtils.newId().toString();
          sessionStore.reserveDispatch(
              id,
              "test-project",
              "",
              "node-a",
              "adhoc",
              List.of(),
              "claude-code",
              null,
              "task",
              "/home/dev/.sail/runs/" + id + "/agent.log",
              "sail-agent-" + id);
          if (pid != null) {
            sessionStore.updateProcess(id, pid, null, null);
          }
          return id;
        });
  }

  private String runningSession(String specId) {
    return Acting.system(
        () -> {
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
              null,
              "/home/dev/.sail/runs/" + id + "/agent.log",
              "sail-agent-" + id);
        });
  }

  private void adoptedForeignRunningSession(String specId, Instant startedAt) {
    Acting.system(
        () ->
            sessionStore.applyRevision(
                DateTimeUtils.newId().toString(),
                Map.of(
                    "project", "test-project",
                    "spec_id", specId,
                    "node", "node-b",
                    "role", "build",
                    "agent", "claude-code",
                    "status", "running",
                    "started_at", Snapshots.millis(startedAt)),
                "1-foreign"));
  }

  private void recordEvent(String specId, String type, Instant timestamp) {
    Acting.system(
        () ->
            eventStore.insert(
                new EventStore.EventRow(
                    0,
                    timestamp,
                    type,
                    "test-project",
                    specId,
                    "claude-code",
                    HostInfo.hostname(),
                    "{}")));
  }

  private void recordStopEvent(String specId, Instant timestamp, Map<String, Object> data) {
    Acting.system(
        () ->
            eventStore.insert(
                new EventStore.EventRow(
                    0,
                    timestamp,
                    Event.WellKnownTypes.AGENT_SESSION_STOPPED,
                    "test-project",
                    specId,
                    "claude-code",
                    HostInfo.hostname(),
                    YamlUtil.dumpJson(data))));
  }

  private String interruptedStopSession(String specId) {
    return Acting.system(
        () -> {
          var id = runningSession(specId);
          TestRuns.transition(sessionStore, id, "running", "stopping");
          return id;
        });
  }

  private ConcurrentLinkedQueue<Event> captureCancels(CountDownLatch latch) {
    var captured = new ConcurrentLinkedQueue<Event>();
    bus.subscribe(
        BusTesting.latching(
            new EventSubscriber() {
              @Override
              public String name() {
                return "capture-cancels";
              }

              @Override
              public Predicate<Event> filter() {
                return e -> Event.WellKnownTypes.AGENT_CANCELLED.equals(e.type());
              }

              @Override
              public void onEvent(Event event) {
                captured.add(event);
              }
            },
            latch));
    return captured;
  }

  private ConcurrentLinkedQueue<Event> captureStops(CountDownLatch latch) {
    var captured = new ConcurrentLinkedQueue<Event>();
    bus.subscribe(
        BusTesting.latching(
            new EventSubscriber() {
              @Override
              public String name() {
                return "capture";
              }

              @Override
              public Predicate<Event> filter() {
                return e -> Event.WellKnownTypes.AGENT_SESSION_STOPPED.equals(e.type());
              }

              @Override
              public void onEvent(Event event) {
                captured.add(event);
              }
            },
            latch));
    return captured;
  }

  private void subscribeController(CountDownLatch latch) {
    var config =
        TestDefinitions.pipeline(
            Map.of(
                "max_iterations",
                3,
                "stages",
                List.of(
                    Map.of(
                        "name",
                        "security",
                        "type",
                        "agent",
                        "agent",
                        "codex",
                        "gate",
                        "no_critical"))));
    var controller =
        new ReviewPipelineController(
            specStore,
            reviewStore,
            sessionStore,
            p -> config,
            p -> "codex",
            new ReviewLanes() {
              @Override
              public Launch launch(Invocation invocation, String boxHandle, Runnable claimed) {
                reviewersLaunched.add(invocation);
                var reviewer = reviewerOf(invocation.reviewId(), invocation.specId());
                claimed.run();
                return new Launch.Started();
              }

              @Override
              public Launch resumeBuild(String project, String specId, String boxHandle) {
                throw new AssertionError("this test resumes no build");
              }

              @Override
              public String output(RunStore.RunRow run) {
                return "";
              }

              @Override
              public RunUsage usage(RunStore.RunRow run) {

                return RunUsage.NONE;
              }

              @Override
              public List<Rescue> ensureCommitted(
                  RunPlace place, List<String> repos, String branch, String commitMessage) {
                return List.of();
              }
            },
            bus,
            () -> {},
            () -> "node-a");
    bus.subscribe(BusTesting.latching(controller, latch));
  }

  private final List<ReviewLanes.Invocation> reviewersLaunched = new CopyOnWriteArrayList<>();
  private final Set<String> watched = ConcurrentHashMap.newKeySet();

  @Test
  void replaysACleanMissedStopAndStartsTheSpecsReview() throws Exception {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 0);
    var latch = new CountDownLatch(1);
    subscribeController(latch);

    var replayed = reconciler(new CountingProbe(false), Instant::now).sweep();

    assertEquals(1, replayed);
    BusTesting.awaitDelivery(latch);
    assertEquals(SpecStatus.REVIEW, specStore.findById("auth").orElseThrow().status());
    assertEquals(
        List.of("auth"),
        reviewersLaunched.stream().map(ReviewLanes.Invocation::specId).toList(),
        "the replayed stop reaches the pipeline, which launches the spec's reviewer");
  }

  @Test
  void replaysAMissedStopWithNoRecordedExitCodeAndStartsTheSpecsReview() throws Exception {
    createInProgressSpec("auth");
    finishedSession("auth", "completed", null);
    var latch = new CountDownLatch(1);
    subscribeController(latch);

    var replayed = reconciler(new CountingProbe(false), Instant::now).sweep();

    assertEquals(1, replayed);
    BusTesting.awaitDelivery(latch);
    assertEquals(SpecStatus.REVIEW, specStore.findById("auth").orElseThrow().status());
    assertEquals(
        List.of("auth"),
        reviewersLaunched.stream().map(ReviewLanes.Invocation::specId).toList(),
        "the replayed stop reaches the pipeline, which launches the spec's reviewer");
  }

  @Test
  void aStopWhoseEndingCannotBeReadIsStillPublishedAndItsRescueIsSpentOnlyThen() throws Exception {
    createReviewSpec("auth");
    var run = finishedSession("auth", "stopped", null);
    var probe =
        new MissedStopReconciler.UnitProbe() {
          @Override
          public boolean active(RunPlace place, String runId, String unit) {
            return false;
          }

          @Override
          public MissedStopReconciler.Ending ending(RunPlace place, String runId, String unit)
              throws IOException {
            throw new IOException("incus exec: connection reset");
          }
        };
    var latch = new CountDownLatch(1);
    var stops = captureStops(latch);
    var reconciler = reconciler(probe, Instant::now);

    assertEquals(1, reconciler.sweep(), "the stop is owed whatever can be read of how it ended");
    BusTesting.awaitDelivery(latch);

    var stop = stops.peek();
    assertEquals(run, stop.data().get(Event.WellKnownData.RUN_ID));
    assertNull(stop.data().get(Event.WellKnownData.REASON));
    assertNull(stop.data().get(Event.WellKnownData.EXIT_CODE));
    assertEquals(0, reconciler.sweep(), "published once: the rescue is spent");
  }

  @Test
  void aSweepInterruptedWhileReadingHowARunEndedKeepsItsInterrupt() {
    createReviewSpec("auth");
    finishedSession("auth", "stopped", null);
    var probe =
        new MissedStopReconciler.UnitProbe() {
          @Override
          public boolean active(RunPlace place, String runId, String unit) {
            return false;
          }

          @Override
          public MissedStopReconciler.Ending ending(RunPlace place, String runId, String unit)
              throws InterruptedException {
            throw new InterruptedException("shutting down");
          }
        };

    assertEquals(1, reconciler(probe, Instant::now).sweep());

    assertTrue(Thread.interrupted(), "the sweep's thread is still told it was interrupted");
  }

  @Test
  void aSpecStrandedInReviewWithNoReviewStartedGetsItsStopReplayed() {
    createReviewSpec("auth");
    finishedSession("auth", "stopped", 0);

    var replayed = reconciler(new CountingProbe(false), Instant::now).sweep();

    assertEquals(1, replayed, "a spec stranded in review with no review must be rescued");
  }

  @Test
  void aReviewSpecWhoseReviewPassedIsNotRescued() {
    createReviewSpec("auth");
    finishedSession("auth", "stopped", 0);
    Acting.system(
        () -> reviewStore.updateReviewStatus(reviewStore.createReview("auth", 1), "passed"));

    var replayed = reconciler(new CountingProbe(false), PAST_GRACE).sweep();

    assertEquals(0, replayed, "a review that ran to its verdict is not stranded");
  }

  @Test
  void theReviewStrandRescueRunsAtMostOncePerSpec() {
    createReviewSpec("auth");
    finishedSession("auth", "stopped", 0);
    var rec = reconciler(new CountingProbe(false), Instant::now);

    assertEquals(1, rec.sweep());
    assertEquals(0, rec.sweep(), "an empty-pipeline review must not be replayed forever");
  }

  @Test
  void aSpecWhoseLatestReviewErroredGetsItsStopReplayed() {
    createReviewSpec("auth");
    finishedSession("auth", "stopped", 0);
    recordEvent("auth", "review_stage_started", Instant.now());
    erroredReview("auth");

    assertEquals(
        1,
        reconciler(new CountingProbe(false), Instant::now).sweep(),
        "an errored review strands the spec in review forever unless its stop is replayed");
  }

  @Test
  void anErroredReviewEarnsExactlyOneReplay() {
    createReviewSpec("auth");
    finishedSession("auth", "stopped", 0);
    recordEvent("auth", "review_stage_started", Instant.now());
    erroredReview("auth");
    var rec = reconciler(new CountingProbe(false), Instant::now);

    assertEquals(1, rec.sweep());
    assertEquals(0, rec.sweep(), "one errored review, one replay; its retry decides what is next");
  }

  @Test
  void aFreshErroredRetryEarnsItsOwnReplay() {
    createReviewSpec("auth");
    finishedSession("auth", "stopped", 0);
    recordEvent("auth", "review_stage_started", Instant.now());
    erroredReview("auth");
    var rec = reconciler(new CountingProbe(false), Instant::now);

    assertEquals(1, rec.sweep());
    erroredReview("auth");

    assertEquals(
        1,
        rec.sweep(),
        "each errored attempt is rescued once; the pipeline's error budget bounds the loop");
  }

  @Test
  void anEscalatedReviewIsNeverReplayed() {
    createReviewSpec("auth");
    finishedSession("auth", "stopped", 0);
    recordEvent("auth", "review_stage_started", Instant.now());
    Acting.system(() -> reviewStore.updateReviewStatus(erroredReview("auth"), "escalated"));

    assertEquals(
        0,
        reconciler(new CountingProbe(false), Instant::now).sweep(),
        "escalation parks the spec for a human; the sweep must not resurrect it");
  }

  private String runningReview(String specId) {
    return Acting.system(
        () -> {
          var id = reviewStore.createReview(specId, 1);
          reviewStore.updateReviewStatus(id, "running");
          reviewStore.createStage(id, "security", "agent");
          return id;
        });
  }

  private String reviewerOf(String reviewId, String specId) {
    var id = DateTimeUtils.newId().toString();
    Acting.system(
        () ->
            ReviewRuns.reserve(
                sessionStore,
                id,
                reviewId,
                "test-project",
                specId,
                "node-a",
                Lane.REVIEW,
                "codex",
                List.of()));
    return id;
  }

  @Test
  void aRunningReviewALiveReviewerServesIsLeftAlone() {
    createReviewSpec("auth");
    finishedSession("auth", "stopped", 0);
    recordEvent("auth", "review_stage_started", Instant.now());
    erroredReview("auth");
    reviewerOf(runningReview("auth"), "auth");

    assertEquals(
        0,
        reconciler(new CountingProbe(true), Instant::now).sweep(),
        "the errored attempt already got its retry; its reviewer owns the spec now");
  }

  @Test
  void aRunningReviewNoRunServesHasItsNewestLoopStopReplayedOnce() throws Exception {
    createReviewSpec("auth");
    var build = finishedSession("auth", "stopped", 0);
    recordEvent("auth", "review_stage_started", Instant.now());
    var review = runningReview("auth");
    var latch = new CountDownLatch(1);
    var replayed = captureStops(latch);
    assertEquals(
        0,
        reconciler(new CountingProbe(false), Instant::now).sweep(),
        "a review only just written is about to get its reviewer: a sweep landing between its"
            + " rows and its first launch leaves it alone");
    var rec =
        reconciler(
            new CountingProbe(false),
            () -> Instant.now().plus(MissedStopReconciler.LAUNCH_GRACE).plusSeconds(1));

    assertEquals(
        1,
        rec.sweep(),
        "a review left running with nothing running it — the daemon died between writing its rows"
            + " and launching its reviewer — goes on when the newest loop stop is replayed");

    BusTesting.awaitDelivery(latch);
    assertEquals(build, replayed.peek().data().get(Event.WellKnownData.RUN_ID));
    assertEquals("running", reviewStore.findReview(review).orElseThrow().status());
    assertEquals(
        0,
        rec.sweep() + rec.sweep(),
        "a launch the gate refuses records the run it waits on, so a review still unserved with"
            + " no wait recorded was rescued, and is not rescued again: the rescue never loops");
  }

  @Test
  void aWaitingReviewIsRescuedOncePerRunItWaitedOnAndOnlyOnceThatRunHasEnded() {
    createReviewSpec("auth");
    finishedSession("auth", "stopped", 0);
    recordEvent("auth", "review_stage_started", Instant.now());
    var review = runningReview("auth");
    var holder = finishedSession("billing", "completed", 0);
    Acting.system(() -> reviewStore.waitOn(review, holder, () -> {}));

    assertEquals(
        0,
        reconciler(new CountingProbe(false), Instant::now).sweep(),
        "the run it waits on only just ended: that run's own stop may still be on its way");
    var rec = reconciler(new CountingProbe(false), PAST_GRACE);
    assertEquals(1, rec.sweep(), "the holder is long ended and nothing woke the review");
    assertEquals(0, rec.sweep() + rec.sweep(), "one rescue per run waited on, however it went");

    var gone = DateTimeUtils.newId().toString();
    Acting.system(() -> reviewStore.waitOn(review, gone, () -> {}));

    assertEquals(1, rec.sweep(), "a wait on a run this box holds no row of is a wait on nothing");
    assertEquals(0, rec.sweep(), "and it too is rescued once");
  }

  @Test
  void aReviewWhoseReviewerOnlyJustEndedIsNotRescuedWhileItsStopIsStillBeingActedOn() {
    createReviewSpec("auth");
    finishedSession("auth", "stopped", 0);
    recordEvent("auth", "review_stage_started", Instant.now());
    var reviewer = reviewerOf(runningReview("auth"), "auth");
    Acting.system(() -> TestRuns.complete(sessionStore, reviewer, "stopped", 0));

    assertEquals(0, reconciler(new CountingProbe(false), Instant::now).sweep());
    assertEquals(
        1,
        reconciler(
                new CountingProbe(false),
                () -> Instant.now().plus(MissedStopReconciler.LAUNCH_GRACE).plusSeconds(1))
            .sweep(),
        "past the grace window nothing is coming: the reviewer's own stop is replayed");
  }

  @Test
  void aReviewWaitingOnAPersonIsNeverRescued() {
    createReviewSpec("auth");
    finishedSession("auth", "stopped", 0);
    recordEvent("auth", "review_stage_started", Instant.now());
    Acting.system(
        () -> {
          var id = reviewStore.createReview("auth", 1);
          reviewStore.updateReviewStatus(id, "running");
          reviewStore.startStage(reviewStore.createStage(id, "sign-off", "human"), "human");
        });

    assertEquals(
        0,
        reconciler(new CountingProbe(false), Instant::now).sweep(),
        "a human stage is a person's to end, however long it takes");
  }

  private String erroredReview(String specId) {
    var reviewId = Acting.system(() -> reviewStore.createReview(specId, 1));
    Acting.system(
        () ->
            reviewStore.failReviewWithError(
                reviewId, "reviewer output unparseable: No JSON block found in agent output."));
    return reviewId;
  }

  @Test
  void aReviewSpecWithNoOwnedRunIsNotRescued() {
    createReviewSpec("auth");

    var replayed = reconciler(new CountingProbe(true), Instant::now).sweep();

    assertEquals(0, replayed, "no run to replay a stop from");
  }

  @Test
  void aSpecReconciledThisSweepIsNotAlsoRescuedInReview() {
    createReviewSpec("auth");
    finishedSession("auth", "stopped", 0);

    assertEquals(
        0,
        reconciler(new CountingProbe(false), Instant::now)
            .rescueStrandedReviews(new HashSet<>(Set.of("auth"))),
        "a spec whose stop was replayed earlier this sweep — which flips it into review on an async"
            + " subscriber thread — must not have its stop replayed a second time by the review pass");
    assertEquals(
        1,
        reconciler(new CountingProbe(false), Instant::now).rescueStrandedReviews(new HashSet<>()),
        "the same genuinely stranded review spec IS rescued when nothing handled it this sweep");
  }

  @Test
  void aRunReconciledThisSweepIsNotAlsoReleasedAsStranded() {
    createSpec("auth", SpecStatus.AWAITING_MERGE);
    runningSession("auth");
    var rec = reconciler(new CountingProbe(false), PAST_GRACE);

    assertEquals(
        0,
        Acting.system(() -> rec.finishDeadSessions(Set.of("auth"))),
        "a run whose stop was replayed earlier this sweep is not also released as a stranded"
            + " reservation, even once its spec has left in_progress");
    assertEquals(
        1,
        Acting.system(() -> rec.finishDeadSessions(Set.of())),
        "the same run IS released as stranded when nothing handled it this sweep");
  }

  private void createReviewSpec(String id) {
    Acting.as(
        null,
        () ->
            specStore.create(
                new SpecStore.SpecRow(
                    id,
                    "test-project",
                    "Test spec",
                    SpecStatus.REVIEW,
                    null,
                    "claude-code",
                    null,
                    null,
                    "feat/test",
                    0,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of())));
  }

  @Test
  void replaysACrashedMissedStopAndLeavesTheSpecInProgress() throws Exception {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 137);
    var latch = new CountDownLatch(1);
    subscribeController(latch);

    reconciler(new CountingProbe(false), Instant::now).sweep();

    BusTesting.awaitDelivery(latch);
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertTrue(reviewStore.reviewsForSpec("auth").isEmpty());
  }

  @Test
  void aStoreErrorIsSwallowedSoAPassNeverThrows() {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 0);
    var reconciler = reconciler(new CountingProbe(true), Instant::now);
    db.close();
    db = null;

    assertEquals(0, assertDoesNotThrow(reconciler::sweep));
  }

  @Test
  void aTerminalRowWithALiveUnitIsNeverReplayed() {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 0);
    var probe = new CountingProbe(true);

    var replayed = reconciler(probe, Instant::now).sweep();

    assertEquals(
        0,
        replayed,
        "a terminal run row is a claim, not an observation — an active unit means the agent is"
            + " still working and replaying its stop would review half-done work");
    assertEquals(1, probe.calls.get(), "the row must be checked against the live unit");
  }

  @Test
  void aVetoedReplayRetriesOnALaterSweepOnceTheUnitDies() {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 0);
    var probe = new CountingProbe(true);
    var rec = reconciler(probe, Instant::now);

    assertEquals(0, rec.sweep());
    probe.active = false;

    assertEquals(1, rec.sweep(), "the veto skips, it never burns the replay");
  }

  @Test
  void aLiveUnitAlsoVetoesTheStrandedReviewRescueWithoutBurningIt() {
    createReviewSpec("auth");
    finishedSession("auth", "stopped", 0);
    var probe = new CountingProbe(true);
    var rec = reconciler(probe, Instant::now);

    assertEquals(0, rec.sweep(), "the spec's agent is still alive; review must wait for it");
    probe.active = false;

    assertEquals(1, rec.sweep(), "once the unit dies the one rescue is still available");
  }

  @Test
  void specsWithoutSessionsNeverTouchSystemctl() {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 0);
    createInProgressSpec("billing");
    var probe = new CountingProbe(false);

    var replayed = reconciler(probe, Instant::now).sweep();

    assertEquals(1, replayed);
    assertEquals(1, probe.calls.get(), "only the terminal row is probed; billing has no session");
  }

  @Test
  void aForeignNodesRunIsNeverProbedOrReplayedHere() {
    createInProgressSpec("auth");
    runningSession("auth");
    var probe = new CountingProbe(false);

    var replayed = reconciler(probe, () -> "node-b", PAST_GRACE).sweep();

    assertEquals(0, replayed);
    assertEquals(0, probe.calls.get());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aForeignTerminalRunIsNotReplayedHereEither() {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 0);

    var replayed = reconciler(new CountingProbe(true), () -> "node-b", Instant::now).sweep();

    assertEquals(0, replayed);
  }

  @Test
  void aSupersededLocalRunNeverReplaysOverANewerForeignRun() {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 0);
    adoptedForeignRunningSession("auth", Instant.now().plus(Duration.ofHours(1)));
    var probe = new CountingProbe(true);

    var replayed = reconciler(probe, Instant::now).sweep();

    assertEquals(0, replayed);
    assertEquals(0, probe.calls.get());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aRunningSessionInsideTheLaunchGraceIsNeverProbed() {
    createInProgressSpec("auth");
    runningSession("auth");
    var probe = new CountingProbe(false);

    var replayed = reconciler(probe, Instant::now).sweep();

    assertEquals(0, replayed);
    assertEquals(0, probe.calls.get());
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aRunningSessionWithALiveUnitIsLeftAlone() {
    createInProgressSpec("auth");
    runningSession("auth");
    var probe = new CountingProbe(true);

    var replayed = reconciler(probe, PAST_GRACE).sweep();

    assertEquals(0, replayed);
    assertEquals(1, probe.calls.get());
  }

  @Test
  void aRunningForegroundSessionPastGraceIsNeverProbedOrStopped() {
    createInProgressSpec("auth");
    var runId = runningSession("auth");
    db.execute("UPDATE runs SET unit = '' WHERE id = ?", runId);
    var probe = new CountingProbe(false);

    var replayed = reconciler(probe, PAST_GRACE).sweep();

    assertEquals(0, replayed);
    assertEquals(0, probe.calls.get());
    assertEquals("running", sessionStore.findById(runId).orElseThrow().status());
  }

  @Test
  void synthesizesAStopWithoutExitCodeWhenTheUnitDiedUnobserved() throws Exception {
    createInProgressSpec("auth");
    runningSession("auth");
    var latch = new CountDownLatch(1);
    var captured = captureStops(latch);

    var replayed = reconciler(new CountingProbe(false), PAST_GRACE).sweep();

    assertEquals(1, replayed);
    BusTesting.awaitDelivery(latch);
    var stop = captured.poll();
    assertEquals("auth", stop.spec());
    assertNull(stop.data().get(Event.WellKnownData.EXIT_CODE));
    assertEquals(Event.WellKnownData.SOURCE_RECONCILE, stop.data().get(Event.WellKnownData.SOURCE));
  }

  @Test
  void aRecordedStopWhoseRunNeverFinishedHasItsRowFinishedAndTheStopReplayedOnlyAfter() {
    createInProgressSpec("auth");
    var sessionId = runningSession("auth");
    recordStopEvent(
        "auth",
        Instant.now().plusSeconds(1),
        Map.of(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_RECONCILE));
    var probe = new CountingProbe(false);
    var reconciler = reconciler(probe, PAST_GRACE);

    assertEquals(1, reconciler.sweep());
    assertEquals("stopped", sessionStore.findById(sessionId).orElseThrow().status());
    assertEquals(0, bus.publishedCount(), "a recorded stop is not replayed over a running row");

    assertEquals(1, reconciler.sweep(), "finished, its never-acted-on stop is rescued");
    assertEquals(2, probe.calls.get(), "each pass probes before it acts");
  }

  @Test
  void aReStampedRunWhoseStopTheTrackerDroppedIsFinishedAndFreesItsSpec() {
    createInProgressSpec("auth");
    var run = session("auth", "old-handle", "build");
    var stop =
        Map.<String, Object>of(
            Event.WellKnownData.SOURCE,
            Event.WellKnownData.SOURCE_WATCHER,
            Event.WellKnownData.RUN_ID,
            run,
            Event.WellKnownData.EXIT_CODE,
            1);
    new RunTracker(
            sessionStore, finished -> RunUsage.NONE, SyncScheduler.disabled(), () -> "node-a")
        .onEvent(
            Event.of(
                "test-project",
                "auth",
                Event.WellKnownTypes.AGENT_SESSION_STOPPED,
                "claude-code",
                "host",
                stop));
    assertEquals("running", sessionStore.findById(run).orElseThrow().status(), "stale: dropped");
    recordStopEvent("auth", Instant.now().plusSeconds(1), stop);
    recordEvent("auth", Event.WellKnownTypes.AGENT_FAILED, Instant.now().plusSeconds(2));
    Acting.system(() -> sessionStore.stamp("node-a", sessionStore.unacknowledged()));
    var reconciler = reconciler(new CountingProbe(false), PAST_GRACE);

    reconciler.sweep();
    reconciler.sweep();

    assertEquals("stopped", sessionStore.findById(run).orElseThrow().status());
    assertEquals(0, bus.publishedCount(), "its stop was acted on: never replayed");
    assertTrue(
        DispatchGate.decide(
                RunPlace.of("test-project", "auth"),
                "auth",
                "build",
                List.of(),
                sessionStore.runningOnNode("test-project", "node-a"))
            .isEmpty(),
        "the failed spec can be dispatched again");
  }

  private static ShellExec incus(String listed, String execError) {
    return new ShellExec() {
      @Override
      public Result exec(List<String> command) {
        return command.get(1).equals("list")
            ? new Result(0, listed, "")
            : new Result(1, "", execError);
      }

      @Override
      public Result exec(List<String> command, Path workDir, Duration timeout) {
        return exec(command);
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }

  @Test
  void aRunWhoseContainerWasStoppedIsFinishedAndItsStopClaimFinalized() {
    var adhoc = session(null, "node-a", "adhoc");
    var claim = session(null, "node-a", "adhoc");
    Acting.system(() -> TestRuns.transition(sessionStore, claim, "running", "stopping"));
    var stopped =
        incus(
            "[{\"name\": \"test-project\", \"status\": \"Stopped\"}]",
            "Error: Instance is not running");

    reconciler(MissedStopReconciler.systemdUnitProbe(stopped), PAST_GRACE).sweep();

    assertEquals("stopped", sessionStore.findById(adhoc).orElseThrow().status());
    assertEquals("stopped", sessionStore.findById(claim).orElseThrow().status());
  }

  @Test
  void aRunWhoseContainerWasDestroyedIsFinished() {
    var adhoc = session(null, "node-a", "adhoc");
    var destroyed = incus("[]", "Error: Instance not found");

    reconciler(MissedStopReconciler.systemdUnitProbe(destroyed), PAST_GRACE).sweep();

    assertEquals("stopped", sessionStore.findById(adhoc).orElseThrow().status());
  }

  @Test
  void aCommandLostWhileIncusdRestartsNeverReadsALiveAgentAsGone() {
    var adhoc = session(null, "node-a", "adhoc");
    var probes = new AtomicInteger();
    var restarting =
        new ShellExec() {
          @Override
          public Result exec(List<String> command) {
            if (command.get(1).equals("list")) {
              return new Result(0, "[{\"name\": \"test-project\", \"status\": \"Running\"}]", "");
            }
            if (command.contains("cat")) {
              return new Result(0, "4242\n", "");
            }
            if (command.contains("kill") && probes.getAndIncrement() == 0) {
              return new Result(1, "", "Error: websocket: close 1006 (abnormal closure)");
            }
            return new Result(0, command.contains("bash") ? "alive" : "", "");
          }

          @Override
          public Result exec(List<String> command, Path workDir, Duration timeout) {
            return exec(command);
          }

          @Override
          public boolean isDryRun() {
            return false;
          }
        };

    assertEquals(
        0, reconciler(MissedStopReconciler.systemdUnitProbe(restarting), PAST_GRACE).sweep());

    assertEquals("running", sessionStore.findById(adhoc).orElseThrow().status());
    assertEquals(2, probes.get(), "read again once the container said the agent is there");
  }

  @Test
  void aRunningContainerThatWillNotRunACommandReadsAsAlive() {
    var adhoc = session(null, "node-a", "adhoc");
    var flaky =
        incus(
            "[{\"name\": \"test-project\", \"status\": \"Running\"}]",
            "Error: websocket: close 1006 (abnormal closure)");

    assertEquals(0, reconciler(MissedStopReconciler.systemdUnitProbe(flaky), PAST_GRACE).sweep());

    assertEquals("running", sessionStore.findById(adhoc).orElseThrow().status());
  }

  @Test
  void aFinishedRowsReplayedStopKeepsTheExitCodeItsRecordedStopCarried() throws Exception {
    createInProgressSpec("auth");
    var build = runningSession("auth");
    recordStopEvent(
        "auth",
        Instant.now().plusSeconds(1),
        Map.of(
            Event.WellKnownData.SOURCE,
            Event.WellKnownData.SOURCE_WATCHER,
            Event.WellKnownData.RUN_ID,
            build,
            Event.WellKnownData.EXIT_CODE,
            1));
    var latch = new CountDownLatch(1);
    var replayed = captureStops(latch);
    var reconciler = reconciler(new CountingProbe(false), PAST_GRACE);

    reconciler.sweep();
    assertEquals(1, sessionStore.findById(build).orElseThrow().exitCode(), "finished with it");
    reconciler.sweep();

    BusTesting.awaitDelivery(latch);
    assertEquals(
        1,
        replayed.poll().data().get(Event.WellKnownData.EXIT_CODE),
        "the agent exited 1: its replayed stop never reads as a clean finish");
  }

  @Test
  void aReviewSpecsDeadBuildRunKeepsTheExitCodeItsRecordedStopCarried() throws Exception {
    createReviewSpec("auth");
    var build = runningSession("auth");
    recordStopEvent(
        "auth",
        Instant.now().plusSeconds(1),
        Map.of(
            Event.WellKnownData.SOURCE,
            Event.WellKnownData.SOURCE_WATCHER,
            Event.WellKnownData.RUN_ID,
            build,
            Event.WellKnownData.EXIT_CODE,
            1));
    var reconciler = reconciler(new CountingProbe(false), PAST_GRACE);

    reconciler.sweep();
    reconciler.sweep();

    assertEquals("stopped", sessionStore.findById(build).orElseThrow().status());
    assertEquals(
        1,
        sessionStore.findById(build).orElseThrow().exitCode(),
        "a failed agent's work is never rescued into review as a clean finish");
  }

  @Test
  void anotherRunsStopNeverSpeaksForTheBuildRunOrLendsItItsExitCode() throws Exception {
    createInProgressSpec("auth");
    var build = runningSession("auth");
    var room = session("auth", "node-a", DispatchGate.ROOM_ROLE);
    Acting.system(() -> sessionStore.finish(room, "running", "stopped", 1, null));
    recordStopEvent(
        "auth",
        Instant.now().plusSeconds(1),
        Map.of(
            Event.WellKnownData.SOURCE,
            Event.WellKnownData.SOURCE_WATCHER,
            Event.WellKnownData.RUN_ID,
            room,
            Event.WellKnownData.RUN_ROLE,
            DispatchGate.ROOM_ROLE,
            Event.WellKnownData.EXIT_CODE,
            1));
    var latch = new CountDownLatch(1);
    var replayed = captureStops(latch);

    assertEquals(1, reconciler(new CountingProbe(false), PAST_GRACE).sweep());

    BusTesting.awaitDelivery(latch);
    var stop = replayed.poll();
    assertEquals(build, stop.data().get(Event.WellKnownData.RUN_ID), "the build's own stop");
    assertNull(stop.data().get(Event.WellKnownData.EXIT_CODE), "never the room run's exit 1");
    assertNull(sessionStore.findById(build).orElseThrow().exitCode());
  }

  @Test
  void aFrozenContainersRunsReadAsAlive() {
    createInProgressSpec("auth");
    var build = runningSession("auth");
    var adhoc = session(null, "node-a", "adhoc");
    var claim = session(null, "node-a", "adhoc");
    Acting.system(() -> TestRuns.transition(sessionStore, claim, "running", "stopping"));
    var frozen =
        incus(
            "[{\"name\": \"test-project\", \"status\": \"Frozen\"},"
                + " {\"name\": \"test-project-auth\", \"status\": \"Frozen\"}]",
            "frozen");

    assertEquals(0, reconciler(MissedStopReconciler.systemdUnitProbe(frozen), PAST_GRACE).sweep());

    assertEquals("running", sessionStore.findById(build).orElseThrow().status());
    assertEquals("running", sessionStore.findById(adhoc).orElseThrow().status());
    assertEquals("stopping", sessionStore.findById(claim).orElseThrow().status());
    assertEquals(0, bus.publishedCount());
  }

  @Test
  void aContainerTheProbeCannotReachReadsAsAliveNotAsEveryRunInItGone() {
    createInProgressSpec("auth");
    var room = session("auth", "node-a", DispatchGate.ROOM_FULL_ROLE);
    var build = runningSession("auth");
    var outage =
        new ShellExec() {
          @Override
          public Result exec(List<String> command) {
            return new Result(1, "", "Error: websocket: close 1006 (abnormal closure)");
          }

          @Override
          public Result exec(List<String> command, Path workDir, Duration timeout) {
            return exec(command);
          }

          @Override
          public boolean isDryRun() {
            return false;
          }
        };

    assertEquals(0, reconciler(MissedStopReconciler.systemdUnitProbe(outage), PAST_GRACE).sweep());

    assertEquals("running", sessionStore.findById(room).orElseThrow().status());
    assertEquals("running", sessionStore.findById(build).orElseThrow().status());
    assertEquals(0, bus.publishedCount());
  }

  @Test
  void aWatcherObservedCrashIsNeverReplayedAgain() {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 137);
    recordStopEvent(
        "auth",
        Instant.now().plusSeconds(1),
        Map.of(
            Event.WellKnownData.EXIT_CODE,
            137,
            Event.WellKnownData.SOURCE,
            Event.WellKnownData.SOURCE_WATCHER));

    var replayed = reconciler(new CountingProbe(true), Instant::now).sweep();

    assertEquals(0, replayed);
  }

  @Test
  void aDroppedAuthoritativeStopIsRescuedOnceItsGraceExpires() {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 0);
    recordStopEvent(
        "auth",
        Instant.now().plusSeconds(1),
        Map.of(
            Event.WellKnownData.EXIT_CODE,
            0,
            Event.WellKnownData.SOURCE,
            Event.WellKnownData.SOURCE_WATCHER));

    var replayed = reconciler(new CountingProbe(false), PAST_GRACE).sweep();

    assertEquals(1, replayed);
  }

  @Test
  void aDroppedStopAlreadyActedOnIsNeverRescued() {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 0);
    recordStopEvent(
        "auth",
        Instant.now().plusSeconds(1),
        Map.of(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER));
    recordEvent("auth", "review_stage_started", Instant.now().plusSeconds(2));

    var replayed = reconciler(new CountingProbe(false), PAST_GRACE).sweep();

    assertEquals(0, replayed);
  }

  @Test
  void aDroppedFailureStopWithItsVerdictPublishedIsNeverRescued() {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 137);
    recordStopEvent(
        "auth",
        Instant.now().plusSeconds(1),
        Map.of(
            Event.WellKnownData.EXIT_CODE,
            137,
            Event.WellKnownData.SOURCE,
            Event.WellKnownData.SOURCE_WATCHER));
    recordEvent("auth", Event.WellKnownTypes.AGENT_FAILED, Instant.now().plusSeconds(2));

    var replayed = reconciler(new CountingProbe(false), PAST_GRACE).sweep();

    assertEquals(0, replayed);
  }

  @Test
  void evidenceFromBeforeTheSessionNeverCountsAsActedOn() {
    createInProgressSpec("auth");
    recordEvent("auth", "review_stage_started", Instant.now().minus(Duration.ofHours(2)));
    finishedSession("auth", "stopped", 0);
    recordStopEvent(
        "auth",
        Instant.now().plusSeconds(1),
        Map.of(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER));

    var replayed = reconciler(new CountingProbe(false), PAST_GRACE).sweep();

    assertEquals(1, replayed);
  }

  @Test
  void aRawTurnEndStopDoesNotBlockTheReplay() {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", null);
    recordStopEvent("auth", Instant.now().plusSeconds(1), Map.of());

    var replayed = reconciler(new CountingProbe(false), Instant::now).sweep();

    assertEquals(1, replayed);
  }

  @Test
  void anAuthoritativeStopFromASupersededSessionDoesNotBlockTheLatestOne() {
    createInProgressSpec("auth");
    recordStopEvent(
        "auth",
        Instant.now().minus(Duration.ofHours(2)),
        Map.of(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER));
    finishedSession("auth", "stopped", 0);

    var replayed = reconciler(new CountingProbe(false), Instant::now).sweep();

    assertEquals(1, replayed);
  }

  @Test
  void anUnreadableStopEventCountsAsCoveringSoTheSweepStaysConservative() {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 0);
    Acting.system(
        () ->
            eventStore.insert(
                new EventStore.EventRow(
                    0,
                    Instant.now().plusSeconds(1),
                    Event.WellKnownTypes.AGENT_SESSION_STOPPED,
                    "test-project",
                    "auth",
                    "claude-code",
                    HostInfo.hostname(),
                    "{\"source\": ")));

    var replayed = reconciler(new CountingProbe(true), Instant::now).sweep();

    assertEquals(0, replayed);
  }

  @Test
  void anAuthoritativeStopWithAMalformedTimestampCountsAsRecent() {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 0);
    recordStopEvent(
        "auth",
        Instant.EPOCH,
        Map.of(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER));

    var replayed = reconciler(new CountingProbe(true), Instant::now).sweep();

    assertEquals(0, replayed);
  }

  @Test
  void aFailingProbeIsLoggedAndDoesNotShadowOtherSpecs() {
    createInProgressSpec("broken");
    var brokenRun = runningSession("broken");
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 0);

    var replayed =
        reconciler(
                (project, runId, unit) -> {
                  if (unit.contains(brokenRun)) {
                    throw new IllegalStateException("container unreachable");
                  }
                  return false;
                },
                PAST_GRACE)
            .sweep();

    assertEquals(
        1,
        replayed,
        "the unreachable spec is deferred — never a forged stop — while the finished one replays");
  }

  @Test
  void aFailingProbeDefersTheRescueWithoutBurningIt() {
    createReviewSpec("auth");
    finishedSession("auth", "stopped", 0);
    var failing = new AtomicBoolean(true);
    var rec =
        reconciler(
            (project, runId, unit) -> {
              if (failing.get()) {
                throw new IllegalStateException("container unreachable");
              }
              return false;
            },
            Instant::now);

    assertEquals(0, assertDoesNotThrow(rec::sweep));
    failing.set(false);

    assertEquals(1, rec.sweep(), "the rescue is deferred, not burned");
  }

  @Test
  void theProbeIsAddressedAtTheRunsRecordedUnit() {
    createInProgressSpec("auth");
    var runId = runningSession("auth");
    var probedUnits = new ConcurrentLinkedQueue<String>();

    var replayed =
        reconciler(
                (project, id, unit) -> {
                  probedUnits.add(id + "|" + unit);
                  return true;
                },
                PAST_GRACE)
            .sweep();

    assertEquals(0, replayed);
    assertEquals(List.of(runId + "|sail-agent-" + runId), List.copyOf(probedUnits));
  }

  @Test
  void aPassNeverOverlapsAStillRunningOne() {
    createInProgressSpec("auth");
    runningSession("auth");
    var overlapped = new AtomicBoolean(true);
    var reconciler = new MissedStopReconciler[1];
    reconciler[0] =
        reconciler(
            (project, runId, unit) -> {
              overlapped.set(reconciler[0].sweepIfIdle());
              return true;
            },
            PAST_GRACE);

    assertTrue(reconciler[0].sweepIfIdle());
    assertFalse(overlapped.get());
  }

  @Test
  void theScheduledSweepFiresAndSurvivesFailingPasses() throws Exception {
    createInProgressSpec("auth");
    runningSession("auth");
    var latch = new CountDownLatch(2);
    try (var reconciler =
        reconciler(
            (project, runId, unit) -> {
              latch.countDown();
              throw new IllegalStateException("boom");
            },
            PAST_GRACE)) {
      reconciler.start(Duration.ofMillis(5));
      BusTesting.awaitDelivery(latch);
    }
  }

  @Test
  void startUsesTheDefaultCadence() {
    try (var reconciler = reconciler(new CountingProbe(true), Instant::now)) {
      reconciler.start();
    }
  }

  @Test
  void recordedIdentityProbeReadsRunScopedProcessLiveness() throws Exception {
    var runId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    var unit = "sail-agent-" + runId;
    assertTrue(
        MissedStopReconciler.systemdUnitProbe(runIdentityShell(true, true))
            .active(RunPlace.of("acme", null), runId, unit));
    assertFalse(
        MissedStopReconciler.systemdUnitProbe(runIdentityShell(true, false))
            .active(RunPlace.of("acme", null), runId, unit));
    assertFalse(
        MissedStopReconciler.systemdUnitProbe(runIdentityShell(false, false))
            .active(RunPlace.of("acme", null), runId, unit));
  }

  private static ShellExec runIdentityShell(boolean pidRecorded, boolean alive) {
    return new ShellExec() {
      @Override
      public Result exec(List<String> command) {
        var joined = String.join(" ", command);
        var asked = ScriptedShellExecutor.reachableContainer(joined);
        if (asked.isPresent()) {
          return asked.get();
        }
        if (joined.contains("agent.pid") && pidRecorded) {
          return new Result(0, "123", "");
        }
        if (joined.contains("kill -0 123") && alive) {
          return new Result(0, "", "");
        }
        return joined.startsWith("incus list")
            ? new Result(0, "[{\"name\": \"acme\", \"status\": \"Running\"}]", "")
            : new Result(1, "", "no such file");
      }

      @Override
      public Result exec(List<String> command, Path workDir, Duration timeout) {
        return exec(command);
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }

  private static RunStore.RunRow stoppedRun(String agent, String role) {
    return new RunStore.RunRow(
        "run-7",
        "test-project",
        "auth",
        "node-a",
        role,
        agent,
        null,
        "t",
        null,
        null,
        "stopped",
        null,
        null,
        "u",
        Instant.EPOCH,
        null,
        List.of(),
        null,
        null,
        null);
  }

  @Test
  void stopEventCarriesExitCodeAndReconcileSource() {
    var event = MissedStopReconciler.stopEvent(stoppedRun("codex", "fix"), 137, null);

    assertEquals(Event.WellKnownTypes.AGENT_SESSION_STOPPED, event.type());
    assertEquals("test-project", event.project());
    assertEquals("auth", event.spec());
    assertEquals("codex", event.agent());
    assertEquals(137, event.data().get("exit_code"));
    assertEquals("reconcile", event.data().get("source"));
    assertEquals("run-7", event.data().get("run_id"));
    assertEquals("fix", event.data().get("run_role"), "the pipeline routes the replay by lane");
  }

  @Test
  void stopEventOmitsExitCodeWhenUnknown() {
    var event = MissedStopReconciler.stopEvent(stoppedRun(null, "build"), null, null);

    assertEquals(Event.SAIL_AGENT, event.agent());
    assertNull(event.data().get("exit_code"));
    assertNull(event.data().get("reason"));
    assertEquals("reconcile", event.data().get("source"));
    assertEquals("run-7", event.data().get("run_id"));
  }

  private RunStore.Reservation.Reserved reservedAdhoc(String id) {
    return Acting.system(
        () -> {
          return (RunStore.Reservation.Reserved)
              sessionStore.reserveDispatch(
                  id,
                  "test-project",
                  "",
                  "node-a",
                  "adhoc",
                  List.of(),
                  "claude-code",
                  null,
                  "task",
                  "/home/dev/.sail/runs/" + id + "/agent.log",
                  "sail-agent-" + id);
        });
  }

  @Test
  void aStrandedReleaseRevokesTheRunCredential() {
    var id = DateTimeUtils.newId().toString();
    var reservation = reservedAdhoc(id);

    var released = reconciler(new CountingProbe(false), PAST_GRACE).sweep();

    assertEquals(1, released);
    assertEquals("stopped", sessionStore.findById(id).orElseThrow().status());
    assertTrue(
        sessionStore.findByCredential(reservation.credential()).isEmpty(),
        "releasing a stranded reservation revokes the run credential");
  }

  @Test
  void anInterruptedStopFinalizationRevokesTheRunCredential() {
    var id = DateTimeUtils.newId().toString();
    var reservation = reservedAdhoc(id);
    Acting.system(() -> TestRuns.transition(sessionStore, id, "running", "stopping"));

    var finalized = reconciler(new CountingProbe(false), PAST_GRACE).sweep();

    assertEquals(1, finalized);
    assertEquals("stopped", sessionStore.findById(id).orElseThrow().status());
    assertTrue(
        sessionStore.findByCredential(reservation.credential()).isEmpty(),
        "finalizing an interrupted stop revokes the run credential");
  }
}
