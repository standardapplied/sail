/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static ai.singlr.sail.api.ReviewScripts.CLEAN_REVIEW;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

  private static final class CountingProbe implements MissedStopReconciler.UnitProbe {
    final AtomicInteger calls = new AtomicInteger();
    volatile boolean active;
    volatile String lastUnit;

    CountingProbe(boolean active) {
      this.active = active;
    }

    @Override
    public boolean active(String project, String runId, String unit) {
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
        specStore, sessionStore, eventStore, reviewStore, bus, probe, localHandle, clock);
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

  private void createPendingSpec(String id) {
    createSpec(id, SpecStatus.PENDING);
  }

  private void createSpec(String id, SpecStatus status) {
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
            "",
            "",
            null,
            List.of(),
            List.of()));
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
          sessionStore.transition(runId, "running", "completed", 0);
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
    sessionStore.transition(runId, "running", "stopping");
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
    sessionStore.reserveDispatch(
        runId,
        "test-project",
        "",
        "node-a",
        "node-a",
        "adhoc",
        List.of(),
        "claude-code",
        null,
        "task",
        "/home/dev/.sail/runs/" + runId + "/agent.log",
        "");
    sessionStore.transition(runId, "running", "stopping");
    var probe = new CountingProbe(false);

    var finalized = reconciler(probe, PAST_GRACE).sweep();

    assertEquals(1, finalized);
    assertEquals("", probe.lastUnit);
    assertEquals("stopped", sessionStore.findById(runId).orElseThrow().status());
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
    var id = runningSession(specId);
    sessionStore.complete(id, status, exitCode);
    return id;
  }

  private String adhocSession(Integer pid) {
    var id = DateTimeUtils.newId().toString();
    sessionStore.reserveDispatch(
        id,
        "test-project",
        "",
        "node-a",
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
  }

  private String runningSession(String specId) {
    var id = DateTimeUtils.newId().toString();
    return sessionStore.create(
        id,
        "test-project",
        specId,
        "node-a",
        "node-a",
        "build",
        "claude-code",
        "feat/test",
        "task",
        1,
        null,
        "/home/dev/.sail/runs/" + id + "/agent.log",
        "sail-agent-" + id);
  }

  private void adoptedForeignRunningSession(String specId, Instant startedAt) {
    sessionStore.applyRevision(
        DateTimeUtils.newId().toString(),
        Map.of(
            "project", "test-project",
            "spec_id", specId,
            "node", "node-b",
            "role", "build",
            "agent", "claude-code",
            "status", "running",
            "started_at", startedAt.toString()),
        "1-foreign");
  }

  private void recordEvent(String specId, String type, String timestamp) {
    eventStore.insert(
        new EventStore.EventRow(
            0, timestamp, type, "test-project", specId, "claude-code", HostInfo.hostname(), "{}"));
  }

  private void recordStopEvent(String specId, String timestamp, Map<String, Object> data) {
    eventStore.insert(
        new EventStore.EventRow(
            0,
            timestamp,
            Event.WellKnownTypes.AGENT_SESSION_STOPPED,
            "test-project",
            specId,
            "claude-code",
            HostInfo.hostname(),
            YamlUtil.dumpJson(data)));
  }

  private String interruptedStopSession(String specId) {
    var id = runningSession(specId);
    sessionStore.transition(id, "running", "stopping");
    return id;
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
        ReviewPipelineConfig.fromMap(
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
            p -> config,
            p -> "codex",
            (p, a, pr, rid, cred) -> CLEAN_REVIEW,
            bus,
            () -> {},
            new DirectExecutorService());
    bus.subscribe(BusTesting.latching(controller, latch));
  }

  @Test
  void replaysACleanMissedStopAndDrivesTheSpecToAwaitingMerge() throws Exception {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 0);
    var latch = new CountDownLatch(1);
    subscribeController(latch);

    var replayed = reconciler(new CountingProbe(false), Instant::now).sweep();

    assertEquals(1, replayed);
    BusTesting.awaitDelivery(latch);
    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void replaysAMissedStopWithNoRecordedExitCode() throws Exception {
    createInProgressSpec("auth");
    finishedSession("auth", "completed", null);
    var latch = new CountDownLatch(1);
    subscribeController(latch);

    var replayed = reconciler(new CountingProbe(false), Instant::now).sweep();

    assertEquals(1, replayed);
    BusTesting.awaitDelivery(latch);
    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aSpecStrandedInReviewWithNoReviewStartedGetsItsStopReplayed() {
    createReviewSpec("auth");
    finishedSession("auth", "stopped", 0);

    var replayed = reconciler(new CountingProbe(false), Instant::now).sweep();

    assertEquals(1, replayed, "a spec stranded in review with no review must be rescued");
  }

  @Test
  void aReviewSpecWhoseReviewActuallyStartedIsNotRescued() {
    createReviewSpec("auth");
    finishedSession("auth", "stopped", 0);
    recordEvent("auth", "review_stage_started", Instant.now().toString());

    var replayed = reconciler(new CountingProbe(false), Instant::now).sweep();

    assertEquals(0, replayed, "a review that ran is not stranded");
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
    recordEvent("auth", "review_stage_started", Instant.now().toString());
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
    recordEvent("auth", "review_stage_started", Instant.now().toString());
    erroredReview("auth");
    var rec = reconciler(new CountingProbe(false), Instant::now);

    assertEquals(1, rec.sweep());
    assertEquals(0, rec.sweep(), "one errored review, one replay; its retry decides what is next");
  }

  @Test
  void aFreshErroredRetryEarnsItsOwnReplay() {
    createReviewSpec("auth");
    finishedSession("auth", "stopped", 0);
    recordEvent("auth", "review_stage_started", Instant.now().toString());
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
    recordEvent("auth", "review_stage_started", Instant.now().toString());
    reviewStore.updateReviewStatus(erroredReview("auth"), "escalated");

    assertEquals(
        0,
        reconciler(new CountingProbe(false), Instant::now).sweep(),
        "escalation parks the spec for a human; the sweep must not resurrect it");
  }

  @Test
  void aRunningRetryReviewIsLeftAlone() {
    createReviewSpec("auth");
    finishedSession("auth", "stopped", 0);
    recordEvent("auth", "review_stage_started", Instant.now().toString());
    erroredReview("auth");
    reviewStore.updateReviewStatus(reviewStore.createReview("auth", 1), "running");

    assertEquals(
        0,
        reconciler(new CountingProbe(false), Instant::now).sweep(),
        "the errored attempt already got its retry; a running review owns the spec now");
  }

  private String erroredReview(String specId) {
    var reviewId = reviewStore.createReview(specId, 1);
    reviewStore.failReviewWithError(
        reviewId, "reviewer output unparseable: No JSON block found in agent output.");
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
        rec.releaseStrandedReservations(Set.of("auth")),
        "a run whose stop was replayed earlier this sweep is not also released as a stranded"
            + " reservation, even once its spec has left in_progress");
    assertEquals(
        1,
        rec.releaseStrandedReservations(Set.of()),
        "the same run IS released as stranded when nothing handled it this sweep");
  }

  private void createReviewSpec(String id) {
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
            "",
            "",
            null,
            List.of(),
            List.of()));
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
  void aRecordedAuthoritativeStopMakesTheSweepANoOpForeverAfter() {
    createInProgressSpec("auth");
    var sessionId = runningSession("auth");
    recordStopEvent(
        "auth",
        Instant.now().plusSeconds(1).toString(),
        Map.of(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_RECONCILE));
    var probe = new CountingProbe(false);
    var reconciler = reconciler(probe, PAST_GRACE);

    assertEquals(0, reconciler.sweep());
    assertEquals(0, reconciler.sweep());
    assertEquals(0, probe.calls.get());
    assertEquals("running", sessionStore.findById(sessionId).orElseThrow().status());
  }

  @Test
  void aWatcherObservedCrashIsNeverReplayedAgain() {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 137);
    recordStopEvent(
        "auth",
        Instant.now().plusSeconds(1).toString(),
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
        Instant.now().plusSeconds(1).toString(),
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
        Instant.now().plusSeconds(1).toString(),
        Map.of(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER));
    recordEvent("auth", "review_stage_started", Instant.now().plusSeconds(2).toString());

    var replayed = reconciler(new CountingProbe(false), PAST_GRACE).sweep();

    assertEquals(0, replayed);
  }

  @Test
  void aDroppedFailureStopWithItsVerdictPublishedIsNeverRescued() {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 137);
    recordStopEvent(
        "auth",
        Instant.now().plusSeconds(1).toString(),
        Map.of(
            Event.WellKnownData.EXIT_CODE,
            137,
            Event.WellKnownData.SOURCE,
            Event.WellKnownData.SOURCE_WATCHER));
    recordEvent("auth", Event.WellKnownTypes.AGENT_FAILED, Instant.now().plusSeconds(2).toString());

    var replayed = reconciler(new CountingProbe(false), PAST_GRACE).sweep();

    assertEquals(0, replayed);
  }

  @Test
  void evidenceFromBeforeTheSessionNeverCountsAsActedOn() {
    createInProgressSpec("auth");
    recordEvent(
        "auth", "review_stage_started", Instant.now().minus(Duration.ofHours(2)).toString());
    finishedSession("auth", "stopped", 0);
    recordStopEvent(
        "auth",
        Instant.now().plusSeconds(1).toString(),
        Map.of(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER));

    var replayed = reconciler(new CountingProbe(false), PAST_GRACE).sweep();

    assertEquals(1, replayed);
  }

  @Test
  void aRawTurnEndStopDoesNotBlockTheReplay() {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", null);
    recordStopEvent("auth", Instant.now().plusSeconds(1).toString(), Map.of());

    var replayed = reconciler(new CountingProbe(false), Instant::now).sweep();

    assertEquals(1, replayed);
  }

  @Test
  void anAuthoritativeStopFromASupersededSessionDoesNotBlockTheLatestOne() {
    createInProgressSpec("auth");
    recordStopEvent(
        "auth",
        Instant.now().minus(Duration.ofHours(2)).toString(),
        Map.of(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER));
    finishedSession("auth", "stopped", 0);

    var replayed = reconciler(new CountingProbe(false), Instant::now).sweep();

    assertEquals(1, replayed);
  }

  @Test
  void anUnreadableStopEventCountsAsCoveringSoTheSweepStaysConservative() {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 0);
    eventStore.insert(
        new EventStore.EventRow(
            0,
            Instant.now().plusSeconds(1).toString(),
            Event.WellKnownTypes.AGENT_SESSION_STOPPED,
            "test-project",
            "auth",
            "claude-code",
            HostInfo.hostname(),
            "{\"source\": "));

    var replayed = reconciler(new CountingProbe(true), Instant::now).sweep();

    assertEquals(0, replayed);
  }

  @Test
  void anAuthoritativeStopWithAMalformedTimestampCountsAsRecent() {
    createInProgressSpec("auth");
    finishedSession("auth", "stopped", 0);
    recordStopEvent(
        "auth", "garbage", Map.of(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER));

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
            .active("acme", runId, unit));
    assertFalse(
        MissedStopReconciler.systemdUnitProbe(runIdentityShell(true, false))
            .active("acme", runId, unit));
    assertFalse(
        MissedStopReconciler.systemdUnitProbe(runIdentityShell(false, false))
            .active("acme", runId, unit));
  }

  private static ShellExec runIdentityShell(boolean pidRecorded, boolean alive) {
    return new ShellExec() {
      @Override
      public Result exec(List<String> command) {
        var joined = String.join(" ", command);
        if (joined.contains("agent.pid") && pidRecorded) {
          return new Result(0, "123", "");
        }
        if (joined.contains("kill -0 123") && alive) {
          return new Result(0, "", "");
        }
        return new Result(1, "", "no such file");
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
  void stopEventCarriesExitCodeAndReconcileSource() {
    var spec =
        new SpecStore.SpecRow(
            "auth",
            "test-project",
            "T",
            SpecStatus.IN_PROGRESS,
            null,
            "codex",
            null,
            null,
            null,
            0,
            null,
            null,
            null,
            null,
            List.of(),
            List.of());

    var event = MissedStopReconciler.stopEvent(spec, "run-7", 137);

    assertEquals(Event.WellKnownTypes.AGENT_SESSION_STOPPED, event.type());
    assertEquals("auth", event.spec());
    assertEquals("codex", event.agent());
    assertEquals(137, event.data().get("exit_code"));
    assertEquals("reconcile", event.data().get("source"));
    assertEquals("run-7", event.data().get("run_id"));
  }

  @Test
  void stopEventOmitsExitCodeWhenUnknown() {
    var spec =
        new SpecStore.SpecRow(
            "auth",
            "test-project",
            "T",
            SpecStatus.IN_PROGRESS,
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            null,
            null,
            null,
            List.of(),
            List.of());

    var event = MissedStopReconciler.stopEvent(spec, "run-7", null);

    assertEquals(Event.SAIL_AGENT, event.agent());
    assertNull(event.data().get("exit_code"));
    assertEquals("reconcile", event.data().get("source"));
    assertEquals("run-7", event.data().get("run_id"));
  }

  private RunStore.Reservation.Reserved reservedAdhoc(String id) {
    return (RunStore.Reservation.Reserved)
        sessionStore.reserveDispatch(
            id,
            "test-project",
            "",
            "node-a",
            "node-a",
            "adhoc",
            List.of(),
            "claude-code",
            null,
            "task",
            "/home/dev/.sail/runs/" + id + "/agent.log",
            "sail-agent-" + id);
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
    sessionStore.transition(id, "running", "stopping");

    var finalized = reconciler(new CountingProbe(false), PAST_GRACE).sweep();

    assertEquals(1, finalized);
    assertEquals("stopped", sessionStore.findById(id).orElseThrow().status());
    assertTrue(
        sessionStore.findByCredential(reservation.credential()).isEmpty(),
        "finalizing an interrupted stop revokes the run credential");
  }
}
