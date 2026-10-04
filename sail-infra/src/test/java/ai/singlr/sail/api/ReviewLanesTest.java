/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static ai.singlr.sail.api.ReviewScripts.CLEAN_REVIEW;
import static ai.singlr.sail.api.ReviewScripts.CRITICAL_FINDING;
import static ai.singlr.sail.api.ReviewScripts.fixAllCarried;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.RunStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lane contract's scenario matrix (ARCHITECTURE.md, "The loop lanes"): every agent of the
 * review loop launches, is watched and stops one way. Each test is one row, driven through {@link
 * ReviewLoop} — the production launch path, pipeline, tracker and bus over a fake container — and a
 * run ends only the way it ends in production: its watcher's stop, or the reconciler's.
 */
class ReviewLanesTest {

  private static final String PROJECT = ReviewLoop.PROJECT;

  @TempDir Path tempDir;
  private ReviewLoop loop;

  @AfterEach
  void tearDown() {
    loop.close();
  }

  @Test
  void aReviewerThatCompletesHasItsStopRoutedByLaneAndItsStageResolvedFromItsOwnLog() {
    loop = ReviewLoop.staged(tempDir, "codex", "claude-code");
    loop.built("auth");

    var first = loop.onlyLive();
    assertEquals("review", first.role());
    assertEquals(loop.reviewOf("auth"), first.reviewId());
    assertEquals("codex/review-" + first.id(), first.principal());
    assertEquals(AgentUnit.forRun(first.id()).unitName(), first.unit());
    var launch = loop.launchCommandOf(first.id());
    assertEquals(
        List.of("auth", "codex", first.id(), "review"),
        List.of(
            launch.get(launch.size() - 5),
            launch.get(launch.size() - 4),
            launch.get(launch.size() - 3),
            launch.getLast()),
        "a reviewer runs with the spec, agent, run id and lane every lane's hooks read");
    assertTrue(launch.get(launch.size() - 2).startsWith("sailrun"), "and its own run credential");
    assertTrue(
        String.join(" ", launch).contains("systemd-run --user"),
        "launched as its run's systemd unit, never a foreground exec the host waits on");
    assertEquals(SpecStatus.REVIEW, loop.specStatus("auth"));

    loop.finish(first.id(), CLEAN_REVIEW);

    var second = loop.onlyLive();
    assertEquals("stopped", loop.runs.findById(first.id()).orElseThrow().status());
    assertEquals("claude-code", second.agent());
    assertEquals(
        first.reviewId(), second.reviewId(), "the next stage's reviewer serves the review");
    assertNotEquals(first.id(), second.id(), "as its own run");
    assertEquals(
        List.of("passed", "running"),
        loop.reviews.stagesForReview(first.reviewId()).stream().map(s -> s.status()).toList());

    loop.finish(second.id(), CLEAN_REVIEW);

    assertEquals("passed", loop.statusOf(first.reviewId()));
    assertEquals(SpecStatus.AWAITING_MERGE, loop.specStatus("auth"));
    assertTrue(loop.live().isEmpty());
  }

  @Test
  void aReviewerPastItsTimeLimitIsKilledAndItsReviewErrorsWithTheReasonWithinTheRetryBudget() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reconciler = loop.reconciler(Instant::now);

    for (var attempt = 1; attempt <= ReviewPipelineController.MAX_ERRORED_RETRIES; attempt++) {
      var reviewer = loop.onlyLive();
      var review = reviewer.reviewId();

      loop.outlastsItsTimeLimit(reviewer.id());

      assertFalse(loop.container.alive(reviewer.id()), "a reaped agent is dead");
      assertEquals("failed", loop.statusOf(review));
      assertEquals(
          "reviewer killed: time limit (45m)",
          loop.reviews.findReview(review).orElseThrow().error());
      assertEquals(attempt, loop.details("review_errored").size());
      assertEquals("reviewer killed: time limit (45m)", loop.details("review_errored").getLast());
      assertNull(
          loop.runs.findById(reviewer.id()).orElseThrow().exitCode(),
          "a killed run has no exit code of its own");
      assertTrue(loop.live().isEmpty(), "an errored review waits for the reconciler's retry");

      assertEquals(1, reconciler.sweep());
      loop.settle();
    }

