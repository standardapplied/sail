/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static ai.singlr.sail.api.ReviewLoop.LATER;
import static ai.singlr.sail.api.ReviewLoop.PROJECT;
import static ai.singlr.sail.api.ReviewScripts.CLEAN_REVIEW;
import static ai.singlr.sail.api.ReviewScripts.CRITICAL_FINDING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the review loop does when things land out of order or not at all: a stop that is lost, heard
 * twice, or beaten by a sweep; a launch that is refused, fails late, or is stopped under way; an
 * operator's halt that half-works; a daemon that dies between two writes. Each test is one such
 * window, driven through {@link ReviewLoop}, and each ends with the loop where a person would
 * expect it: gone on exactly once, waiting on what it is owed, or escalated to whoever stopped it.
 */
class ReviewLoopRecoveryTest {

  private static final Actor ADMIN = new Actor(ReviewLoop.HANDLE, Role.ADMIN, Actor.Lane.API);

  @TempDir Path tempDir;
  private ReviewLoop loop;

  @AfterEach
  void tearDown() {
    loop.close();
  }

  /** A fix agent at work on {@code auth} after its first review failed the gate. */
  private RunStore.RunRow fixing() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    loop.finish(loop.onlyLive().id(), CRITICAL_FINDING);
    var fix = loop.onlyLive();
    assertEquals("fix", fix.role());
    return fix;
  }

  private void halt(String runId) {
    try {
      new AgentSession(loop.container).killAgent(PROJECT, AgentUnit.forRun(runId));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Event cancelOf(String runId) {
    return StopOperations.cancelEvent(
        loop.runs.findById(runId).orElseThrow(), Event.WellKnownData.SOURCE_OPERATOR, "uday");
  }

  /** A full chat turn of {@code auth}'s room takes the repo the loop's next run would claim. */
  private String chatTurnTakesTheRepo() {
    var chat = DateTimeUtils.newId().toString();
    var reserved =
        Acting.system(
            () ->
                loop.runs.reserveDispatch(
                    chat,
                    PROJECT,
                    "auth",
                    null,
                    ReviewLoop.HANDLE,
                    Lane.ROOM_FULL.wire(),
                    List.of("api"),
                    "claude-code",
                    "feat/test",
                    "chat",
                    AgentUnit.forRun(chat).logPath(),
                    AgentUnit.forRun(chat).unitName(),
                    null));
    assertInstanceOf(RunStore.Reservation.Reserved.class, reserved);
    loop.container.started(chat);
    return chat;
  }

  private void chatTurnEnds(String chat) {
    loop.container.exited(chat, "replied", 0);
    loop.onEvent(
        RunStops.of(
            Event.WellKnownData.SOURCE_WATCHER,
            PROJECT,
            "auth",
            "claude-code",
            chat,
            Lane.ROOM_FULL.wire(),
            0,
            null));
  }

  /** A spec whose build stopped and whose review row was written, with nothing launched for it. */
  private String strandedReviewOf(String specId) {
    loop.spec(specId, "api");
    var build = loop.run(specId, "build");
    Acting.system(
        () -> {
          loop.runs.complete(build, "stopped", 0);
          loop.specs.updateStatus(specId, SpecStatus.REVIEW);
          loop.reviews.createReview(specId, 1);
        });
    return build;
  }

  private long replaysOf(String runId) {
    return loop.events(Event.WellKnownTypes.AGENT_SESSION_STOPPED).stream()
        .filter(stop -> "reconcile".equals(stop.data().get(Event.WellKnownData.SOURCE)))
        .filter(stop -> runId.equals(stop.data().get(Event.WellKnownData.RUN_ID)))
        .count();
  }

  private int sweeps(MissedStopReconciler reconciler, int times) {
    var rescued = 0;
    for (var sweep = 0; sweep < times; sweep++) {
      rescued += reconciler.sweep();
      loop.settle();
    }
    return rescued;
  }

  private long verdictsInTheRoom() {
    return loop.roomLines("auth", "Review failed").size();
  }

  private List<String> waitsInTheRoom() {
    return loop.roomLines("auth", "Review is waiting");
  }

  private static String waitLine(String holder, String role, String specId) {
    return "Review is waiting for run `"
        + holder
        + "` (`"
        + role
        + "` of `"
        + specId
        + "`) to finish.";
  }

  /** A run in lane {@code role} of {@code specId}, at work over the whole container. */
  private String holderOf(String specId, String role) {
    var holder = loop.run(specId, role);
    loop.container.started(holder);
    return holder;
  }

  /** The run ended and its row was completed in place: no stop of it ever reaches the bus. */
  private void endsWithNoStop(String runId) {
    loop.container.exited(runId, "done", 0);
    Acting.system(() -> loop.runs.complete(runId, "completed", 0));
  }

  /** The room is down: every message written to it fails, as a database error would. */
  private void roomFails() {
    loop.db.execute(
        """
        CREATE TRIGGER room_down BEFORE INSERT ON room_messages
        BEGIN SELECT RAISE(ABORT, 'room is down'); END""");
  }

  private void roomRecovers() {
    loop.db.execute("DROP TRIGGER room_down");
  }

  @Test
  void aSweepNeverSpeaksForARunItsWatcherStillCovers() {
    var fix = fixing();
    loop.container.dirty("api", " M src/Half.java\n");
    loop.container.exited(fix.id(), "crashed", 1);

    assertEquals(
        0,
        loop.reconciler(LATER).sweep(),
        "the fix agent is gone and its watcher is alive, a liveness poll from saying how it"
            + " ended: a sweep that got in first would say less");
    assertEquals("running", loop.runs.findById(fix.id()).orElseThrow().status());

    loop.exit(fix.id(), "crashed", 1);

    assertEquals(List.of("fix agent failed: exit 1"), loop.details("review_iteration_failed"));
    assertTrue(loop.container.commits().isEmpty(), "a crashed fix agent's work is not committed");
    assertEquals("escalated", loop.statusOf(fix.reviewId()));
  }

  @Test
  void aSweepBetweenAWatchersKillAndItsStopLeavesTheStopToTheWatcher() {
    var fix = fixing();
    loop.container.dirty("api", " M src/Half.java\n");
    halt(fix.id());

    assertEquals(0, loop.reconciler(LATER).sweep(), "the watcher is mid-reap: its stop says why");

    loop.onEvent(loop.watcherStop(fix.id(), "time limit (45m)"));

    assertEquals(
        List.of("fix agent killed: time limit (45m)"), loop.details("review_iteration_failed"));
    assertTrue(loop.container.commits().isEmpty(), "nothing of a killed fix agent's is committed");
    assertTrue(loop.live().isEmpty());
  }

  @Test
  void aCrashedFixAgentNoWatcherCoversIsReconciledWithTheExitCodeItsUnitStillHolds() {
    var fix = fixing();
    loop.container.dirty("api", " M src/Half.java\n");
    loop.exitsUnheard(fix.id(), "crashed", 1);

    assertEquals(1, loop.reconciler(LATER).sweep());
    loop.settle();

    var stop = loop.events(Event.WellKnownTypes.AGENT_SESSION_STOPPED).getLast();
    assertEquals("reconcile", stop.data().get("source"));
    assertEquals(1, stop.data().get("exit_code"), "a failed unit still says how its agent ended");
    assertEquals(List.of("fix agent failed: exit 1"), loop.details("review_iteration_failed"));
    assertTrue(loop.container.commits().isEmpty());
  }

  @Test
  void aKillWhoseStopNeverReachedTheDaemonIsStillAKill() {
    var fix = fixing();
    loop.container.dirty("api", " M src/Half.java\n");

    loop.stallsUnheard(fix.id());
    loop.restart();

    assertFalse(loop.container.alive(fix.id()));
    assertEquals(1, loop.reconciler(LATER).sweep());
    loop.settle();

    assertEquals(
        List.of("fix agent killed: stall (20m)"),
        loop.details("review_iteration_failed"),
        "the watcher recorded why it ended the run beside the run, and the sweep read it there");
    assertTrue(
        loop.container.commits().isEmpty(),
        "a fix agent killed for its limit left half-done work, and it stays uncommitted");
    assertEquals("escalated", loop.statusOf(fix.reviewId()));
  }

  @Test
  void aKilledReviewersHalfWrittenVerdictNeverPassesItsReview() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    loop.container.wrote(
        reviewer.id(),
        """
        exec: cat Fixture.md
        ```json
        {"verdicts": [], "findings": []}
        ```
        exec: git diff main...feat/test
        (still reading the diff)
        """);

    loop.stallsUnheard(reviewer.id());
    loop.restart();
    assertEquals(1, loop.reconciler(LATER).sweep());
    loop.settle();

    assertEquals(List.of("reviewer killed: stall (20m)"), loop.details("review_errored"));
    assertEquals(SpecStatus.REVIEW, loop.specStatus("auth"), "killed mid-read is not a pass");
  }

  @Test
  void anOperatorsStopThatLandsWhileAReviewerIsLaunchingEscalatesItsReview() {
    loop = ReviewLoop.staged(tempDir, "codex");
    var stopped = new AtomicReference<String>();
    loop.onLaunch(
        runId -> {
          assertTrue(loop.runs.claimStop(runId, "stopping", () -> {}));
          halt(runId);
          assertTrue(loop.runs.transition(runId, "stopping", "stopped"));
          stopped.set(runId);
        });

    loop.built("auth");
    loop.onLaunch(runId -> {});
    var review = loop.reviewOf("auth");
    assertTrue(
        loop.reviews.findReview(review).orElseThrow().errored(),
        "the launch found its run stopped under it and failed");

    loop.onEvent(cancelOf(stopped.get()));

    assertEquals(
        "escalated",
        loop.statusOf(review),
        "a person stopped this reviewer: the review is theirs, not an error to retry");
    assertTrue(
        loop.details("review_escalated").getFirst().contains("reviewer stopped by an operator"));
    assertEquals(0, loop.reconciler(LATER).sweep(), "and no sweep retries over the stop");
    assertTrue(loop.live().isEmpty());
  }

  @Test
  void aStopClaimThatLandsWhileAStopIsBeingRoutedIsSeen() {
    var fix = fixing();
    loop.container.dirty("api", " M src/Half.java\n");
    loop.container.exited(fix.id(), "half done", 0);
    var stop = loop.watcherStop(fix.id(), null);
    var armed = new AtomicBoolean(true);
    var racing =
        new ReviewPipelineController(
                loop.specs,
                loop.reviews,
                loop.runs,
                project -> ReviewLoop.stages("codex"),
                project -> "codex",
                loop.operations.reviewLanes(),
                loop.bus,
                () -> {},
                () -> {
                  if (armed.compareAndSet(true, false)) {
                    assertTrue(loop.runs.claimStop(fix.id(), "stopping", () -> {}));
                  }
                  return ReviewLoop.HANDLE;
                })
            .useMessages(loop.messages);

    racing.onEvent(stop);
    loop.settle();

    assertFalse(armed.get(), "the claim landed between the router's read and its finish");
    assertTrue(
        loop.container.commits().isEmpty(),
        "the operator claimed the run before the router finished it: nothing is committed");
    assertEquals(1, loop.reviews.reviewsForSpec("auth").size(), "and no re-review starts");
  }

  @Test
  void aStopThatKilledTheAgentAndThenFailedIsStillTheOperators() {
    var fix = fixing();
    loop.container.dirty("api", " M src/Half.java\n");
    var stops =
        loop.stops(
            (project, unit) -> {
              new AgentSession(loop.container).killAgent(project, unit);
              throw new IOException("incus exec: websocket closed after the signal landed");
            });

    var refused =
        assertThrows(
            ApiException.class,
            () ->
                Actor.call(
                    ADMIN,
                    () ->
                        stops.stop(
                            new StopOperations.RunTarget(fix.id()), ReviewLoop.HANDLE, false)));

    assertEquals(ErrorCode.AGENT_STOP_FAILED, refused.failure().errorCode());
    var claimed = loop.runs.findById(fix.id()).orElseThrow();
    assertEquals(
        "stopping",
        claimed.status(),
        "the agent is gone: the run is not handed back as one that may end on its own");
    assertTrue(claimed.stoppedByOperator());

    loop.onEvent(loop.watcherStop(fix.id(), null));

    assertTrue(
        loop.container.commits().isEmpty(),
        "the unit died under the operator's signal: its exit is not a finished fix");
    assertEquals(1, loop.reviews.reviewsForSpec("auth").size());

    assertEquals(1, loop.reconciler(LATER).sweep(), "the interrupted stop is finalized");
    loop.settle();

    assertEquals("escalated", loop.statusOf(fix.reviewId()));
    assertTrue(
        loop.details("review_escalated").getFirst().contains("fix agent stopped by an operator"));
    assertTrue(loop.container.commits().isEmpty());
  }

  @Test
  void theWatchersStopOfABuildAnOperatorStoppedStartsNoReview() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.spec("auth", "api");
    var build = loop.run("auth", "build");
    Acting.system(() -> assertTrue(loop.runs.claimStop(build, "stopping", () -> {})));
    Acting.system(() -> assertTrue(loop.runs.transition(build, "stopping", "stopped")));

    loop.onEvent(ReviewLoop.buildStop("auth", build));

    assertTrue(
        loop.reviews.reviewsForSpec("auth").isEmpty(),
        "a person stopped this build and its spec was not theirs to cancel: the halted unit's"
            + " exit 0 is not a build to review");
    assertTrue(loop.live().isEmpty());
    assertEquals(SpecStatus.IN_PROGRESS, loop.specStatus("auth"), "the spec is its owner's");
    assertEquals(
        0, loop.reconciler(LATER).sweep(), "and no sweep replays the stop it was told to drop");
  }

  @Test
  void aReviewRescuedOnceThatThenErrorsIsStillRetried() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    loop.exitsUnheard(reviewer.id(), "not a verdict", 0);
    Acting.system(() -> loop.runs.complete(reviewer.id(), "stopped", 0));
    var reconciler = loop.reconciler(LATER);

    assertEquals(1, reconciler.sweep(), "the review whose reviewer's stop was lost is rescued");
    loop.settle();
    assertTrue(loop.reviews.findReview(reviewer.reviewId()).orElseThrow().errored());

    assertEquals(
        1,
        reconciler.sweep(),
        "the rescue left the same review errored: that is another thing it is owed, and it is"
            + " rescued for it too");
    loop.settle();

    var retry = loop.onlyLive();
    assertEquals("review", retry.role());
    assertEquals(1, loop.reviews.findReview(retry.reviewId()).orElseThrow().iteration());
    assertEquals(2, loop.reviews.reviewsForSpec("auth").size());
  }

  @Test
  void aReviewerWhoseRunWasFinishedAndWhoseStopWasNeverJudgedIsRescuedAndJudged() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    loop.exitsUnheard(reviewer.id(), CLEAN_REVIEW, 0);
    Acting.system(() -> loop.runs.complete(reviewer.id(), "stopped", 0));
    loop.restart();

    assertEquals(
        0, loop.reconciler(Instant::now).sweep(), "its stop may still be on its way: not yet");
    assertEquals(1, loop.reconciler(LATER).sweep());
    loop.settle();

    assertEquals(
        "passed",
        loop.statusOf(reviewer.reviewId()),
        "the reviewer's own stop is replayed and its verdict judged; no second reviewer runs");
    assertEquals(1, loop.runsIn("auth", "review").size());
  }

  @Test
  void aStopElsewhereNeverRelaunchesAReviewerWhoseOwnStopIsStillOnItsWay() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth", "api");
    var auth = loop.onlyLive();
    loop.built("billing", "web");
    var billing =
        loop.live().stream()
            .filter(run -> "billing".equals(run.specId()))
            .findFirst()
            .orElseThrow();
    loop.container.exited(billing.id(), CLEAN_REVIEW, 0);
    var billingsStop = loop.watcherStop(billing.id(), null);
    Acting.system(() -> loop.runs.complete(billing.id(), "stopped", 0));

    loop.finish(auth.id(), CLEAN_REVIEW);

    assertEquals(
        1,
        loop.runsIn("billing", "review").size(),
        "billing's reviewer ended and its stop is queued behind auth's: auth's stop frees claims,"
            + " it does not launch a second reviewer over a verdict about to be heard");
    assertEquals("running", loop.statusOf(billing.reviewId()));

    loop.onEvent(billingsStop);

    assertEquals("passed", loop.statusOf(billing.reviewId()));
    assertEquals(1, loop.runsIn("billing", "review").size());
  }

  @Test
  void aLaunchThatReportsFailureAfterItsAgentStartedWaitsForThatAgentsStop() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    loop.onLaunch(
        runId -> {
          throw new IllegalStateException("incus exec: connection reset");
        });

    loop.finish(reviewer.id(), CRITICAL_FINDING);

    loop.onLaunch(runId -> {});
    var fix = loop.onlyLive();
    assertEquals("fix", fix.role());
    assertEquals("running", fix.status(), "the agent started, so its run stands");
    assertTrue(
        loop.details("review_iteration_failed").isEmpty(),
        "a working agent is not a launch that failed: " + loop.details("review_iteration_failed"));
    assertTrue(loop.details("review_escalated").isEmpty());
    assertEquals(SpecStatus.IN_PROGRESS, loop.specStatus("auth"));
    assertEquals(1, loop.rearmer().rearm(), "the launch never got to its watcher: it is armed");
    loop.container.dirty("api", " M src/Fixed.java\n");

    loop.finish(fix.id(), "fixed and pushed");

    assertEquals(1, loop.container.commits().size(), "its work is rescued and judged");
    assertEquals("review", loop.onlyLive().role());
    assertEquals(2, loop.reviews.reviewsForSpec("auth").size());
  }

  @Test
  void aChatTurnThatTakesTheRepoBetweenAFixAndItsReReviewMakesTheReviewWaitNotFail() {
    var fix = fixing();
    loop.container.dirty("api", " M src/Fixed.java\n");
    var chat = new AtomicReference<String>();
    loop.container.beforeGit(
        () -> {
          if (chat.get() == null) {
            chat.set(chatTurnTakesTheRepo());
          }
        });

    loop.finish(fix.id(), "fixed");

    var reReview = loop.reviewOf("auth");
    assertEquals(2, loop.reviews.findReview(reReview).orElseThrow().iteration());
    assertEquals("running", loop.statusOf(reReview), "the re-review waits for the repo");
    assertEquals(List.of(chat.get()), loop.container.live());
    var reconciler = loop.reconciler(LATER);
    for (var sweep = 1; sweep <= ReviewPipelineController.MAX_ERRORED_RETRIES; sweep++) {
      reconciler.sweep();
      loop.settle();
    }
    assertTrue(
        loop.details("review_errored").isEmpty(),
        "a refused claim spends none of the reviewer's retries: " + loop.details("review_errored"));
    assertTrue(loop.details("review_escalated").isEmpty());

    chatTurnEnds(chat.get());

    var reviewer = loop.onlyLive();
    assertEquals("review", reviewer.role(), "the chat turn's stop freed the repo");
    assertEquals(reReview, reviewer.reviewId());
    assertEquals(2, loop.reviews.reviewsForSpec("auth").size());
  }

  @Test
  void aWaitingReviewIsRescuedOnceItsHolderEndsWithNoStopOnTheBus() {
    var fix = fixing();
    loop.container.dirty("api", " M src/Fixed.java\n");
    var chat = new AtomicReference<String>();
    loop.container.beforeGit(
        () -> {
          if (chat.get() == null) {
            chat.set(chatTurnTakesTheRepo());
          }
        });
    loop.finish(fix.id(), "fixed");
    var reReview = loop.reviewOf("auth");
    var reconciler = loop.reconciler(LATER);

    assertEquals(
        0,
        reconciler.sweep(),
        "while a run holds the claim a replay would only be refused again: none is published");
    assertEquals(List.of(chat.get()), loop.container.live());

    loop.container.exited(chat.get(), "replied", 0);
    assertEquals(1, reconciler.sweep(), "the dead chat turn is finished in place, with no stop");
    assertEquals("stopped", loop.runs.findById(chat.get()).orElseThrow().status());
    assertEquals(1, reconciler.sweep(), "and the review it held up is rescued at the next sweep");
    loop.settle();

    var reviewer = loop.onlyLive();
    assertEquals("review", reviewer.role());
    assertEquals(reReview, reviewer.reviewId());
    assertTrue(loop.details("review_errored").isEmpty());
    assertEquals(0, reconciler.sweep());
  }

  @Test
  void aStageRescuedOnceIsRescuedAgainAfterARunThatHeldItUpEndsWithNoStop() {
    loop = ReviewLoop.staged(tempDir, "codex", "claude-code");
    var build = strandedReviewOf("auth");
    var reconciler = loop.reconciler(LATER);
    assertEquals(1, reconciler.sweep(), "the review nothing serves is rescued, once");
    loop.settle();
    var first = loop.onlyLive();
    var chat = loop.run("auth", Lane.ROOM_FULL.wire());
    loop.container.started(chat);

    loop.finish(first.id(), CLEAN_REVIEW);

    assertEquals(List.of(chat), loop.container.live(), "the second stage's reviewer must wait");
    assertEquals(0, reconciler.sweep(), "held: no replay");
    loop.container.exited(chat, "replied", 0);
    assertEquals(1, reconciler.sweep(), "the dead chat turn is finished in place");

    assertEquals(
        1,
        reconciler.sweep(),
        "the review was rescued at its first stage, and is rescued again at its second: what"
            + " held it up is gone, and nothing told the pipeline");
    loop.settle();

    var second = loop.onlyLive();
    assertEquals("claude-code", second.agent());
    assertEquals(first.reviewId(), second.reviewId());
  }

  @Test
  void aStageHeldUpByARunThatEndedBeforeAnySweepSawItIsStillRescued() {
    loop = ReviewLoop.staged(tempDir, "codex", "claude-code");
    var build = strandedReviewOf("auth");
    var reconciler = loop.reconciler(LATER);
    assertEquals(1, reconciler.sweep(), "the first stage is rescued");
    loop.settle();
    var first = loop.onlyLive();
    var chat = loop.run("auth", Lane.ROOM_FULL.wire());
    loop.container.started(chat);
    loop.finish(first.id(), CLEAN_REVIEW);
    loop.container.exited(chat, "replied", 0);
    Acting.system(() -> loop.runs.complete(chat, "completed", 0));

    assertEquals(
        1,
        reconciler.sweep(),
        "the run that held the second stage up came and went between two sweeps, with no stop:"
            + " a stage is rescued in its own right, whatever its review was rescued for before");
    loop.settle();

    assertEquals("claude-code", loop.onlyLive().agent());
  }

  @Test
  void aReviewerRefusedItsClaimRecordsTheRunItWaitsOnAndIsLaunchedByThatRunsStop() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.spec("billing", "api");
    var holder = holderOf("billing", "build");

    var build = loop.built("auth");

    var review = loop.reviewOf("auth");
    assertEquals(List.of(holder), loop.container.live(), "auth's reviewer was refused the repo");
    assertEquals(holder, loop.waitingOn(review), "the wait names the run that holds the claim");
    assertEquals(List.of(waitLine(holder, "build", "billing")), waitsInTheRoom());
    assertFalse(
        loop.reviews.comparableSnapshot(review).containsKey("waiting_on"),
        "which run this box waits on is its own bookkeeping, never synced");
    var reconciler = loop.reconciler(LATER);
    assertEquals(0, sweeps(reconciler, 2), "the holder lives: nothing to rescue");

    loop.container.exited(holder, "crashed", 1);
    loop.onEvent(
        RunStops.of(
            Event.WellKnownData.SOURCE_WATCHER,
            PROJECT,
            "billing",
            "claude-code",
            holder,
            "build",
            1,
            null));

    var reviewer = loop.onlyLive();
    assertEquals("review", reviewer.role(), "the holder's stop launched the reviewer");
    assertEquals(review, reviewer.reviewId());
    assertNull(loop.waitingOn(review), "a review a run serves waits on no one");
    assertEquals(1, waitsInTheRoom().size(), "the room was told once");
    assertEquals(0, sweeps(reconciler, 2));
    assertEquals(0, replaysOf(build), "the reconciler published nothing: the stop did it all");
  }

  @Test
  void aWaitIsSaidOncePerRunWaitedOnHoweverManyStopsFindItStillHeld() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.spec("billing", "api");
    var holder = holderOf("billing", "build");
    loop.built("auth");
    var review = loop.reviewOf("auth");

    for (var stop = 1; stop <= 2; stop++) {
      loop.onEvent(
          MissedStopReconciler.stopEvent(loop.runsIn("auth", "build").getFirst(), 0, null));
    }

    assertEquals(holder, loop.waitingOn(review));
    assertEquals(
        List.of(waitLine(holder, "build", "billing")),
        waitsInTheRoom(),
        "refused twice by the same run: the wait is recorded once, and said once");
  }

  @Test
  void aWaitingReviewWhoseHolderEndsWithNoStopIsReplayedExactlyOnce() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.spec("billing", SpecStatus.PENDING, "claude-code", null, null, List.of("api"));
    var holder = holderOf("billing", "build");
    var build = loop.built("auth");
    var review = loop.reviewOf("auth");
    assertEquals(holder, loop.waitingOn(review));
    var reconciler = loop.reconciler(LATER);

    assertEquals(0, sweeps(reconciler, 2));
    assertEquals(0, replaysOf(build), "no replay while the run it waits on lives");

    loop.container.exited(holder, "done", 0);
    assertEquals(1, reconciler.sweep(), "the dead build is finished in place, with no stop");
    assertEquals("stopped", loop.runs.findById(holder).orElseThrow().status());
    assertEquals(1, sweeps(reconciler, 3), "and the review that waited on it is rescued, once");

    assertEquals(1, replaysOf(build));
    var reviewer = loop.onlyLive();
    assertEquals("review", reviewer.role());
    assertEquals(review, reviewer.reviewId());
    assertNull(loop.waitingOn(review));
  }

  @Test
  void aHolderThatOnlyJustEndedIsNotRescuedOverWhileItsOwnStopMayStillBeOnItsWay() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.spec("billing", SpecStatus.PENDING, "claude-code", null, null, List.of("api"));
    var holder = holderOf("billing", "build");
    var build = loop.built("auth");
    endsWithNoStop(holder);
    var ended = Instant.parse(loop.runs.findById(holder).orElseThrow().completedAt());
    var insideTheGrace = ended.plus(MissedStopReconciler.LAUNCH_GRACE).minusSeconds(1);

    assertEquals(
        0,
        sweeps(loop.reconciler(() -> insideTheGrace), 2),
        "the holder ended inside the grace window: its stop may be the very next event");
    assertEquals(0, replaysOf(build));
    assertEquals(1, sweeps(loop.reconciler(LATER), 2), "past it, nothing is coming");
  }

  @Test
  void aReviewRefusedThreeTimesRunningByRunsThatEndWithNoStopWaitsOnEachAndIsReplayedOncePerWait() {
    var holders = new CopyOnWriteArrayList<String>();
    var races = new AtomicInteger(3);
    loop =
        new ReviewLoop(
            tempDir,
            ReviewLoop.YAML,
            project -> {
              if (races.getAndDecrement() > 0) {
                holders.add(holderOf("auth", Lane.ROOM_FULL.wire()));
              }
              return ReviewLoop.stages("codex");
            },
            project -> "codex");
    var build = loop.built("auth");
    var review = loop.reviewOf("auth");
    var reconciler = loop.reconciler(LATER);

    for (var wait = 1; wait <= 3; wait++) {
      var holder = holders.getLast();
      assertEquals(wait, holders.size(), "a new run took the repo before each launch");
      assertEquals(holder, loop.waitingOn(review), "wait " + wait + " names its own holder");
      assertEquals(0, sweeps(reconciler, 2), "no replay while that holder lives");
      endsWithNoStop(holder);
      assertEquals(1, sweeps(reconciler, 3), "one replay for the wait on " + holder);
      assertEquals(wait, replaysOf(build));
    }

    assertEquals(
        holders.stream().map(holder -> waitLine(holder, "room-full", "auth")).toList(),
        waitsInTheRoom(),
        "three waits, each said once and each naming its own holder");
    var reviewer = loop.onlyLive();
    assertEquals("review", reviewer.role(), "the third replay found the claim free");
    assertEquals(review, reviewer.reviewId());
    assertEquals(0, sweeps(reconciler, 3), "and nothing is owed any more");
    assertEquals(3, replaysOf(build));
  }

  @Test
  void aReviewTheDaemonDiedBeforeLaunchingIsReplayedOnceAndARefusedReplayBecomesARecordedWait() {
    var holder = new AtomicReference<String>();
    var armed = new AtomicBoolean();
    loop =
        new ReviewLoop(
            tempDir,
            ReviewLoop.YAML,
            project -> {
              if (armed.compareAndSet(true, false)) {
                holder.set(holderOf("auth", Lane.ROOM_FULL.wire()));
              }
              return ReviewLoop.stages("codex");
            },
            project -> "codex");
    var build = strandedReviewOf("auth");
    var review = loop.reviewOf("auth");
    assertNull(loop.waitingOn(review));
    assertTrue(loop.reviews.stagesForReview(review).isEmpty());
    var reconciler = loop.reconciler(LATER);
    armed.set(true);

    assertEquals(1, sweeps(reconciler, 3), "a running review nothing serves is replayed, once");
    assertEquals(
        List.of(holder.get()),
        loop.container.live(),
        "a run took the repo before the pipeline's launch: the replay was refused");
    assertEquals(holder.get(), loop.waitingOn(review), "and the refusal is a recorded wait");
    assertEquals(1, replaysOf(build));

    endsWithNoStop(holder.get());

    assertEquals(
        1,
        sweeps(reconciler, 5),
        "the holder ended with no stop: the wait on it is rescued, once, and once launched the"
            + " review is owed no more");
    assertEquals(2, replaysOf(build));
    assertEquals("review", loop.onlyLive().role());
  }

  @Test
  void aReplayedStopOfABuildThatHadAlreadyStoppedMovesTheLoopAndIsNotSaidAgain() {
    loop = ReviewLoop.staged(tempDir, "codex");
    var said = loop.narrating();
    loop.spec("billing", SpecStatus.PENDING, "claude-code", null, null, List.of("api"));
    var holder = holderOf("billing", "build");
    var build = loop.built("auth");
    assertEquals(1, said.stopsInSlack(), "the build's stop was said when it stopped");
    assertEquals(1, said.stopsToWebhooks());
    assertEquals(List.of(holder), loop.container.live(), "its reviewer waits for the repo");
    endsWithNoStop(holder);

    assertEquals(1, sweeps(loop.reconciler(LATER), 2), "the wait is rescued by a replay");

    assertEquals(1, replaysOf(build));
    var replay =
        loop.events(Event.WellKnownTypes.AGENT_SESSION_STOPPED).stream()
            .filter(stop -> "reconcile".equals(stop.data().get(Event.WellKnownData.SOURCE)))
            .findFirst()
            .orElseThrow();
    assertEquals(true, replay.data().get(Event.WellKnownData.REPLAY));
    assertEquals(
        "review",
        loop.onlyLive().role(),
        "the pipeline routed the replay like any stop: the reviewer it held up is running");
    assertEquals(1, said.stopsInSlack(), "and nobody was told the build stopped a second time");
    assertEquals(1, said.stopsToWebhooks());
  }

  @Test
  void theOnlyStopOfARunFinishedInPlaceWithNoneIsSaidHoweverLongAgoItsRowEnded() {
    loop = ReviewLoop.staged(tempDir, "codex");
    var said = loop.narrating();
    loop.spec("auth", SpecStatus.REVIEW, "claude-code", null, null, List.of("api"));
    var build = loop.run("auth", "build");
    loop.container.started(build);
    loop.container.exited(build, "done", 0);

    sweeps(loop.reconciler(LATER), 3);

    assertEquals(
        List.of("review"),
        loop.live().stream().map(RunStore.RunRow::role).toList(),
        "the spec was moved to review under its build, whose row a sweep then finished with no"
            + " stop: the next sweep's stop is what starts its review");
    assertEquals(
        1,
        said.stopsInSlack(),
        "and it is the build's first and only stop, so it is news: a row that had ended is not a"
            + " stop that was said");
    assertEquals(1, said.stopsToWebhooks());
  }

  @Test
  void theFirstStopOfARunThatDiedUnwatchedIsSaidThoughTheReconcilerPublishesIt() {
    loop = ReviewLoop.staged(tempDir, "codex");
    var said = loop.narrating();
    loop.spec("auth", "api");
    var build = holderOf("auth", "build");
    loop.container.exited(build, "done", 0);

    assertEquals(1, sweeps(loop.reconciler(LATER), 1));

    var stop =
        loop.events(Event.WellKnownTypes.AGENT_SESSION_STOPPED).stream()
            .filter(event -> build.equals(event.data().get(Event.WellKnownData.RUN_ID)))
            .findFirst()
            .orElseThrow();
    assertNull(stop.data().get(Event.WellKnownData.REPLAY), "nobody said this stop before");
    assertEquals(1, said.stopsInSlack(), "so it is news: " + said.slack());
    assertEquals(1, said.stopsToWebhooks());
    assertEquals("review", loop.onlyLive().role(), "and the pipeline routed it");
  }

  @Test
  void aRefusedReviewersStageStaysPendingAndMainIsToldNoStageStarted() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.spec("billing", "api");
    holderOf("billing", "build");

    loop.built("auth");

    var review = loop.reviewOf("auth");
    assertEquals(
        List.of("pending"),
        loop.reviews.stagesForReview(review).stream().map(ReviewStore.StageRow::status).toList(),
        "a stage is running only while a run exists for it");
    assertTrue(loop.events("review_stage_started").isEmpty());
    assertEquals(
        List.of(),
        loop.narratedOnMain(review).stream().map(Event::type).toList(),
        "main, reading the synced rows, announces no stage that has no reviewer");
  }

  @Test
  void aLaunchedReviewersStageIsRunningAndItsRunRecordedBeforeItsUnitStarts() {
    loop = ReviewLoop.staged(tempDir, "codex");
    var atUnitStart = new CopyOnWriteArrayList<String>();
    loop.onLaunch(
        runId -> {
          var run = loop.runs.findById(runId).orElseThrow();
          atUnitStart.add(run.role() + " " + run.status());
          loop.reviews.stagesForReview(run.reviewId()).stream()
              .map(stage -> "stage " + stage.status() + " by " + stage.reviewer())
              .forEach(atUnitStart::add);
        });

    loop.built("auth");

    assertEquals(List.of("review running", "stage running by codex"), atUnitStart);
    assertEquals(
        List.of("review_stage_started"),
        loop.narratedOnMain(loop.reviewOf("auth")).stream().map(Event::type).toList());
  }

  @Test
  void aFailedGateWhoseFindingsAPersonResolvedBeforeTheFixLaunchedIsReviewedAgainNotFixed() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    loop.container.exited(reviewer.id(), CRITICAL_FINDING, 0);
    Acting.system(() -> loop.runs.complete(reviewer.id(), "stopped", 0));
    var chat = chatTurnTakesTheRepo();
    loop.onEvent(loop.watcherStop(reviewer.id(), null));
    assertEquals("failed", loop.statusOf(reviewer.reviewId()));
    assertEquals(chat, loop.waitingOn(reviewer.reviewId()), "the fix agent waits for the repo");
    Acting.by(
        ADMIN,
        () ->
            loop.reviews
                .openFindingsForReview(reviewer.reviewId())
                .forEach(
                    finding ->
                        loop.reviews.resolveFinding(
                            finding.id(), Finding.Resolution.DISMISSED, "not a defect")));

    chatTurnEnds(chat);

    var next = loop.onlyLive();
    assertEquals("review", next.role(), "nothing is left to fix: the branch is judged again");
    assertEquals(2, loop.reviews.findReview(next.reviewId()).orElseThrow().iteration());
    assertEquals(loop.reviewOf("auth"), next.reviewId());
    assertTrue(loop.runsIn("auth", "fix").isEmpty(), "no fix agent ran over nothing");
    assertTrue(loop.details("review_escalated").isEmpty());
    assertEquals(SpecStatus.REVIEW, loop.specStatus("auth"));
  }

  @Test
  void anEscalationWhoseReasonOutgrowsARoomMessageStillLandsWithItsReasonKeptWhole() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    loop.finish(loop.onlyLive().id(), CRITICAL_FINDING);
    var fix = loop.onlyLive();
    loop.container.dirty("api", " M src/Fixed.java\n");
    loop.container.gitFails("pre-commit hook failed:\n" + "E".repeat(70_000), "commit");

    loop.finish(fix.id(), "fixed");

    var review = loop.reviews.findReview(fix.reviewId()).orElseThrow();
    assertEquals(
        "escalated",
        review.status(),
        "a room line too long to write never undoes the escalation it is written with");
    assertTrue(review.error().length() > 70_000, "the reason is kept whole on the review");
    var line = loop.roomLines("auth", "Review escalated").getFirst();
    assertTrue(line.endsWith("[cut: longer than a room message can be]"), "and the room is told");
    assertEquals(SpecStatus.REVIEW, loop.specStatus("auth"));
  }

  @Test
  void aPassWhoseVerdictOutgrowsARoomMessageStillLands() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    loop.finish(loop.onlyLive().id(), CRITICAL_FINDING);
    var fix = loop.onlyLive();
    loop.finish(fix.id(), "fixed");
    var reviewer = loop.onlyLive();
    Acting.by(
        ADMIN,
        () ->
            loop.reviews
                .openFindingsForReview(fix.reviewId())
                .forEach(
                    finding ->
                        loop.reviews.resolveFinding(
                            finding.id(),
                            Finding.Resolution.DISPUTED,
                            "the reviewer's argument ".repeat(3_000))));

    loop.finish(reviewer.id(), CLEAN_REVIEW);

    assertEquals("passed", loop.statusOf(reviewer.reviewId()));
    assertEquals(SpecStatus.AWAITING_MERGE, loop.specStatus("auth"));
    assertEquals(1, loop.roomLines("auth", "Review passed").size());
  }

  @Test
  void aStageThePipelineNoLongerHasWhenItsReviewerStopsEscalatesItsReviewSayingSo() {
    var pipeline = new AtomicReference<>(ReviewLoop.stages("codex"));
    loop = new ReviewLoop(tempDir, ReviewLoop.YAML, project -> pipeline.get(), project -> "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    pipeline.set(ReviewLoop.stages("claude-code"));

    loop.finish(reviewer.id(), CLEAN_REVIEW);

    var review = loop.reviews.findReview(reviewer.reviewId()).orElseThrow();
    assertEquals(
        "escalated",
        review.status(),
        "nothing can judge a stage the pipeline was changed away from: the review is a person's,"
            + " never left running with nothing owed");
    assertTrue(review.error().contains("stage 'codex'"), review.error());
    assertEquals(
        List.of("failed"),
        loop.reviews.stagesForReview(review.id()).stream()
            .map(ReviewStore.StageRow::status)
            .toList(),
        "and no stage of an escalated review is left running");
    assertEquals(0, sweeps(loop.reconciler(LATER), 3), "nothing is owed for it any more");
  }

  @Test
  void aSecondStageThePipelineReplacedEscalatesItsReviewNamingTheStageItHolds() {
    var pipeline = new AtomicReference<>(ReviewLoop.stages("codex", "claude-code"));
    loop = new ReviewLoop(tempDir, ReviewLoop.YAML, project -> pipeline.get(), project -> "codex");
    loop.built("auth");
    loop.finish(loop.onlyLive().id(), CLEAN_REVIEW);
    var second = loop.onlyLive();
    pipeline.set(ReviewLoop.stages("codex", "gemini"));

    loop.finish(second.id(), CLEAN_REVIEW);

    assertEquals("escalated", loop.statusOf(second.reviewId()));
    assertTrue(
        loop.reviews.findReview(second.reviewId()).orElseThrow().error().contains("claude-code"),
        "the stage the review holds in second place is not the pipeline's second stage any more");
  }

  @Test
  void aRunningReviewWhosePipelineLostItsStagesClosesTheStageItsReviewerRan() {
    var pipeline = new AtomicReference<>(ReviewLoop.stages("codex"));
    loop = new ReviewLoop(tempDir, ReviewLoop.YAML, project -> pipeline.get(), project -> "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    pipeline.set(ReviewLoop.stages());

    loop.finish(reviewer.id(), CLEAN_REVIEW);

    assertEquals("escalated", loop.statusOf(reviewer.reviewId()));
    assertEquals(
        List.of("failed"),
        loop.reviews.stagesForReview(reviewer.reviewId()).stream()
            .map(ReviewStore.StageRow::status)
            .toList(),
        "a stage is running only while a run exists for it or a person owns it");
  }

  @Test
  void aDescriptorThatCannotBeReadIsNeverTakenForAPipelineThatChanged() {
    var custom =
        ReviewLoop.YAML
            + "  review_pipeline:\n"
            + "    stages:\n"
            + "      - name: security\n"
            + "        type: agent\n"
            + "        agent: codex\n"
            + "        gate: no_critical\n";
    loop = ReviewLoop.wired(tempDir, custom);
    loop.built("auth");
    var reviewer = loop.onlyLive();
    loop.describe("agent: [unterminated");

    loop.finish(reviewer.id(), CLEAN_REVIEW);

    var review = loop.reviews.findReview(reviewer.reviewId()).orElseThrow();
    assertEquals("escalated", review.status());
    assertTrue(
        review.error().contains("could not be read"),
        "the review is handed to a person for what is true — its project's descriptor cannot be"
            + " read — and never judged against the default pipeline an unread file would be"
            + " taken for: "
            + review.error());
    assertFalse(review.error().contains("changed while this review ran"), review.error());
  }

  @Test
  void aStageThePipelineMadeAPersonsWhileItsReviewerRanEscalatesItsReview() {
    var pipeline = new AtomicReference<>(ReviewLoop.stages("codex"));
    loop = new ReviewLoop(tempDir, ReviewLoop.YAML, project -> pipeline.get(), project -> "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    pipeline.set(
        ReviewPipelineConfig.fromMap(
            Map.of("stages", List.of(Map.<String, Object>of("name", "codex", "type", "human")))));

    loop.finish(reviewer.id(), CLEAN_REVIEW);

    var review = loop.reviews.findReview(reviewer.reviewId()).orElseThrow();
    assertEquals(
        "escalated",
        review.status(),
        "the stage kept its name and stopped being a reviewer's: a reviewer's verdict is no"
            + " longer what passes it");
    assertTrue(review.error().contains("stage 'codex'"), review.error());
  }

  @Test
  void anErroredReviewIsNotRetriedUnderAPipelineThatChangedSinceItsStagesWereWritten() {
    var pipeline = new AtomicReference<>(ReviewLoop.stages("codex"));
    loop = new ReviewLoop(tempDir, ReviewLoop.YAML, project -> pipeline.get(), project -> "codex");
    loop.refuseLaunches();
    loop.built("auth");
    var errored = loop.reviewOf("auth");
    assertEquals("failed", loop.statusOf(errored));
    pipeline.set(ReviewLoop.stages("claude-code"));

    assertEquals(1, sweeps(loop.reconciler(LATER), 2));

    assertEquals("escalated", loop.statusOf(errored));
    assertTrue(
        loop.reviews.findReview(errored).orElseThrow().error().contains("changed while"),
        "findings carry forward by stage name: a retry under other stages would carry none");
    assertEquals(1, loop.reviews.reviewsForSpec("auth").size(), "and no review is begun under it");
  }

  @Test
  void aFixIsNotReReviewedUnderAPipelineThatChangedWhileItRan() {
    var pipeline = new AtomicReference<>(ReviewLoop.stages("codex"));
    loop = new ReviewLoop(tempDir, ReviewLoop.YAML, project -> pipeline.get(), project -> "codex");
    loop.built("auth");
    loop.finish(loop.onlyLive().id(), CRITICAL_FINDING);
    var fix = loop.onlyLive();
    pipeline.set(ReviewLoop.stages("claude-code"));

    loop.finish(fix.id(), "fixed");

    assertEquals("escalated", loop.statusOf(fix.reviewId()));
    assertEquals(1, loop.reviews.reviewsForSpec("auth").size());
    assertTrue(loop.live().isEmpty(), "no reviewer is launched under the other pipeline");
  }

  @Test
  void aBuildThatEndsWhileItsProjectsDescriptorCannotBeReadIsHandedToAPersonNotReplayed() {
    loop = ReviewLoop.wired(tempDir, ReviewLoop.YAML);
    loop.describe("agent: [unterminated");

    var build = loop.built("auth");

    var review = loop.reviews.latestReviewForSpec("auth").orElseThrow();
    assertEquals(
        "escalated",
        review.status(),
        "the build's work is not reviewed under a pipeline nobody could read, nor under the"
            + " default one an unread file would be taken for: a person is told why");
    assertTrue(review.error().contains("could not be read"), review.error());
    assertEquals(SpecStatus.REVIEW, loop.specStatus("auth"));
    assertTrue(loop.live().isEmpty());
    assertEquals(0, sweeps(loop.reconciler(LATER), 3), "and nothing is owed for it any more");
    assertEquals(0, replaysOf(build));
  }

  @Test
  void aRunningReviewWhosePipelineLostItsStagesIsEscalatedSayingSo() {
    loop = ReviewLoop.staged(tempDir);
    var build = strandedReviewOf("auth");
    var review = loop.reviewOf("auth");
    var reconciler = loop.reconciler(LATER);

    assertEquals(1, sweeps(reconciler, 3), "one replay, and the review is a person's");

    var reason =
        "the project's review pipeline has no stages; set agent.review_pipeline.stages, then"
            + " re-dispatch with --restart";
    assertEquals("escalated", loop.statusOf(review));
    assertEquals(reason, loop.reviews.findReview(review).orElseThrow().error());
    assertEquals(SpecStatus.REVIEW, loop.specStatus("auth"));
    assertEquals(List.of(reason), loop.details("review_escalated"));
    assertEquals(
        List.of("Review escalated: " + reason + "."), loop.roomLines("auth", "Review esc"));
    assertEquals(1, replaysOf(build));
  }

  private static final String NO_STAGES =
      "the project's review pipeline has no stages; set agent.review_pipeline.stages, then"
          + " re-dispatch with --restart";

  /** A loop whose project's pipeline is whatever {@code pipeline} holds when it is asked. */
  private void loopUnder(AtomicReference<ReviewPipelineConfig> pipeline) {
    loop = new ReviewLoop(tempDir, ReviewLoop.YAML, project -> pipeline.get(), project -> "codex");
  }

  private void assertEscalatedForNoStages(String review) {
    assertEquals("escalated", loop.statusOf(review));
    assertEquals(NO_STAGES, loop.reviews.findReview(review).orElseThrow().error());
    assertEquals(SpecStatus.REVIEW, loop.specStatus("auth"));
    assertEquals(List.of(NO_STAGES), loop.details("review_escalated"));
  }

  @Test
  void aReviewersStopThatFindsThePipelineWithoutStagesEscalatesItsReview() {
    var pipeline = new AtomicReference<>(ReviewLoop.stages("codex"));
    loopUnder(pipeline);
    loop.built("auth");
    var reviewer = loop.onlyLive();
    pipeline.set(ReviewLoop.stages());

    loop.finish(reviewer.id(), CLEAN_REVIEW);

    assertEscalatedForNoStages(reviewer.reviewId());
  }

  @Test
  void aFixOwedThatFindsThePipelineWithoutStagesEscalatesItsReview() {
    var pipeline = new AtomicReference<>(ReviewLoop.stages("codex"));
    loopUnder(pipeline);
    loop.built("auth");
    var reviewer = loop.onlyLive();
    loop.container.exited(reviewer.id(), CRITICAL_FINDING, 0);
    Acting.system(() -> loop.runs.complete(reviewer.id(), "stopped", 0));
    var chat = chatTurnTakesTheRepo();
    loop.onEvent(loop.watcherStop(reviewer.id(), null));
    assertEquals(chat, loop.waitingOn(reviewer.reviewId()));
    pipeline.set(null);

    chatTurnEnds(chat);

    assertEscalatedForNoStages(reviewer.reviewId());
    assertTrue(loop.runsIn("auth", "fix").isEmpty());
  }

  @Test
  void aFinishedFixThatFindsThePipelineWithoutStagesEscalatesRatherThanStartANewReview() {
    var pipeline = new AtomicReference<>(ReviewLoop.stages("codex"));
    loopUnder(pipeline);
    loop.built("auth");
    loop.finish(loop.onlyLive().id(), CRITICAL_FINDING);
    var fix = loop.onlyLive();
    pipeline.set(ReviewLoop.stages());

    loop.finish(fix.id(), "fixed");

    assertEscalatedForNoStages(fix.reviewId());
    assertEquals(1, loop.reviews.reviewsForSpec("auth").size(), "no re-review nothing can judge");
  }

  @Test
  void anErroredReviewThatFindsThePipelineWithoutStagesEscalatesRatherThanRetry() {
    var pipeline = new AtomicReference<>(ReviewLoop.stages("codex"));
    loopUnder(pipeline);
    loop.refuseLaunches();
    loop.built("auth");
    var errored = loop.reviewOf("auth");
    assertEquals("failed", loop.statusOf(errored));
    pipeline.set(ReviewLoop.stages());

    assertEquals(1, sweeps(loop.reconciler(LATER), 2));

    assertEscalatedForNoStages(errored);
    assertEquals(1, loop.reviews.reviewsForSpec("auth").size());
  }

  @Test
  void aPassWhoseWriteFailsHalfwayLeavesTheReviewRunningTheSpecInReviewAndTheRoomUntold() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    roomFails();

    loop.finish(reviewer.id(), CLEAN_REVIEW);

    assertEquals("running", loop.statusOf(reviewer.reviewId()), "the pass rolled back whole");
    assertEquals(SpecStatus.REVIEW, loop.specStatus("auth"));
    assertTrue(loop.roomLines("auth", "Review passed").isEmpty());
    assertTrue(loop.events("review_completed").isEmpty(), "nothing is announced that did not land");
    assertEquals(1, loop.events("review_pipeline_error").size());

    roomRecovers();
    assertEquals(
        1, sweeps(loop.reconciler(LATER), 3), "a review left running is still owed its end");

    assertEquals("passed", loop.statusOf(reviewer.reviewId()));
    assertEquals(SpecStatus.AWAITING_MERGE, loop.specStatus("auth"));
    assertEquals(1, loop.roomLines("auth", "Review passed").size());
    assertEquals(1, loop.events("review_completed").size());
  }

  @Test
  void anEscalationWhoseWriteFailsHalfwayLeavesTheReviewAndItsSpecAsTheyWereAndTheRoomUntold() {
    var fix = fixing();
    roomFails();

    loop.exit(fix.id(), "boom", 1);

    var review = loop.reviews.findReview(fix.reviewId()).orElseThrow();
    assertEquals("failed", review.status(), "the escalation rolled back whole");
    assertNull(review.error(), "with its reason");
    assertEquals(SpecStatus.IN_PROGRESS, loop.specStatus("auth"));
    assertTrue(loop.roomLines("auth", "Review escalated").isEmpty());
    assertTrue(loop.details("review_escalated").isEmpty());

    roomRecovers();
    assertEquals(
        1, sweeps(loop.reconciler(LATER), 3), "the fix agent's stop is still owed its end");

    assertEquals("escalated", loop.statusOf(fix.reviewId()));
    assertEquals(SpecStatus.REVIEW, loop.specStatus("auth"));
    assertEquals(1, loop.roomLines("auth", "Review escalated").size());
  }

  @Test
  void anEscalatedReviewsSyncedRowCarriesItsReasonAndMainSaysWhatThisBoxSaid() {
    var fix = fixing();

    loop.exit(fix.id(), "boom", 1);

    var said = loop.details("review_escalated");
    assertEquals(
        List.of("fix iteration failed — fix agent failed: exit 1; triage and re-dispatch"), said);
    assertEquals(said.getFirst(), loop.reviews.comparableSnapshot(fix.reviewId()).get("error"));
    var onMain =
        loop.narratedOnMain(fix.reviewId()).stream()
            .filter(event -> "review_escalated".equals(event.type()))
            .toList();
    assertEquals(1, onMain.size());
    assertEquals(said.getFirst(), onMain.getFirst().data().get("detail"));
    assertEquals(
        Event.WellKnownData.SOURCE_SYNC, onMain.getFirst().data().get(Event.WellKnownData.SOURCE));
  }

  @Test
  void aReviewWithNoStageRowsWhoseFirstStageIsAPersonsIsOpenedWhoeverHoldsTheRepo() {
    loop =
        ReviewLoop.of(
            tempDir,
            ReviewPipelineConfig.fromMap(
                Map.of(
                    "max_iterations",
                    3,
                    "stages",
                    List.of(Map.<String, Object>of("name", "approve", "type", "human")))));
    strandedReviewOf("auth");
    loop.spec("billing", "api");
    loop.container.started(loop.run("billing", "build"));
    var reconciler = loop.reconciler(LATER);

    assertEquals(
        1,
        sweeps(reconciler, 3),
        "nothing says a review with no stage rows launches anything, and opening a person's"
            + " stage claims no repo: it is not held up by another spec's build");
    assertEquals(
        List.of("running"),
        loop.reviews.stagesForReview(loop.reviewOf("auth")).stream()
            .map(ReviewStore.StageRow::status)
            .toList());
  }

  @Test
  void aPersonsStageLeftUnopenedIsOpenedWhoeverHoldsTheRepo() {
    loop =
        ReviewLoop.of(
            tempDir,
            ReviewPipelineConfig.fromMap(
                Map.of(
                    "max_iterations",
                    3,
                    "stages",
                    List.of(
                        Map.<String, Object>of(
                            "name",
                            "codex",
                            "type",
                            "agent",
                            "agent",
                            "codex",
                            "gate",
                            "no_critical"),
                        Map.<String, Object>of("name", "approve", "type", "human")))));
    loop.built("auth");
    var reviewer = loop.onlyLive();
    loop.exitsUnheard(reviewer.id(), CLEAN_REVIEW, 0);
    Acting.system(
        () -> {
          loop.runs.complete(reviewer.id(), "stopped", 0);
          loop.reviews.completeStage(
              loop.reviews.stagesForReview(reviewer.reviewId()).getFirst().id(), "passed");
        });
    loop.restart();
    assertEquals("pending", loop.reviews.stagesForReview(reviewer.reviewId()).getLast().status());
    loop.spec("billing", "api");
    loop.container.started(loop.run("billing", "build"));

    assertEquals(
        1,
        loop.reconciler(LATER).sweep(),
        "the daemon died before the person's stage was opened, and opening it claims no repo");
    loop.settle();

    assertEquals(
        List.of("passed", "running"),
        loop.reviews.stagesForReview(reviewer.reviewId()).stream()
            .map(ReviewStore.StageRow::status)
            .toList(),
        "the review now waits on its person, not on another spec's build");
  }

  @Test
  void aReviewWaitingToLaunchIsNeverReplayedWhileARunHoldsItsClaim() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.spec("billing", "api");
    var holder = loop.run("billing", "build");
    loop.container.started(holder);
    var build = loop.built("auth");
    assertEquals(List.of(holder), loop.container.live(), "auth's reviewer waits for the repo");
    var reconciler = loop.reconciler(LATER);

    for (var turn = 1; turn <= 3; turn++) {
      var chat = loop.run("billing", "room");
      loop.container.started(chat);
      reconciler.sweep();
      loop.settle();
      loop.container.exited(chat, "replied", 0);
      loop.onEvent(
          RunStops.of(
              Event.WellKnownData.SOURCE_WATCHER,
              PROJECT,
              "billing",
              "claude-code",
              chat,
              "room",
              0,
              null));
      reconciler.sweep();
      loop.settle();
    }

    assertEquals(
        1,
        loop.events(Event.WellKnownTypes.AGENT_SESSION_STOPPED).stream()
            .filter(stop -> build.equals(stop.data().get(Event.WellKnownData.RUN_ID)))
            .count(),
        "read-only chat turns came and went and the holder never moved: auth's build stop was"
            + " heard once, and never replayed");
    assertEquals(List.of(holder), loop.container.live());
  }

  @Test
  void aRescueThatNeedsNoClaimIsNotHeldUpByARunThatHoldsTheRepo() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    loop.exitsUnheard(reviewer.id(), CLEAN_REVIEW, 0);
    Acting.system(
        () -> {
          loop.runs.complete(reviewer.id(), "stopped", 0);
          loop.reviews.completeStage(
              loop.reviews.stagesForReview(reviewer.reviewId()).getFirst().id(), "passed");
        });
    loop.restart();
    loop.spec("billing", "api");
    loop.container.started(loop.run("billing", "build"));

    assertEquals(1, loop.reconciler(LATER).sweep());
    loop.settle();

    assertEquals(
        "passed",
        loop.statusOf(reviewer.reviewId()),
        "every stage had passed when the daemon died: marking the review passed claims no repo,"
            + " whoever holds it");
    assertEquals(SpecStatus.AWAITING_MERGE, loop.specStatus("auth"));
  }

  @Test
  void aStaleBuildStopStartsNoReviewBesideTheBuildThatReplacedIt() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.spec("auth", "api");
    var replaced = loop.run("auth", "build");
    Acting.system(() -> loop.runs.complete(replaced, "stopped", 0));
    var fresh = loop.run("auth", "build");
    loop.container.started(fresh);

    loop.onEvent(ReviewLoop.buildStop("auth", replaced));

    assertTrue(
        loop.reviews.reviewsForSpec("auth").isEmpty(),
        "the spec was re-dispatched before its old build's stop was heard: that stop starts no"
            + " review beside the new build");
    assertEquals(SpecStatus.IN_PROGRESS, loop.specStatus("auth"));

    loop.container.exited(fresh, "boom", 1);
    loop.onEvent(
        RunStops.of(
            Event.WellKnownData.SOURCE_WATCHER,
            PROJECT,
            "auth",
            "claude-code",
            fresh,
            "build",
            1,
            null));

    assertTrue(loop.live().isEmpty(), "and no reviewer judges a build that failed");
    assertEquals(List.of("exit 1"), loop.details(Event.WellKnownTypes.AGENT_FAILED));
  }

  @Test
  void aRunWhoseContainerStoppedUnderItIsLeftByItsWatcherAndEndedByTheReconciler() {
    var fix = fixing();
    loop.container.stopped();

    loop.stalls(fix.id());

    assertTrue(
        loop.events(Event.WellKnownTypes.AGENT_SESSION_STOPPED).stream()
            .noneMatch(stop -> fix.id().equals(stop.data().get(Event.WellKnownData.RUN_ID))),
        "the watcher found the container stopped and said nothing of how the run ended");
    assertEquals("running", loop.runs.findById(fix.id()).orElseThrow().status());

    assertEquals(1, loop.reconciler(LATER).sweep(), "no watcher is left: the sweep speaks");
    loop.settle();

    assertEquals("stopped", loop.runs.findById(fix.id()).orElseThrow().status());
  }

  @Test
  void aFixAgentRefusedItsClaimWaitsAndItsVerdictIsSaidOnce() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    loop.container.exited(reviewer.id(), CRITICAL_FINDING, 0);
    Acting.system(() -> loop.runs.complete(reviewer.id(), "stopped", 0));
    var chat = chatTurnTakesTheRepo();

    loop.onEvent(loop.watcherStop(reviewer.id(), null));

    assertEquals("failed", loop.statusOf(reviewer.reviewId()));
    assertEquals(List.of(chat), loop.container.live(), "the fix agent was refused the repo");
    assertEquals(SpecStatus.REVIEW, loop.specStatus("auth"), "the spec moves when a fix runs");
    assertTrue(loop.events("review_iteration_started").isEmpty());
    assertTrue(
        loop.details("review_iteration_failed").isEmpty(),
        "a refused claim is not a fix that failed: " + loop.details("review_iteration_failed"));
    assertTrue(loop.details("review_escalated").isEmpty());

    chatTurnEnds(chat);

    var fix = loop.onlyLive();
    assertEquals("fix", fix.role(), "the fix the review was owed starts once the repo is free");
    assertEquals(reviewer.reviewId(), fix.reviewId());
    assertEquals(SpecStatus.IN_PROGRESS, loop.specStatus("auth"));
    assertEquals(1, loop.events("review_iteration_started").size());
    assertEquals(1, verdictsInTheRoom(), "the verdict was said when the gate failed, once");
  }

  @Test
  void aFixAgentRefusedItsClaimWaitsOnItsHolderAndIsReplayedOnceWhenThatRunEndsWithNoStop() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    loop.container.exited(reviewer.id(), CRITICAL_FINDING, 0);
    Acting.system(() -> loop.runs.complete(reviewer.id(), "stopped", 0));
    var chat = chatTurnTakesTheRepo();
    loop.onEvent(loop.watcherStop(reviewer.id(), null));
    var reconciler = loop.reconciler(LATER);

    assertEquals("failed", loop.statusOf(reviewer.reviewId()));
    assertEquals(chat, loop.waitingOn(reviewer.reviewId()), "the failed review records its wait");
    assertEquals(List.of(waitLine(chat, "room-full", "auth")), waitsInTheRoom());
    assertEquals(0, sweeps(reconciler, 2));
    assertEquals(0, replaysOf(reviewer.id()), "no replay while the run it waits on lives");

    loop.container.exited(chat, "replied", 0);
    assertEquals(1, reconciler.sweep(), "the dead chat turn is finished in place");
    assertEquals(1, sweeps(reconciler, 3), "and the fix it held up is rescued, once");

    assertEquals(1, replaysOf(reviewer.id()));
    var fix = loop.onlyLive();
    assertEquals("fix", fix.role());
    assertEquals(reviewer.reviewId(), fix.reviewId());
    assertNull(loop.waitingOn(reviewer.reviewId()));
    assertEquals(1, verdictsInTheRoom(), "the verdict was said when the gate failed, once");
  }

  @Test
  void aReviewWhoseStageRowsARescueWritesIsRescuedOnceNotOncePerKey() {
    loop = ReviewLoop.staged(tempDir, "codex");
    var build = strandedReviewOf("auth");
    var reconciler = loop.reconciler(LATER);
    loop.refuseLaunches();

    assertEquals(1, reconciler.sweep(), "a review with no stage rows yet is rescued");
    loop.settle();
    assertEquals(1, loop.reviews.stagesForReview(loop.reviewOf("auth")).size());

    assertEquals(
        1,
        reconciler.sweep(),
        "the rescue wrote the stage rows and its launch errored: the errored review is owed its"
            + " retry, under its own key");
    loop.settle();
    assertEquals(
        2,
        loop.events(Event.WellKnownTypes.AGENT_SESSION_STOPPED).stream()
            .filter(stop -> "reconcile".equals(stop.data().get(Event.WellKnownData.SOURCE)))
            .count());
  }

  @Test
  void aFinishedFixWhoseReReviewWasNeverWrittenIsRescued() {
    var fix = fixing();
    loop.exitsUnheard(fix.id(), "fixed and pushed", 0);
    new SpecStoreAuditPersister(new EventStore(loop.db))
        .onEvent(loop.watcherStop(fix.id(), null).withId(901));
    Acting.system(
        () -> {
          loop.runs.complete(fix.id(), "stopped", 0);
          loop.specs.updateStatus("auth", SpecStatus.REVIEW);
        });
    loop.restart();
    var reconciler = loop.reconciler(LATER);

    assertEquals(
        1,
        reconciler.sweep(),
        "the daemon died after the fix agent's stop was recorded and before anything came of it");
    loop.settle();

    var reviewer = loop.onlyLive();
    assertEquals("review", reviewer.role());
    assertEquals(2, loop.reviews.findReview(reviewer.reviewId()).orElseThrow().iteration());
    assertEquals(0, reconciler.sweep(), "a reviewer serves the re-review now");
  }

  @Test
  void aStopOfARunANewerBuildHasReplacedStartsNoReReview() {
    var fix = fixing();
    loop.container.exited(fix.id(), "fixed and pushed", 0);
    loop.run("auth", "build");

    loop.onEvent(loop.watcherStop(fix.id(), null));

    assertEquals(
        1,
        loop.reviews.reviewsForSpec("auth").size(),
        "a re-dispatch reserved a new build before the fix agent's stop was heard: the new"
            + " attempt owns the spec");
    assertTrue(loop.live().isEmpty());
    assertEquals(SpecStatus.IN_PROGRESS, loop.specStatus("auth"));
  }

  @Test
  void aBuildsLateStopAfterAnEscalationStartsNoNewReview() {
    loop = ReviewLoop.staged(tempDir, "codex");
    var build = loop.built("auth");
    loop.finish(loop.onlyLive().id(), CRITICAL_FINDING);
    var fix = loop.onlyLive();
    loop.exit(fix.id(), "boom", 1);
    assertEquals("escalated", loop.statusOf(fix.reviewId()));

    loop.onEvent(ReviewLoop.buildStop("auth", build));

    assertEquals(1, loop.reviews.reviewsForSpec("auth").size(), "an escalated spec is a person's");
    assertTrue(loop.live().isEmpty());
    assertEquals(1, loop.details("review_escalated").size());
  }

  @Test
  void aSpecCancelledWhileItsReviewerRunsGetsNoFixAgent() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    Acting.system(
        () ->
            assertTrue(
                loop.specs.compareAndSetStatus("auth", SpecStatus.REVIEW, SpecStatus.CANCELLED)));

    loop.finish(reviewer.id(), CRITICAL_FINDING);

    assertTrue(loop.live().isEmpty(), "a cancelled spec is not the loop's to act on");
    assertTrue(loop.events("review_iteration_started").isEmpty());
    assertEquals(SpecStatus.CANCELLED, loop.specStatus("auth"));
    assertEquals("stopped", loop.runs.findById(reviewer.id()).orElseThrow().status());
  }

  @Test
  void aRunAnotherBoxPushedThatNamesThisBoxsReviewNeverHoldsItsLoop() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    var foreign = DateTimeUtils.newId().toString();
    var pushed = new LinkedHashMap<String, Object>();
    pushed.put("project", "elsewhere");
    pushed.put("spec_id", "other-spec");
    pushed.put("node", "node-b");
    pushed.put("owner", "node-b");
    pushed.put("role", "fix");
    pushed.put("agent", "claude-code");
    pushed.put("status", "running");
    pushed.put("started_at", Instant.now().plus(Duration.ofMinutes(1)).toString());
    pushed.put("repos", List.of());
    pushed.put("principal", "claude/fix-" + foreign);
    pushed.put("review_id", reviewer.reviewId());
    Acting.system(() -> loop.runs.applyRevision(foreign, pushed, "1-abcdef"));
    loop.exitsUnheard(reviewer.id(), CLEAN_REVIEW, 0);
    Acting.system(() -> loop.runs.complete(reviewer.id(), "stopped", 0));
    loop.restart();

    assertEquals(
        1,
        loop.reconciler(LATER).sweep(),
        "only the runs this box executed serve its reviews, whatever a pushed row names: the"
            + " review whose reviewer's stop was lost is still owed that stop, and rescued");
    loop.settle();

    assertEquals("passed", loop.statusOf(reviewer.reviewId()));
    assertEquals(SpecStatus.AWAITING_MERGE, loop.specStatus("auth"));
  }

  @Test
  void aReviewAnotherBoxStartedIsNeverDrivenFromThisBoxsCopyOfIt() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.spec("theirs", SpecStatus.REVIEW, "claude-code", null, null, List.of("web"));
    var synced = DateTimeUtils.newId().toString();
    var copy = new LinkedHashMap<String, Object>();
    copy.put("id", synced);
    copy.put("spec_id", "theirs");
    copy.put("iteration", 1);
    copy.put("status", "running");
    copy.put("created_at", Instant.now().minus(Duration.ofHours(1)).toString());
    copy.put("stages", List.of());
    Acting.system(() -> loop.reviews.applyRevision(synced, copy, "1-abcdef"));
    loop.built("auth");

    loop.finish(loop.onlyLive().id(), CLEAN_REVIEW);

    assertTrue(
        loop.runs.listForSpec("theirs").isEmpty(),
        "a stop here frees this box's claims; a review another box runs is that box's to drive");
    assertEquals(0, loop.reconciler(LATER).sweep());
    assertEquals("running", loop.statusOf(synced));
  }
}
