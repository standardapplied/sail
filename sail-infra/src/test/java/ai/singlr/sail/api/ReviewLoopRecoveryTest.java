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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.RunStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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

  private long verdictsInTheRoom() {
    return loop.messages.list("auth", null, 20).stream()
        .filter(message -> message.body().startsWith("Review failed"))
        .count();
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
    loop.spec("auth", "api");
    var build = loop.run("auth", "build");
    Acting.system(
        () -> {
          loop.runs.complete(build, "stopped", 0);
          loop.specs.updateStatus("auth", SpecStatus.REVIEW);
          loop.reviews.createReview("auth", 1);
        });
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
    loop.spec("auth", "api");
    var build = loop.run("auth", "build");
    Acting.system(
        () -> {
          loop.runs.complete(build, "stopped", 0);
          loop.specs.updateStatus("auth", SpecStatus.REVIEW);
          loop.reviews.createReview("auth", 1);
        });
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
  void aFixOwedBehindARunThatEndsWithNoStopIsRescuedAgainThoughItsRescueWasSpent() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    loop.container.exited(reviewer.id(), CRITICAL_FINDING, 0);
    Acting.system(() -> loop.runs.complete(reviewer.id(), "stopped", 0));
    var chat = chatTurnTakesTheRepo();
    loop.onEvent(loop.watcherStop(reviewer.id(), null));
    var reconciler = loop.reconciler(LATER);

    assertEquals(
        1,
        reconciler.sweep(),
        "what a failed gate is owed may be an escalation, which needs no claim: it is rescued"
            + " whoever holds the repo");
    loop.settle();
    assertEquals(List.of(chat), loop.container.live(), "this one is owed a fix agent: refused");
    assertEquals(0, reconciler.sweep(), "its rescue is spent, and its claim still held");

    loop.container.exited(chat, "replied", 0);
    assertEquals(1, reconciler.sweep(), "the dead chat turn is finished in place");
    assertEquals(1, reconciler.sweep(), "seen held, now free: owed its rescue again");
    loop.settle();

    var fix = loop.onlyLive();
    assertEquals("fix", fix.role());
    assertEquals(reviewer.reviewId(), fix.reviewId());
    assertEquals(1, verdictsInTheRoom());
  }

  @Test
  void aReviewWhoseStageRowsARescueWritesIsRescuedOnceNotOncePerKey() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.spec("auth", "api");
    var build = loop.run("auth", "build");
    Acting.system(
        () -> {
          loop.runs.complete(build, "stopped", 0);
          loop.specs.updateStatus("auth", SpecStatus.REVIEW);
          loop.reviews.createReview("auth", 1);
        });
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
