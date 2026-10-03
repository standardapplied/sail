/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static ai.singlr.sail.api.ReviewScripts.CLEAN_REVIEW;
import static ai.singlr.sail.api.ReviewScripts.fixAllCarried;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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

  private static final String CRITICAL =
      """
      ```json
      {"verdicts": [], "findings": [{"severity": "CRITICAL", "category": "SECURITY", "file": "a.java",
        "line_start": 1, "line_end": 1, "title": "Bad",
        "description": "Very bad", "confidence": 0.95}]}
      ```
      """;

  private static final Supplier<Instant> LATER = () -> Instant.now().plus(Duration.ofHours(1));

  @TempDir Path tempDir;
  private ReviewLoop loop;

  @AfterEach
  void tearDown() {
    loop.close();
  }

  private static ReviewPipelineConfig stages(String... names) {
    return ReviewPipelineConfig.fromMap(
        Map.of(
            "max_iterations",
            3,
            "stages",
            List.of(names).stream()
                .map(
                    name ->
                        Map.<String, Object>of(
                            "name", name, "type", "agent", "agent", name, "gate", "no_critical"))
                .toList()));
  }

  private void spec(String id, String repo) {
    Acting.as(
        null,
        () ->
            loop.specs.create(
                new SpecStore.SpecRow(
                    id,
                    PROJECT,
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
                    List.of(repo))));
  }

  private String build(String specId) {
    var id = DateTimeUtils.newId().toString();
    Acting.system(
        () ->
            loop.runs.create(
                id,
                PROJECT,
                specId,
                ReviewLoop.HANDLE,
                "build",
                "claude-code",
                "feat/test",
                "build it",
                null,
                null,
                AgentUnit.forRun(id).logPath(),
                AgentUnit.forRun(id).unitName()));
    return id;
  }

  private Event buildStop(String specId, String runId) {
    return RunStops.of(
        Event.WellKnownData.SOURCE_WATCHER,
        PROJECT,
        specId,
        "claude-code",
        runId,
        "build",
        0,
        null);
  }

  /** Dispatches {@code specId}'s build to its end: the spec's review starts. */
  private String built(String specId) {
    return built(specId, "api");
  }

  private String built(String specId, String repo) {
    spec(specId, repo);
    var run = build(specId);
    loop.onEvent(buildStop(specId, run));
    return run;
  }

  private RunStore.RunRow onlyLive() {
    var live = loop.live();
    assertEquals(1, live.size(), "exactly one agent is running: " + live);
    return live.getFirst();
  }

  private String reviewOf(String specId) {
    return loop.reviews.latestReviewForSpec(specId).orElseThrow().id();
  }

  private String statusOf(String reviewId) {
    return loop.reviews.findReview(reviewId).orElseThrow().status();
  }

  private SpecStatus specStatus(String specId) {
    return loop.specs.findById(specId).orElseThrow().status();
  }

  private List<String> details(String type) {
    return loop.events(type).stream().map(event -> (String) event.data().get("detail")).toList();
  }

  @Test
  void aReviewerThatCompletesHasItsStopRoutedByLaneAndItsStageResolvedFromItsOwnLog() {
    loop = ReviewLoop.of(tempDir, stages("codex", "claude-code"));
    built("auth");

    var first = onlyLive();
    assertEquals("review", first.role());
    assertEquals(reviewOf("auth"), first.reviewId());
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
    assertEquals(SpecStatus.REVIEW, specStatus("auth"));

    loop.finish(first.id(), CLEAN_REVIEW);

    var second = onlyLive();
    assertEquals("stopped", loop.runs.findById(first.id()).orElseThrow().status());
    assertEquals("claude-code", second.agent());
    assertEquals(
        first.reviewId(), second.reviewId(), "the next stage's reviewer serves the review");
    assertNotEquals(first.id(), second.id(), "as its own run");
    assertEquals(
        List.of("passed", "running"),
        loop.reviews.stagesForReview(first.reviewId()).stream().map(s -> s.status()).toList());

    loop.finish(second.id(), CLEAN_REVIEW);

    assertEquals("passed", statusOf(first.reviewId()));
    assertEquals(SpecStatus.AWAITING_MERGE, specStatus("auth"));
    assertTrue(loop.live().isEmpty());
  }

  @Test
  void aReviewerPastItsTimeLimitIsKilledAndItsReviewErrorsWithTheReasonWithinTheRetryBudget() {
    loop = ReviewLoop.of(tempDir, stages("codex"));
    built("auth");
    var reconciler = loop.reconciler(Instant::now);

    for (var attempt = 1; attempt <= ReviewPipelineController.MAX_ERRORED_RETRIES; attempt++) {
      var reviewer = onlyLive();
      var review = reviewer.reviewId();

      loop.kill(reviewer.id(), "time limit (45m)");

      assertFalse(loop.container.alive(reviewer.id()), "a reaped agent is dead");
      assertEquals("failed", statusOf(review));
      assertEquals(
          "reviewer killed: time limit (45m)",
          loop.reviews.findReview(review).orElseThrow().error());
      assertEquals(attempt, details("review_errored").size());
      assertEquals("reviewer killed: time limit (45m)", details("review_errored").getLast());
      assertNull(
          loop.runs.findById(reviewer.id()).orElseThrow().exitCode(),
          "a killed run has no exit code of its own");
      assertTrue(loop.live().isEmpty(), "an errored review waits for the reconciler's retry");

      assertEquals(1, reconciler.sweep());
      loop.settle();
    }

    assertTrue(loop.live().isEmpty(), "the budget is spent: no fourth reviewer launches");
    assertEquals("escalated", statusOf(reviewOf("auth")));
    assertEquals(
        ReviewPipelineController.MAX_ERRORED_RETRIES,
        loop.reviews.reviewsForSpec("auth").size(),
        "every retry ran as the same iteration");
    assertTrue(details("review_escalated").getFirst().contains("errored in a row"));
  }

  @Test
  void aStalledReviewerErrorsItsReviewWithTheStallAsTheReason() {
    loop = ReviewLoop.of(tempDir, stages("codex"));
    built("auth");
    var reviewer = onlyLive();

    loop.kill(reviewer.id(), "stall (20m)");

    assertFalse(loop.container.alive(reviewer.id()));
    assertEquals(List.of("reviewer killed: stall (20m)"), details("review_errored"));
    assertEquals(SpecStatus.REVIEW, specStatus("auth"));
  }

  @Test
  void aFixAgentThatCompletesIsFollowedByTheReReviewAsTheNextIterationWithItsOwnRun() {
    loop = ReviewLoop.of(tempDir, stages("codex"));
    built("auth");
    var reviewer = onlyLive();

    loop.finish(reviewer.id(), CRITICAL);

    var fix = onlyLive();
    assertEquals("fix", fix.role());
    assertEquals("claude-code", fix.agent(), "the spec's own agent fixes what the reviewer found");
    assertEquals(reviewer.reviewId(), fix.reviewId(), "the fix run serves the review it answers");
    assertEquals("claude/fix-" + fix.id(), fix.principal());
    assertEquals("failed", statusOf(reviewer.reviewId()));
    assertEquals(SpecStatus.IN_PROGRESS, specStatus("auth"));
    assertTrue(fix.task().contains("wait for or watch CI"), "the fix task ends at a pushed commit");

    loop.finish(fix.id(), "fixed and pushed");

    var second = onlyLive();
    assertEquals("review", second.role());
    assertNotEquals(reviewer.reviewId(), second.reviewId());
    assertEquals(2, loop.reviews.findReview(second.reviewId()).orElseThrow().iteration());
    assertEquals(SpecStatus.REVIEW, specStatus("auth"));
    assertTrue(loop.container.commits().isEmpty(), "a clean tree needs no rescue");

    loop.finish(second.id(), fixAllCarried(second.task()));

    assertEquals("passed", statusOf(second.reviewId()));
    assertEquals(SpecStatus.AWAITING_MERGE, specStatus("auth"));
    assertEquals(
        List.of("build", "review", "fix", "review"),
        loop.runs.listForSpec("auth").reversed().stream().map(RunStore.RunRow::role).toList());
  }

  @Test
  void aFixAgentPastItsTimeLimitIsKilledNothingOfItsLandsAndTheRoomIsToldWhy() {
    loop = ReviewLoop.of(tempDir, stages("codex"));
    built("auth");
    loop.finish(onlyLive().id(), CRITICAL);
    var fix = onlyLive();
    loop.container.dirty("api", " M src/Half.java\n");

    loop.kill(fix.id(), "time limit (45m)");

    assertFalse(loop.container.alive(fix.id()), "a reaped agent is dead");
    assertTrue(
        loop.container.commits().isEmpty(),
        "nothing lands after the kill: a killed fix agent's half-done work is not committed");
    assertEquals(List.of("fix agent killed: time limit (45m)"), details("review_iteration_failed"));
    assertEquals("escalated", statusOf(fix.reviewId()));
    assertEquals(SpecStatus.REVIEW, specStatus("auth"));
    assertTrue(
        details("review_escalated").getFirst().contains("fix agent killed: time limit (45m)"));
    assertTrue(loop.live().isEmpty(), "no re-review runs over the code the reviewer just failed");
    assertEquals(1, loop.reviews.reviewsForSpec("auth").size());
  }

  @Test
  void aFixAgentThatEndsWithADirtyTreeHasItsWorkRescuedOnItsStop() {
    loop = ReviewLoop.of(tempDir, stages("codex"));
    built("auth");
    loop.finish(onlyLive().id(), CRITICAL);
    var fix = onlyLive();
    loop.container.dirty("api", " M src/Fixed.java\n");

    loop.finish(fix.id(), "fixed, forgot to commit");

    assertEquals(1, loop.container.commits().size());
    assertTrue(loop.container.commits().getFirst().contains("address 1 review finding"));
    var guardrail = loop.events(Event.WellKnownTypes.GUARDRAIL_TRIGGERED).getFirst();
    assertTrue(guardrail.data().get("reason").toString().contains("src/Fixed.java"));
    assertEquals("review", onlyLive().role(), "the re-review judges the rescued branch");
  }

  @Test
  void aDaemonRestartWhileAReviewerRunsReArmsItsWatcherAndFailsNothing() {
    loop = ReviewLoop.of(tempDir, stages("codex"));
    built("auth");
    var reviewer = onlyLive();
    var review = reviewer.reviewId();

    loop.restart();

    assertEquals("running", statusOf(review), "nothing at start marks a running review failed");
    assertEquals("running", loop.runs.findById(reviewer.id()).orElseThrow().status());
    assertEquals(0, loop.reconciler(LATER).sweep(), "the agent is alive: no stop to replay");
    assertEquals(
        1, loop.rearmer().rearm(), "the unit outlived the daemon; its watcher is re-armed");
    var watchers = loop.watcherCommandsOf(reviewer.id());
    assertEquals(2, watchers.size());
    assertTrue(watchers.getLast().contains("--max-duration 45m"), watchers.getLast());

    loop.finish(reviewer.id(), CLEAN_REVIEW);

    assertEquals("passed", statusOf(review));
    assertEquals(SpecStatus.AWAITING_MERGE, specStatus("auth"));
  }

  @Test
  void aReviewerThatExitedWhileTheDaemonWasDownHasItsStopPublishedAtStart() {
    loop = ReviewLoop.of(tempDir, stages("codex"));
    built("auth");
    var reviewer = onlyLive();
    loop.container.exited(reviewer.id(), CLEAN_REVIEW, 0);

    loop.restart();
    var replayed = loop.reconciler(LATER).sweep();
    loop.settle();

    assertEquals(1, replayed);
    var stop = loop.events(Event.WellKnownTypes.AGENT_SESSION_STOPPED).getLast();
    assertEquals("reconcile", stop.data().get("source"));
    assertEquals("review", stop.data().get("run_role"));
    assertEquals(reviewer.id(), stop.data().get("run_id"));
    assertEquals("stopped", loop.runs.findById(reviewer.id()).orElseThrow().status());
    assertEquals("passed", statusOf(reviewer.reviewId()));
    assertEquals(SpecStatus.AWAITING_MERGE, specStatus("auth"));
  }

  @Test
  void aFixAgentThatExitsUnwatchedIsReconciledAsABuildIsAndTheLoopGoesOn() {
    loop = ReviewLoop.of(tempDir, stages("codex"));
    built("auth");
    loop.finish(onlyLive().id(), CRITICAL);
    var fix = onlyLive();
    loop.container.exited(fix.id(), "fixed and pushed", 0);

    var replayed = loop.reconciler(LATER).sweep();
    loop.settle();

    assertEquals(1, replayed, "the reconciler sees a fix run as it sees a build");
    assertEquals("stopped", loop.runs.findById(fix.id()).orElseThrow().status());
    var second = onlyLive();
    assertEquals("review", second.role(), "the pipeline advances on the reconciled stop");
    assertEquals(2, loop.reviews.findReview(second.reviewId()).orElseThrow().iteration());
  }

  @Test
  void twoSpecsPipelinesInOneContainerEachAdvanceOnlyOnTheirOwnRunsStop() {
    loop = ReviewLoop.of(tempDir, stages("codex"));
    built("auth", "api");
    built("billing", "web");

    var live = loop.live();
    assertEquals(2, live.size());
    var auth = live.stream().filter(run -> "auth".equals(run.specId())).findFirst().orElseThrow();
    var billing =
        live.stream().filter(run -> "billing".equals(run.specId())).findFirst().orElseThrow();
    for (var paths :
        List.<java.util.function.Function<AgentUnit, String>>of(
            AgentUnit::unitName,
            AgentUnit::logPath,
            AgentUnit::taskPath,
            AgentUnit::guardrailTriggerPath)) {
      assertNotEquals(
          paths.apply(AgentUnit.forRun(auth.id())), paths.apply(AgentUnit.forRun(billing.id())));
    }
    assertNotEquals(loop.watcherCommandOf(auth.id()), loop.watcherCommandOf(billing.id()));
    assertTrue(loop.watcherCommandOf(auth.id()).contains("sail-watch-" + auth.id()));

    loop.finish(auth.id(), CRITICAL);

    assertEquals("failed", statusOf(auth.reviewId()));
    assertEquals("running", statusOf(billing.reviewId()), "billing's review saw none of it");
    assertTrue(loop.container.alive(billing.id()));

    loop.finish(billing.id(), CLEAN_REVIEW);

    assertEquals("passed", statusOf(billing.reviewId()));
    assertEquals(SpecStatus.AWAITING_MERGE, specStatus("billing"));
    assertEquals(SpecStatus.IN_PROGRESS, specStatus("auth"), "auth is with its fix agent");
  }

  @Test
  void aPlainReviewerAndAStreamingOneAreEachJudgedOnTheirOwnRunsLog() {
    loop = ReviewLoop.of(tempDir, stages("codex", "claude-code"));
    built("auth");
    var codex = onlyLive();

    loop.finish(codex.id(), CLEAN_REVIEW);

    var claude = onlyLive();
    var streamed =
        String.join(
            "\n",
            YamlUtil.dumpJson(
                Map.of(
                    "type",
                    "assistant",
                    "message",
                    Map.of("content", List.of(Map.of("type", "text", "text", CRITICAL))))),
            YamlUtil.dumpJson(Map.of("type", "result", "result", CLEAN_REVIEW)));
    loop.finish(claude.id(), streamed);

    assertEquals(
        "passed",
        statusOf(codex.reviewId()),
        "the streamed reviewer's verdict is its final result, and the plain reviewer's log was"
            + " never read for it");
    assertEquals(
        CLEAN_REVIEW, loop.container.file(AgentUnit.forRun(codex.id()).logPath()), "one log each");
    assertTrue(loop.reviews.findingsForReview(codex.reviewId()).isEmpty());
  }

  @Test
  void aBuildsStopWhileTheSpecsFixAgentRunsIsNotTakenAsTheFixAgentsStop() {
    loop = ReviewLoop.of(tempDir, stages("codex"));
    var build = built("auth");
    loop.finish(onlyLive().id(), CRITICAL);
    var fix = onlyLive();
    assertEquals(SpecStatus.IN_PROGRESS, specStatus("auth"));

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
    assertEquals(fix.id(), onlyLive().id(), "and the fix agent is still the run the loop awaits");
    assertEquals(SpecStatus.IN_PROGRESS, specStatus("auth"));

    loop.finish(fix.id(), "fixed and pushed");

    assertEquals("review", onlyLive().role());
    assertEquals(2, loop.reviews.reviewsForSpec("auth").size());
  }

  @Test
  void aReviewLanesLimitsDefaultTo45MinutesAndAnEditAppliesToTheNextRunOnly() {
    loop = ReviewLoop.wired(tempDir, ReviewLoop.YAML);
    built("auth");
    var reviewer = onlyLive();
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
    loop.finish(reviewer.id(), CRITICAL);

    var fix = onlyLive();
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
    loop = ReviewLoop.of(tempDir, stages("codex"));
    built("auth");
    var reviewer = onlyLive();
    loop.container.exited(reviewer.id(), "", 143);
    Acting.system(() -> loop.runs.complete(reviewer.id(), "stopped", null));

    loop.onEvent(
        StopOperations.cancelEvent(
            loop.runs.findById(reviewer.id()).orElseThrow(),
            Event.WellKnownData.SOURCE_OPERATOR,
            "uday"));

    assertEquals("escalated", statusOf(reviewer.reviewId()));
    assertTrue(details("review_escalated").getFirst().contains("reviewer stopped by an operator"));
    assertEquals(SpecStatus.REVIEW, specStatus("auth"));
    assertTrue(loop.live().isEmpty(), "a person's stop is not retried over");
  }

  @Test
  void theWatchersStopOfARunAnOperatorIsStoppingIsLeftToTheOperatorsCancel() {
    loop = ReviewLoop.of(tempDir, stages("codex"));
    built("auth");
    loop.finish(onlyLive().id(), CRITICAL);
    var fix = onlyLive();
    Acting.system(() -> assertTrue(loop.runs.transition(fix.id(), "running", "stopping")));
    loop.container.exited(fix.id(), "half done", 0);

    loop.onEvent(loop.watcherStop(fix.id(), null));

    assertEquals(
        1,
        loop.reviews.reviewsForSpec("auth").size(),
        "the unit died under the operator's halt: its exit is not a finished fix to re-review");
    assertTrue(loop.container.commits().isEmpty());

    Acting.system(() -> assertTrue(loop.runs.transition(fix.id(), "stopping", "stopped")));
    loop.onEvent(
        StopOperations.cancelEvent(
            loop.runs.findById(fix.id()).orElseThrow(),
            Event.WellKnownData.SOURCE_OPERATOR,
            "uday"));

    assertEquals("escalated", statusOf(fix.reviewId()));
    assertTrue(details("review_escalated").getFirst().contains("fix agent stopped by an operator"));
    assertTrue(loop.live().isEmpty());
  }

  @Test
  void anOperatorsStopStillEscalatesAReviewTheWatchersStopAlreadyErrored() {
    loop = ReviewLoop.of(tempDir, stages("codex"));
    built("auth");
    var reviewer = onlyLive();
    loop.exit(reviewer.id(), "", 143);
    assertEquals("failed", statusOf(reviewer.reviewId()));

    loop.onEvent(
        StopOperations.cancelEvent(
            loop.runs.findById(reviewer.id()).orElseThrow(),
            Event.WellKnownData.SOURCE_OPERATOR,
            "uday"));

    assertEquals("escalated", statusOf(reviewer.reviewId()));
    assertEquals(0, loop.reconciler(Instant::now).sweep(), "an escalated review is never retried");
  }

  @Test
  void aReviewerNeverStartsBesideAnotherSpecsRunOverTheSameRepos() {
    loop = ReviewLoop.of(tempDir, stages("codex"));
    built("auth", "api");
    var auth = onlyLive();

    built("billing", "api");

    assertEquals(auth.id(), onlyLive().id(), "billing's reviewer was refused the repos auth holds");
    assertEquals("failed", statusOf(reviewOf("billing")));
    assertTrue(
        details("review_errored").getFirst().startsWith("reviewer could not start:"),
        details("review_errored").toString());
    assertEquals("running", statusOf(auth.reviewId()));
  }

  private Event replayedStop(RunStore.RunRow run) {
    return MissedStopReconciler.stopEvent(loop.runs.findById(run.id()).orElseThrow(), 0);
  }

  @Test
  void aReReviewWhoseReviewerNeverLaunchedGoesOnWhenItsFixAgentsStopIsReplayed() {
    loop = ReviewLoop.of(tempDir, stages("codex"));
    built("auth");
    loop.finish(onlyLive().id(), CRITICAL);
    var fix = onlyLive();
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

    var reviewer = onlyLive();
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
    loop = ReviewLoop.of(tempDir, stages("codex", "claude-code"));
    built("auth");
    var first = onlyLive();
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
        statusOf(first.reviewId()),
        "the second stage started after the first reviewer's run: its clean log is not that"
            + " stage's verdict, and the review does not pass unreviewed");
    var second = onlyLive();
    assertEquals("claude-code", second.agent());
    assertEquals(first.reviewId(), second.reviewId());

    loop.finish(second.id(), CLEAN_REVIEW);

    assertEquals("passed", statusOf(first.reviewId()));
  }

  @Test
  void aGateFailedReviewWhoseFixAgentNeverLaunchedGetsItAtTheNextSweep() {
    loop = ReviewLoop.of(tempDir, stages("codex"));
    built("auth");
    var reviewer = onlyLive();
    var stage = loop.reviews.stagesForReview(reviewer.reviewId()).getFirst();
    loop.container.exited(reviewer.id(), CRITICAL, 0);
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
    assertEquals(1, loop.reconciler(LATER).sweep());
    loop.settle();

    var fix = onlyLive();
    assertEquals(
        "fix", fix.role(), "the review was owed a fix agent, and the sweep's replay got it");
    assertEquals(reviewer.reviewId(), fix.reviewId());
    assertEquals(0, loop.reconciler(LATER).sweep(), "a fix agent now serves the review");
  }

  @Test
  void aReDispatchThatSupersedesTheReviewWhileTheFixIsRescuedGetsNoReReviewBesideItsBuild() {
    loop = ReviewLoop.of(tempDir, stages("codex"));
    built("auth");
    loop.finish(onlyLive().id(), CRITICAL);
    var fix = onlyLive();
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
    loop = ReviewLoop.of(tempDir, stages("codex"));
    built("auth");
    var reviewer = onlyLive();
    loop.container.exited(reviewer.id(), CRITICAL, 0);

    loop.controller.onEvent(loop.watcherStop(reviewer.id(), null));
    loop.settle();

    var ended = loop.runs.findById(reviewer.id()).orElseThrow();
    assertEquals("stopped", ended.status(), "the pipeline heard the stop before the run tracker");
    assertEquals(0, ended.exitCode());
    assertEquals(
        "fix",
        onlyLive().role(),
        "the fix agent passed the gate: its reviewer no longer holds the spec's repos");
    assertTrue(details("review_iteration_failed").isEmpty());
  }
}