    assertTrue(loop.live().isEmpty(), "the budget is spent: no fourth reviewer launches");
    assertEquals("escalated", loop.statusOf(loop.reviewOf("auth")));
    assertEquals(
        ReviewPipelineController.MAX_ERRORED_RETRIES,
        loop.reviews.reviewsForSpec("auth").size(),
        "every retry ran as the same iteration");
    assertTrue(loop.details("review_escalated").getFirst().contains("errored in a row"));
  }

  @Test
  void aStalledReviewerErrorsItsReviewWithTheStallAsTheReason() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();

    loop.stalls(reviewer.id());

    assertFalse(loop.container.alive(reviewer.id()));
    assertEquals(List.of("reviewer killed: stall (20m)"), loop.details("review_errored"));
    assertEquals(SpecStatus.REVIEW, loop.specStatus("auth"));
  }

  @Test
  void aFixAgentThatCompletesIsFollowedByTheReReviewAsTheNextIterationWithItsOwnRun() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();

    loop.finish(reviewer.id(), CRITICAL_FINDING);

    var fix = loop.onlyLive();
    assertEquals("fix", fix.role());
    assertEquals("claude-code", fix.agent(), "the spec's own agent fixes what the reviewer found");
    assertEquals(reviewer.reviewId(), fix.reviewId(), "the fix run serves the review it answers");
    assertEquals("claude/fix-" + fix.id(), fix.principal());
    assertEquals("failed", loop.statusOf(reviewer.reviewId()));
    assertEquals(SpecStatus.IN_PROGRESS, loop.specStatus("auth"));
    assertTrue(fix.task().contains("wait for or watch CI"), "the fix task ends at a pushed commit");

    loop.finish(fix.id(), "fixed and pushed");

    var second = loop.onlyLive();
    assertEquals("review", second.role());
    assertNotEquals(reviewer.reviewId(), second.reviewId());
    assertEquals(2, loop.reviews.findReview(second.reviewId()).orElseThrow().iteration());
    assertEquals(SpecStatus.REVIEW, loop.specStatus("auth"));
    assertTrue(loop.container.commits().isEmpty(), "a clean tree needs no rescue");

    loop.finish(second.id(), fixAllCarried(second.task()));

    assertEquals("passed", loop.statusOf(second.reviewId()));
    assertEquals(SpecStatus.AWAITING_MERGE, loop.specStatus("auth"));
    assertEquals(
        List.of("build", "review", "fix", "review"),
        loop.runs.listForSpec("auth").reversed().stream().map(RunStore.RunRow::role).toList());
  }

  @Test
  void aFixAgentPastItsTimeLimitIsKilledNothingOfItsLandsAndTheRoomIsToldWhy() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    loop.finish(loop.onlyLive().id(), CRITICAL_FINDING);
    var fix = loop.onlyLive();
    loop.container.dirty("api", " M src/Half.java\n");

    loop.outlastsItsTimeLimit(fix.id());

    assertFalse(loop.container.alive(fix.id()), "a reaped agent is dead");
    assertTrue(
        loop.container.commits().isEmpty(),
        "nothing lands after the kill: a killed fix agent's half-done work is not committed");
    assertEquals(
        List.of("fix agent killed: time limit (45m)"), loop.details("review_iteration_failed"));
    assertEquals("escalated", loop.statusOf(fix.reviewId()));
    assertEquals(SpecStatus.REVIEW, loop.specStatus("auth"));
    assertTrue(
        loop.details("review_escalated").getFirst().contains("fix agent killed: time limit (45m)"));
    assertTrue(loop.live().isEmpty(), "no re-review runs over the code the reviewer just failed");
    assertEquals(1, loop.reviews.reviewsForSpec("auth").size());
  }

  @Test
  void aFixAgentThatEndsWithADirtyTreeHasItsWorkRescuedOnItsStop() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    loop.finish(loop.onlyLive().id(), CRITICAL_FINDING);
    var fix = loop.onlyLive();
    loop.container.dirty("api", " M src/Fixed.java\n");

    loop.finish(fix.id(), "fixed, forgot to commit");

    assertEquals(1, loop.container.commits().size());
    assertTrue(loop.container.commits().getFirst().contains("address 1 review finding"));
    var guardrail = loop.events(Event.WellKnownTypes.GUARDRAIL_TRIGGERED).getFirst();
    assertTrue(guardrail.data().get("reason").toString().contains("src/Fixed.java"));
    assertEquals("review", loop.onlyLive().role(), "the re-review judges the rescued branch");
  }

  @Test
  void aDaemonRestartWhileAReviewerRunsFailsNothingAndReArmsAWatcherThatDiedWithIt() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    var review = reviewer.reviewId();

    loop.restart();

    assertEquals(
        "running", loop.statusOf(review), "nothing at start marks a running review failed");
    assertEquals("running", loop.runs.findById(reviewer.id()).orElseThrow().status());
    assertEquals(
        0, loop.reconciler(ReviewLoop.LATER).sweep(), "the agent is alive: no stop to replay");
    assertEquals(
        0,
        loop.rearmer().rearm(),
        "its watcher is a unit of its own and outlived the daemon too: one watcher, never two");

    loop.container.watcherGone(reviewer.id());

    assertEquals(
        1, loop.rearmer().rearm(), "a run whose watcher is gone is unguarded; it is re-armed");
    var watchers = loop.watcherCommandsOf(reviewer.id());
    assertEquals(2, watchers.size());
    assertTrue(watchers.getLast().contains("--max-duration 45m"), watchers.getLast());

    loop.finish(reviewer.id(), CLEAN_REVIEW);

    assertEquals("passed", loop.statusOf(review));
    assertEquals(SpecStatus.AWAITING_MERGE, loop.specStatus("auth"));
  }

  @Test
  void aReviewerThatExitedWhileTheDaemonWasDownHasItsStopPublishedAtStart() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    loop.exitsUnheard(reviewer.id(), CLEAN_REVIEW, 0);

    loop.restart();
    var replayed = loop.reconciler(ReviewLoop.LATER).sweep();
    loop.settle();

    assertEquals(1, replayed);
    var stop = loop.events(Event.WellKnownTypes.AGENT_SESSION_STOPPED).getLast();
    assertEquals("reconcile", stop.data().get("source"));
    assertEquals("review", stop.data().get("run_role"));
    assertEquals(reviewer.id(), stop.data().get("run_id"));
    assertEquals("stopped", loop.runs.findById(reviewer.id()).orElseThrow().status());
    assertEquals("passed", loop.statusOf(reviewer.reviewId()));
    assertEquals(SpecStatus.AWAITING_MERGE, loop.specStatus("auth"));
  }

  @Test
  void aFixAgentThatExitsUnwatchedIsReconciledAsABuildIsAndTheLoopGoesOn() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    loop.finish(loop.onlyLive().id(), CRITICAL_FINDING);
    var fix = loop.onlyLive();
    loop.exitsUnheard(fix.id(), "fixed and pushed", 0);

    var replayed = loop.reconciler(ReviewLoop.LATER).sweep();
    loop.settle();

    assertEquals(1, replayed, "the reconciler sees a fix run as it sees a build");
    assertEquals("stopped", loop.runs.findById(fix.id()).orElseThrow().status());
    var second = loop.onlyLive();
    assertEquals("review", second.role(), "the pipeline advances on the reconciled stop");
    assertEquals(2, loop.reviews.findReview(second.reviewId()).orElseThrow().iteration());
  }

  @Test
  void twoSpecsPipelinesInOneContainerEachAdvanceOnlyOnTheirOwnRunsStop() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth", "api");
    loop.built("billing", "web");

    var live = loop.live();
    assertEquals(2, live.size());
    var auth = live.stream().filter(run -> "auth".equals(run.specId())).findFirst().orElseThrow();
    var billing =
        live.stream().filter(run -> "billing".equals(run.specId())).findFirst().orElseThrow();
    for (var paths :
        List.<Function<AgentUnit, String>>of(
            AgentUnit::unitName,
            AgentUnit::logPath,
            AgentUnit::taskPath,
            AgentUnit::guardrailTriggerPath)) {
      assertNotEquals(
          paths.apply(AgentUnit.forRun(auth.id())), paths.apply(AgentUnit.forRun(billing.id())));
    }
    assertNotEquals(loop.watcherCommandOf(auth.id()), loop.watcherCommandOf(billing.id()));
    assertTrue(loop.watcherCommandOf(auth.id()).contains("sail-watch-" + auth.id()));

    loop.finish(auth.id(), CRITICAL_FINDING);

    assertEquals("failed", loop.statusOf(auth.reviewId()));
    assertEquals("running", loop.statusOf(billing.reviewId()), "billing's review saw none of it");
    assertTrue(loop.container.alive(billing.id()));

    loop.finish(billing.id(), CLEAN_REVIEW);

    assertEquals("passed", loop.statusOf(billing.reviewId()));
    assertEquals(SpecStatus.AWAITING_MERGE, loop.specStatus("billing"));
    assertEquals(SpecStatus.IN_PROGRESS, loop.specStatus("auth"), "auth is with its fix agent");
  }

  @Test
  void aPlainReviewerAndAStreamingOneAreEachJudgedOnTheirOwnRunsLog() {
    loop = ReviewLoop.staged(tempDir, "codex", "claude-code");
    loop.built("auth");
    var codex = loop.onlyLive();

    loop.finish(codex.id(), CLEAN_REVIEW);

    var claude = loop.onlyLive();
    var streamed =
        String.join(
            "\n",
            YamlUtil.dumpJson(
                Map.of(
                    "type",
                    "assistant",
                    "message",
                    Map.of("content", List.of(Map.of("type", "text", "text", CRITICAL_FINDING))))),
            YamlUtil.dumpJson(Map.of("type", "result", "result", CLEAN_REVIEW)));
    loop.finish(claude.id(), streamed);

    assertEquals(
        "passed",
        loop.statusOf(codex.reviewId()),
        "the streamed reviewer's verdict is its final result, and the plain reviewer's log was"
            + " never read for it");
    assertEquals(
        CLEAN_REVIEW, loop.container.file(AgentUnit.forRun(codex.id()).logPath()), "one log each");
    assertTrue(loop.reviews.findingsForReview(codex.reviewId()).isEmpty());
  }

  @Test
  void aBuildsStopWhileTheSpecsFixAgentRunsIsNotTakenAsTheFixAgentsStop() {
    loop = ReviewLoop.staged(tempDir, "codex");
    var build = loop.built("auth");
    loop.finish(loop.onlyLive().id(), CRITICAL_FINDING);
    var fix = loop.onlyLive();
    assertEquals(SpecStatus.IN_PROGRESS, loop.specStatus("auth"));

    loop.onEvent(
        RunStops.of(
            Event.WellKnownData.SOURCE_RECONCILE,
            PROJECT,
            "auth",
            "claude-code",
            build,
            "build",
            0,
            null));

    assertEquals(
        1,
        loop.reviews.reviewsForSpec("auth").size(),
        "routed by lane, not by the spec's status: a build's stop starts no review beside a"
            + " working fix agent");
    assertEquals(
        fix.id(), loop.onlyLive().id(), "and the fix agent is still the run the loop awaits");
    assertEquals(SpecStatus.IN_PROGRESS, loop.specStatus("auth"));

    loop.finish(fix.id(), "fixed and pushed");

    assertEquals("review", loop.onlyLive().role());
    assertEquals(2, loop.reviews.reviewsForSpec("auth").size());
  }

  @Test
  void aReviewLanesLimitsDefaultTo45MinutesAndAnEditAppliesToTheNextRunOnly() {
    loop = ReviewLoop.wired(tempDir, ReviewLoop.YAML);
    loop.built("auth");
    var reviewer = loop.onlyLive();
    var first = loop.watcherCommandOf(reviewer.id());
    assertTrue(
        first.endsWith("--action stop --max-duration 45m --max-idle 20m"),
        "no review_pipeline.guardrails: the review lanes' own defaults, not the build lane's 4h: "
            + first);

    loop.describe(
        ReviewLoop.YAML
            + """
                guardrails:
                  max_duration: 6h
                review_pipeline:
                  guardrails:
                    max_duration: 90m
                    max_idle: 30m
                    action: snapshot-and-stop
              """);
    loop.finish(reviewer.id(), CRITICAL_FINDING);

    var fix = loop.onlyLive();
    assertEquals("fix", fix.role());
    assertTrue(
        loop.watcherCommandOf(fix.id())
            .endsWith("--action snapshot-and-stop --max-duration 90m --max-idle 30m"),
        "the fix run launched after the edit is held to the new review-lane limits, never the"
            + " build lane's: "
            + loop.watcherCommandOf(fix.id()));
    assertEquals(
        List.of(first), loop.watcherCommandsOf(reviewer.id()), "the first run kept its own");
  }

  @Test
  void anOperatorsStopOfAReviewerEscalatesItsReviewInsteadOfLeavingItRunningUnserved() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    loop.container.exited(reviewer.id(), "", 143);
    Acting.system(() -> assertTrue(loop.runs.claimStop(reviewer.id(), "stopped", () -> {})));

    loop.onEvent(
        StopOperations.cancelEvent(
            loop.runs.findById(reviewer.id()).orElseThrow(),
            Event.WellKnownData.SOURCE_OPERATOR,
            "uday"));

    assertEquals("escalated", loop.statusOf(reviewer.reviewId()));
    assertTrue(
        loop.details("review_escalated").getFirst().contains("reviewer stopped by an operator"));
    assertEquals(SpecStatus.REVIEW, loop.specStatus("auth"));
    assertTrue(loop.live().isEmpty(), "a person's stop is not retried over");
  }

  @Test
  void theWatchersStopOfARunAnOperatorIsStoppingIsLeftToTheOperatorsCancel() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    loop.finish(loop.onlyLive().id(), CRITICAL_FINDING);
    var fix = loop.onlyLive();
    Acting.system(() -> assertTrue(loop.runs.claimStop(fix.id(), "stopping", () -> {})));
    loop.container.exited(fix.id(), "half done", 0);

    loop.onEvent(loop.watcherStop(fix.id(), null));

    assertEquals(
        1,
        loop.reviews.reviewsForSpec("auth").size(),
        "the unit died under the operator's halt: its exit is not a finished fix to re-review");
    assertTrue(loop.container.commits().isEmpty());
    assertEquals(
        "failed",
        loop.statusOf(fix.reviewId()),
        "nothing is decided while the halt is under way: one that fails gives the run back");

    Acting.system(() -> assertTrue(loop.runs.transition(fix.id(), "stopping", "stopped")));
    loop.onEvent(
        StopOperations.cancelEvent(
            loop.runs.findById(fix.id()).orElseThrow(),
            Event.WellKnownData.SOURCE_OPERATOR,
            "uday"));

    assertEquals("escalated", loop.statusOf(fix.reviewId()));
    assertTrue(
        loop.details("review_escalated").getFirst().contains("fix agent stopped by an operator"));
    assertTrue(loop.live().isEmpty());
  }

  @Test
  void theWatchersStopHeardAfterAnOperatorsStopIsFinalizedNeverRestartsTheLoop() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    loop.finish(loop.onlyLive().id(), CRITICAL_FINDING);
    var fix = loop.onlyLive();
    loop.container.dirty("api", " M src/Half.java\n");
    Acting.system(() -> assertTrue(loop.runs.claimStop(fix.id(), "stopping", () -> {})));
    loop.container.exited(fix.id(), "half done", 0);
    Acting.system(() -> assertTrue(loop.runs.transition(fix.id(), "stopping", "stopped")));

    loop.onEvent(loop.watcherStop(fix.id(), null));

    assertEquals(
        1,
        loop.reviews.reviewsForSpec("auth").size(),
        "the unit a person halted reports exit 0: that is not a fix to commit and re-review");
    assertTrue(loop.container.commits().isEmpty(), "nothing of a stopped fix agent's lands");
    assertTrue(loop.live().isEmpty());
    assertEquals(
        "escalated", loop.statusOf(fix.reviewId()), "the stop is the operator's, whoever says");
    assertTrue(
        loop.details("review_escalated").getFirst().contains("fix agent stopped by an operator"));

    loop.onEvent(
        StopOperations.cancelEvent(
            loop.runs.findById(fix.id()).orElseThrow(),
            Event.WellKnownData.SOURCE_OPERATOR,
            "uday"));

    assertEquals(
        1, loop.details("review_escalated").size(), "the cancel finds it already escalated");
    assertEquals(SpecStatus.REVIEW, loop.specStatus("auth"));
  }

  @Test
  void aReviewersStopHeardAfterAnOperatorsStopIsFinalizedIsNotAnErrorToRetry() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    Acting.system(() -> assertTrue(loop.runs.claimStop(reviewer.id(), "stopping", () -> {})));
    loop.container.exited(reviewer.id(), "", 143);
    Acting.system(() -> assertTrue(loop.runs.transition(reviewer.id(), "stopping", "stopped")));

    loop.onEvent(loop.watcherStop(reviewer.id(), null));

    assertEquals("escalated", loop.statusOf(reviewer.reviewId()));
    assertTrue(
        loop.details("review_errored").isEmpty(), "a person's stop is not a reviewer's failure");
    assertTrue(loop.live().isEmpty(), "and it is not retried over");
    assertEquals(
        0, loop.reconciler(ReviewLoop.LATER).sweep(), "an escalated review is never retried");
  }

  @Test
  void anOperatorsStopWhoseCancelWasLostWithTheDaemonStillEscalatesOnTheReconcilersReplay() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    loop.finish(loop.onlyLive().id(), CRITICAL_FINDING);
    var fix = loop.onlyLive();
    loop.container.dirty("api", " M src/Half.java\n");
    Acting.system(() -> assertTrue(loop.runs.claimStop(fix.id(), "stopping", () -> {})));
    loop.container.exited(fix.id(), "half done", 0);
    Acting.system(() -> assertTrue(loop.runs.transition(fix.id(), "stopping", "stopped")));
    loop.restart();

    assertEquals(1, loop.reconciler(ReviewLoop.LATER).sweep());
    loop.settle();

    assertEquals("escalated", loop.statusOf(fix.reviewId()));
    assertTrue(loop.container.commits().isEmpty());
    assertTrue(loop.live().isEmpty());
    assertEquals(
        0, loop.reconciler(ReviewLoop.LATER).sweep(), "the replay was acted on: none follows");
  }

  @Test
  void aReplayedStopCarriesOnlyTheReasonAnAuthoritativeStopOfThatVeryRunRecorded() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    loop.finish(reviewer.id(), CRITICAL_FINDING);
    var fix = loop.onlyLive();
    loop.container.exited(fix.id(), "fixed and pushed", 0);
    var recorded = new SpecStoreAuditPersister(new EventStore(loop.db));
    var others =
        RunStops.of(
            Event.WellKnownData.SOURCE_WATCHER,
            PROJECT,
            "auth",
            "codex",
            reviewer.id(),
            "review",
            null,
            "stall (20m)");
    var hooks = new LinkedHashMap<>(loop.watcherStop(fix.id(), "turn ended").data());
    hooks.remove(Event.WellKnownData.SOURCE);
    recorded.onEvent(others.withId(901));
    recorded.onEvent(
        Event.of(
                PROJECT,
                "auth",
                Event.WellKnownTypes.AGENT_SESSION_STOPPED,
                "claude-code",
                "host",
                hooks)
            .withId(902));
    recorded.onEvent(
        Event.of(
                PROJECT,
                "auth",
                Event.WellKnownTypes.AGENT_SESSION_STOPPED,
                "claude-code",
                "host",
                Map.of(
                    Event.WellKnownData.SOURCE,
                    Event.WellKnownData.SOURCE_WATCHER,
                    Event.WellKnownData.REASON,
                    "time limit (45m)"))
            .withId(903));
    Acting.system(() -> loop.runs.complete(fix.id(), "stopped", 0));
    loop.restart();

    assertEquals(1, loop.reconciler(ReviewLoop.LATER).sweep());
    loop.settle();

    assertTrue(
        loop.details("review_iteration_failed").isEmpty(),
        "this fix agent was not killed: another run's reason, a hook's, and one that names no run"
            + " at all never speak for it");
    assertEquals("review", loop.onlyLive().role(), "its work is re-reviewed, as a clean finish is");
  }

  @Test
  void aKillTheWatcherRecordedIsStillAKillWhenTheReconcilerReplaysItAfterTheDaemonDied() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    loop.finish(loop.onlyLive().id(), CRITICAL_FINDING);
    var fix = loop.onlyLive();
    loop.container.dirty("api", " M src/Half.java\n");
    loop.container.exited(fix.id(), "half done", 0);
    new SpecStoreAuditPersister(new EventStore(loop.db))
        .onEvent(loop.watcherStop(fix.id(), "time limit (45m)").withId(901));
    Acting.system(() -> loop.runs.complete(fix.id(), "stopped", null));
    loop.restart();

    assertEquals(1, loop.reconciler(ReviewLoop.LATER).sweep());
    loop.settle();

    assertEquals(
        List.of("fix agent killed: time limit (45m)"),
        loop.details("review_iteration_failed"),
        "the replay says why the run ended, as the stop it replays did");
    assertEquals("escalated", loop.statusOf(fix.reviewId()));
    assertTrue(
        loop.container.commits().isEmpty(),
        "a killed fix agent's half-done work is not committed, however its stop is heard");
    assertTrue(loop.live().isEmpty(), "no re-review runs over the code the reviewer just failed");
  }

  @Test
  void aReviewerRefusedTheReposAnotherSpecsRunHoldsWaitsAndStartsWhenThatRunStops() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth", "api");
    var auth = loop.onlyLive();

    loop.built("billing", "api");

    assertEquals(
        auth.id(), loop.onlyLive().id(), "billing's reviewer was refused the repos auth holds");
    assertEquals(
        "running",
        loop.statusOf(loop.reviewOf("billing")),
        "a refused claim is a review that waits, not a reviewer that failed");
    assertTrue(loop.details("review_errored").isEmpty(), loop.details("review_errored").toString());
    assertEquals(
        1,
        loop.events("review_stage_started").size(),
        "and the room is told a stage started only once a reviewer runs for it");
    assertEquals(0, loop.reconciler(Instant::now).sweep(), "nothing to rescue while it waits");

    loop.finish(auth.id(), CLEAN_REVIEW);

    var billing = loop.onlyLive();
    assertEquals(
        "billing", billing.specId(), "auth's stop freed the repos: the waiting review starts");
    assertEquals(loop.reviewOf("billing"), billing.reviewId());
    assertEquals(2, loop.events("review_stage_started").size());

    loop.finish(billing.id(), CLEAN_REVIEW);

    assertEquals("passed", loop.statusOf(loop.reviewOf("billing")));
    assertEquals(1, loop.reviews.reviewsForSpec("billing").size(), "in the one review, unretried");
  }

  private Event replayedStop(RunStore.RunRow run) {
    return MissedStopReconciler.stopEvent(loop.runs.findById(run.id()).orElseThrow(), 0, null);
  }

  @Test
  void aReReviewWhoseReviewerNeverLaunchedGoesOnWhenItsFixAgentsStopIsReplayed() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    loop.finish(loop.onlyLive().id(), CRITICAL_FINDING);
    var fix = loop.onlyLive();
    loop.container.exited(fix.id(), "fixed and pushed", 0);
    var next =
        Acting.system(
            () -> {
              loop.runs.complete(fix.id(), "stopped", 0);
              var id = loop.reviews.createReview("auth", 2);
              loop.reviews.updateReviewStatus(id, "running");
              return id;
            });

    loop.onEvent(replayedStop(fix));

    var reviewer = loop.onlyLive();
    assertEquals("review", reviewer.role());
    assertEquals(
        next,
        reviewer.reviewId(),
        "the daemon died between writing the re-review and launching its reviewer: the replay"
            + " goes on from the review's rows instead of leaving it running forever");
    assertEquals(1, loop.reviews.stagesForReview(next).size(), "its stage rows were completed");
    assertEquals(2, loop.reviews.reviewsForSpec("auth").size());
  }

  @Test
  void aStageWhoseReviewerNeverLaunchedIsNeverJudgedOnTheStageBeforeItsLog() {
    loop = ReviewLoop.staged(tempDir, "codex", "claude-code");
    loop.built("auth");
    var first = loop.onlyLive();
    var stages = loop.reviews.stagesForReview(first.reviewId());
    loop.container.exited(first.id(), CLEAN_REVIEW, 0);
    Acting.system(
        () -> {
          loop.runs.complete(first.id(), "stopped", 0);
          loop.reviews.completeStage(stages.getFirst().id(), "passed");
          loop.reviews.startStage(stages.getLast().id(), "claude-code");
        });

    loop.onEvent(replayedStop(first));

    assertEquals(
        "running",
        loop.statusOf(first.reviewId()),
        "the second stage started after the first reviewer's run: its clean log is not that"
            + " stage's verdict, and the review does not pass unreviewed");
    var second = loop.onlyLive();
    assertEquals("claude-code", second.agent());
    assertEquals(first.reviewId(), second.reviewId());

    loop.finish(second.id(), CLEAN_REVIEW);

    assertEquals("passed", loop.statusOf(first.reviewId()));
  }

  @Test
  void aGateFailedReviewWhoseFixAgentNeverLaunchedGetsItAtTheNextSweep() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    var stage = loop.reviews.stagesForReview(reviewer.reviewId()).getFirst();
    loop.container.exited(reviewer.id(), CRITICAL_FINDING, 0);
    Acting.system(
        () -> {
          loop.runs.complete(reviewer.id(), "stopped", 0);
          loop.reviews.addFinding(
              stage.id(),
              Finding.create(
                  Finding.Severity.CRITICAL,
                  Finding.Category.SECURITY,
                  "a.java",
                  1,
                  1,
                  "Bad",
                  "Very bad",
                  "trace",
                  new Finding.Suggestion("a", "b", "c"),
                  0.95));
          loop.reviews.completeStage(stage.id(), "failed");
          loop.reviews.updateReviewStatus(reviewer.reviewId(), "failed");
          loop.specs.updateStatus("auth", SpecStatus.IN_PROGRESS);
        });
    var recorded = new SpecStoreAuditPersister(new EventStore(loop.db));
    recorded.onEvent(loop.watcherStop(reviewer.id(), null).withId(901));
    recorded.onEvent(
        Event.of(PROJECT, "auth", "review_stage_failed", Event.SAIL_AGENT, "host").withId(902));

    assertEquals(0, loop.reconciler(Instant::now).sweep(), "its reviewer only just ended");
    assertEquals(1, loop.reconciler(ReviewLoop.LATER).sweep());
    loop.settle();

    var fix = loop.onlyLive();
    assertEquals(
        "fix", fix.role(), "the review was owed a fix agent, and the sweep's replay got it");
    assertEquals(reviewer.reviewId(), fix.reviewId());
    assertEquals(0, loop.reconciler(ReviewLoop.LATER).sweep(), "a fix agent now serves the review");
  }

  @Test
  void aReDispatchThatSupersedesTheReviewWhileTheFixIsRescuedGetsNoReReviewBesideItsBuild() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    loop.finish(loop.onlyLive().id(), CRITICAL_FINDING);
    var fix = loop.onlyLive();
    loop.container.dirty("api", " M src/Fixed.java\n");
    loop.container.beforeGit(() -> loop.reviews.supersedeForSpec("auth"));

    loop.finish(fix.id(), "fixed");

    assertEquals(
        1,
        loop.reviews.reviewsForSpec("auth").size(),
        "the review was superseded while the fix agent's work was being committed: the new"
            + " attempt owns the spec, and no re-review starts");
    assertTrue(loop.live().isEmpty());
  }

  @Test
  void theRunThatStoppedIsFinishedBeforeTheRunThatFollowsReservesWhoeverHearsTheStopFirst() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    loop.container.exited(reviewer.id(), CRITICAL_FINDING, 0);

    loop.controller.onEvent(loop.watcherStop(reviewer.id(), null));
    loop.settle();

    var ended = loop.runs.findById(reviewer.id()).orElseThrow();
    assertEquals("stopped", ended.status(), "the pipeline heard the stop before the run tracker");
    assertEquals(0, ended.exitCode());
    assertEquals(
        "fix",
        loop.onlyLive().role(),
        "the fix agent passed the gate: its reviewer no longer holds the spec's repos");
    assertTrue(loop.details("review_iteration_failed").isEmpty());
  }

  @Test
  void anAgentThatEndsBetweenTheLaunchersStatusReadAndItsStampIsNotACancelledLaunch() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    loop.onLaunch(
        runId ->
            loop.container.afterAliveProbe(
                () -> {
                  loop.container.exited(runId, "fixed and pushed", 0);
                  assertTrue(loop.runs.transition(runId, "running", "stopped", 0));
                }));

    loop.finish(reviewer.id(), CRITICAL_FINDING);

    var fix = loop.runsIn("auth", "fix").getFirst();
    assertTrue(
        loop.details("review_iteration_failed").isEmpty(),
        "the launcher read the agent as running, and it ended before the stamp: that is a run"
            + " that launched and ended, not one cancelled under its launch: "
            + loop.details("review_iteration_failed"));
    assertTrue(
        loop.container.commandsContaining("kill --kill-who=all").stream()
            .noneMatch(command -> command.contains(fix.id())),
        "and nothing tore down an agent that was already gone");
    loop.onLaunch(runId -> {});

    loop.onEvent(loop.watcherStop(fix.id(), null));

    assertEquals("review", loop.onlyLive().role(), "its stop is judged as any fix agent's is");
    assertEquals(2, loop.reviews.reviewsForSpec("auth").size());
  }

  @Test
  void anAgentStartedOnARowTheReconcilerFinishedUnderASlowLaunchIsTornDown() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    loop.onLaunch(runId -> assertTrue(loop.runs.transition(runId, "running", "stopped")));

    loop.finish(reviewer.id(), CRITICAL_FINDING);

    var fix = loop.runsIn("auth", "fix").getFirst();
    assertFalse(
        loop.container.alive(fix.id()),
        "a launch slower than the reconciler's grace had its row finished with no unit to see:"
            + " the agent that then started must not run on against a row that says it is over");
    assertEquals(
        1,
        loop.details("review_iteration_failed").size(),
        "and the launch is the failure it was: " + loop.details("review_iteration_failed"));
    assertTrue(
        loop.details("review_iteration_failed").getFirst().contains("was ended while its launch"));
    assertEquals("escalated", loop.statusOf(fix.reviewId()));
  }

  @Test
  void anAgentThatEndsBeforeItsLaunchIsStampedIsJudgedOnItsStopNotLostAsACancelledLaunch() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();
    loop.onLaunch(
        runId -> {
          loop.container.exited(runId, "codex: command not found", 127);
          assertTrue(loop.runs.transition(runId, "running", "stopped", 127));
        });

    loop.finish(reviewer.id(), CRITICAL_FINDING);

    var fix = loop.runsIn("auth", "fix").getFirst();
    assertTrue(
        loop.details("review_iteration_failed").isEmpty(),
        "the fix agent launched and died at once; its watcher's stop finished the run before the"
            + " launcher stamped it, which is not a launch cancelled under it: "
            + loop.details("review_iteration_failed"));

    loop.onEvent(loop.watcherStop(fix.id(), null));

    assertEquals(List.of("fix agent failed: exit 127"), loop.details("review_iteration_failed"));
    assertEquals("escalated", loop.statusOf(fix.reviewId()));
  }
}
