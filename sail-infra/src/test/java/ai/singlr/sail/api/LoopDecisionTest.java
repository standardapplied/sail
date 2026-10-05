/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static ai.singlr.sail.api.LoopRows.AN_AGENT_THEN_A_PERSON;
import static ai.singlr.sail.api.LoopRows.ENDED;
import static ai.singlr.sail.api.LoopRows.NODE;
import static ai.singlr.sail.api.LoopRows.RECORDED;
import static ai.singlr.sail.api.LoopRows.REVIEW;
import static ai.singlr.sail.api.LoopRows.ended;
import static ai.singlr.sail.api.LoopRows.facts;
import static ai.singlr.sail.api.LoopRows.finding;
import static ai.singlr.sail.api.LoopRows.review;
import static ai.singlr.sail.api.LoopRows.run;
import static ai.singlr.sail.api.LoopRows.stage;
import static ai.singlr.sail.api.LoopRows.staged;
import static ai.singlr.sail.api.LoopRows.stages;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The loop's table (ARCHITECTURE.md, "The loop machine"), one case a row: for these facts and this
 * trigger, {@link LoopDecision#next} names this step. Nothing here touches a store, a bus or a
 * container.
 */
class LoopDecisionTest {

  private static final ReviewStore.ReviewRow RUNNING = review("running");
  private static final ReviewStore.ReviewRow GATE_FAILED = review("failed");
  private static final ReviewStore.ReviewRow ERRORED = review(2, "failed", "reviewer died", null);
  private static final RunStore.RunRow BUILD = ended("build-1", "build", null);
  private static final RunStore.RunRow REVIEWER = ended("reviewer-1", "review", REVIEW);
  private static final RunStore.RunRow FIXER = ended("fix-1", "fix", REVIEW);
  private static final ReviewPipelineConfig TWO_AGENTS =
      ReviewPipelineConfig.fromMap(
          Map.of(
              "stages",
              List.of(
                  Map.<String, Object>of(
                      "name", "codex", "type", "agent", "agent", "codex", "gate", "no_critical"),
                  Map.<String, Object>of(
                      "name",
                      "claude",
                      "type",
                      "agent",
                      "agent",
                      "claude-code",
                      "gate",
                      "all_clear"))));
  private static final ReviewPipelineConfig UNNAMED_REVIEWER =
      ReviewPipelineConfig.mandatoryDefault();
  private static final Finding CRITICAL = finding("f1", Finding.Severity.CRITICAL);
  private static final Finding HIGH = finding("f2", Finding.Severity.HIGH);
  private static final LoopStep NOTHING = new LoopStep.Nothing();
  private static final LoopTrigger GO_ON = new LoopTrigger.GoOn();
  private static final LoopTrigger FREED = new LoopTrigger.Freed();
  private static final LoopTrigger REVIEW_RUNNING = new LoopTrigger.ReviewRunning();

  private record Row(String name, LoopRows.Facts facts, LoopTrigger trigger, LoopStep step) {

    @Override
    public String toString() {
      return name;
    }
  }

  private static Row row(String name, LoopRows.Facts facts, LoopTrigger trigger, LoopStep step) {
    return new Row(name, facts, trigger, step);
  }

  private static LoopTrigger buildEnded(String runId, Integer exitCode) {
    return new LoopTrigger.BuildEnded(runId, exitCode);
  }

  private static LoopTrigger reviewerEnded(RunStore.RunRow run) {
    return new LoopTrigger.ReviewerEnded(run, Optional.empty());
  }

  private static LoopTrigger fixEnded(RunStore.RunRow run) {
    return new LoopTrigger.FixEnded(run, Optional.empty());
  }

  private static LoopStep escalate(ReviewStore.ReviewRow review, String reason) {
    return new LoopStep.Escalate(review, reason);
  }

  /** A review its reviewer just ran the first stage of: the run its loop waits on. */
  private static LoopRows.Facts reviewed() {
    return facts().review(RUNNING, stages("running", "pending")).serving(REVIEWER);
  }

  /** A gate-failed review its fix agent just worked: the run its loop waits on. */
  private static LoopRows.Facts fixed() {
    return facts().review(GATE_FAILED, stages("failed", "pending")).serving(FIXER);
  }

  /** A gate-failed review nothing serves, with one finding open. */
  private static LoopRows.Facts owedAFix(ReviewStore.ReviewRow review) {
    return facts().review(review, stages("failed", "pending")).open(HIGH);
  }

  private static LoopFacts.Aged aged(Finding finding, int age) {
    return new LoopFacts.Aged(finding, age);
  }

  private static Stream<Row> buildEnded() {
    var replaced = facts().newestLoopRun(run("build-2", "build", null));
    return Stream.of(
        row("A1 a spec this box does not hold", facts().spec(null), buildEnded(null, 0), NOTHING),
        row(
            "A1 a spec that is not the loop's",
            facts().spec(SpecStatus.CANCELLED),
            buildEnded(null, 0),
            NOTHING),
        row(
            "A1 a spec in awaiting_merge is not the loop's either",
            facts().spec(SpecStatus.AWAITING_MERGE),
            buildEnded(null, 0),
            NOTHING),
        row(
            "A1 a failed build of a spec that is not the loop's is not said to have failed",
            facts().spec(SpecStatus.CANCELLED),
            buildEnded(null, 2),
            NOTHING),
        row(
            "A1 a spec in progress is the loop's",
            facts().spec(SpecStatus.IN_PROGRESS),
            buildEnded(null, 0),
            new LoopStep.StartReview(1)),
        row(
            "A2 a build a newer run replaced starts no review beside it",
            replaced,
            buildEnded("build-1", 0),
            NOTHING),
        row(
            "A2 a replaced build that failed is not said to have: the loop only goes on",
            facts().review(ERRORED, List.of()).newestLoopRun(run("build-2", "build", null)),
            buildEnded("build-1", 1),
            new LoopStep.StartReview(2)),
        row(
            "A2 the newest run of the loop is not replaced",
            facts().newestLoopRun(BUILD),
            buildEnded("build-1", 0),
            new LoopStep.StartReview(1)),
        row(
            "A2 a stop naming no run this box holds is not replaced",
            replaced,
            buildEnded(null, 0),
            new LoopStep.StartReview(1)),
        row("A3 a non-zero exit", facts(), buildEnded(null, 2), new LoopStep.SayBuildFailed(2)),
        row(
            "A3 a non-zero exit is said before the review is looked at",
            facts().review(ERRORED, List.of()),
            buildEnded(null, 2),
            new LoopStep.SayBuildFailed(2)),
        row("A3 an exit of zero is no failure", facts(), buildEnded(null, 0), start(1)),
        row("A3 a stop with no exit code is no failure", facts(), buildEnded(null, null), start(1)),
        row(
            "A4 a build whose spec has a review goes on from the rows",
            facts().review(ERRORED, List.of()),
            buildEnded(null, 0),
            new LoopStep.StartReview(2)),
        row("A5 a pipeline with stages", facts(), buildEnded(null, 0), start(1)),
        row(
            "A6 no pipeline",
            facts().pipeline(new LoopFacts.Pipeline.None()),
            buildEnded(null, 0),
            new LoopStep.ParkForAPerson()),
        row(
            "A7 a pipeline that cannot be read",
            facts().pipeline(new LoopFacts.Pipeline.Unreadable("bad yaml")),
            buildEnded(null, 0),
            new LoopStep.EscalateNew(1, ReviewNarration.pipelineUnreadable("bad yaml"))));
  }

  private static LoopStep start(int iteration) {
    return new LoopStep.StartReview(iteration);
  }

  private static Stream<Row> freed() {
    var waiting = review(1, "running", null, "holder-1");
    var anotherBoxs = run("build-1", "node-b", "build", null, "stopped", ENDED);
    return Stream.of(
        row(
            "W1 a wait another box drives is that box's to take",
            facts().review(waiting, List.of()).holderEnded(true).newestLoopRun(anotherBoxs),
            FREED,
            NOTHING),
        row(
            "W1 a spec with no run of its loop is driven by no box",
            facts().review(waiting, List.of()).holderEnded(true),
            FREED,
            NOTHING),
        row(
            "W2 an errored review keeps to the reconciler's pace",
            facts().newestLoopRun(BUILD).review(ERRORED, List.of()),
            FREED,
            NOTHING),
        row(
            "W2 a review whose launch was cut short is the reconciler's to rescue",
            facts().newestLoopRun(BUILD).review(RUNNING, List.of()),
            FREED,
            NOTHING),
        row(
            "W2 a review whose reviewer ended waits for that run's own stop",
            reviewed(),
            FREED,
            NOTHING),
        row(
            "W3 a wait whose holder still runs",
            facts().newestLoopRun(BUILD).review(waiting, List.of()),
            FREED,
            NOTHING),
        row(
            "W3 a wait whose holder ended takes the step it held",
            facts().newestLoopRun(BUILD).review(waiting, List.of()).holderEnded(true),
            FREED,
            new LoopStep.Resume(waiting)),
        row(
            "W3 a wait of a spec that is not the loop's is left alone",
            facts()
                .newestLoopRun(BUILD)
                .spec(SpecStatus.CANCELLED)
                .review(waiting, List.of())
                .holderEnded(true),
            FREED,
            NOTHING));
  }

  private static Stream<Row> goOn() {
    var waiting = review(1, "running", null, "holder-1");
    var waitingToFix = review(1, "failed", null, "holder-1");
    var renamed = List.of(stage("the-stage-before", "agent", "pending"));
    var retyped = List.of(stage("codex", "agent", "passed"), stage("approve", "agent", "pending"));
    var tooMany =
        List.of(
            stage("codex", "agent", "passed"),
            stage("approve", "human", "passed"),
            stage("a-third", "agent", "pending"));
    return Stream.of(
        row(
            "G0 a spec that is not the loop's",
            facts().spec(SpecStatus.CANCELLED).review(ERRORED, List.of()),
            GO_ON,
            NOTHING),
        row("G1 no review", facts(), GO_ON, NOTHING),
        row("G1 a review that passed", facts().review(review("passed"), List.of()), GO_ON, NOTHING),
        row(
            "G1 a review that was escalated",
            facts().review(review("escalated"), List.of()),
            GO_ON,
            NOTHING),
        row(
            "G1 a review a live run serves",
            facts().review(ERRORED, List.of()).serving(run("reviewer-1", "review", REVIEW)),
            GO_ON,
            NOTHING),
        row(
            "G1 a review that waits on a person",
            facts().review(RUNNING, stages("passed", "running")),
            GO_ON,
            NOTHING),
        row("G1 a review whose reviewer ended is owed that run's stop", reviewed(), GO_ON, NOTHING),
        row("G1 a review whose fix agent ended is owed that run's stop", fixed(), GO_ON, NOTHING),
        row(
            "G1 a review that ended is not looked at again, whatever became of its pipeline",
            facts().review(review("passed"), List.of()).pipeline(new LoopFacts.Pipeline.None()),
            GO_ON,
            NOTHING),
        row(
            "G1 a review owed its run's stop is left to it, whatever became of its pipeline",
            reviewed().pipeline(new LoopFacts.Pipeline.None()),
            GO_ON,
            NOTHING),
        row(
            "G2 a wait holds, whatever became of the pipeline, while its holder runs",
            facts().review(waiting, List.of()).pipeline(new LoopFacts.Pipeline.None()),
            GO_ON,
            NOTHING),
        row(
            "G2 a wait whose holder still runs",
            facts().review(waiting, List.of()),
            GO_ON,
            NOTHING),
        row(
            "G6 a wait whose holder ended is due the step it held",
            facts().review(waiting, List.of()).holderEnded(true),
            GO_ON,
            new LoopStep.Resume(waiting)),
        row(
            "G14 a fix that waited is launched once its holder ended",
            owedAFix(waitingToFix).holderEnded(true),
            GO_ON,
            new LoopStep.LaunchFix(waitingToFix, List.of(HIGH))),
        row(
            "G2 a fix that waits is not launched while its holder runs",
            owedAFix(waitingToFix),
            GO_ON,
            NOTHING),
        row(
            "G4 an errored review out of retries",
            facts().review(ERRORED, List.of()).erroredAttempts(3),
            GO_ON,
            escalate(ERRORED, ReviewNarration.erroredOut(3, 2))),
        row(
            "G4 out of retries is said before the pipeline is looked at",
            facts()
                .review(ERRORED, List.of())
                .erroredAttempts(3)
                .pipeline(new LoopFacts.Pipeline.None()),
            GO_ON,
            escalate(ERRORED, ReviewNarration.erroredOut(3, 2))),
        row(
            "G5 an errored review within its retries runs its iteration again",
            facts().review(ERRORED, List.of()).erroredAttempts(2),
            GO_ON,
            new LoopStep.StartReview(2)),
        row(
            "G5 P an errored review is not retried under a pipeline with no stages",
            facts().review(ERRORED, List.of()).pipeline(new LoopFacts.Pipeline.None()),
            GO_ON,
            escalate(ERRORED, ReviewNarration.noStages())),
        row(
            "G6 a running review nothing serves",
            facts().review(RUNNING, List.of()),
            GO_ON,
            new LoopStep.Resume(RUNNING)),
        row(
            "G6 a legacy pending review nothing serves",
            facts().review(review("pending"), List.of()),
            GO_ON,
            new LoopStep.Resume(review("pending"))),
        row(
            "G6 P a review whose stage was renamed",
            facts().review(RUNNING, renamed),
            GO_ON,
            escalate(RUNNING, ReviewNarration.pipelineChanged("the-stage-before"))),
        row(
            "G6 P a review whose stage changed kind",
            facts().review(RUNNING, retyped),
            GO_ON,
            escalate(RUNNING, ReviewNarration.pipelineChanged("approve"))),
        row(
            "G6 P a review that holds a stage the pipeline no longer has",
            facts().review(RUNNING, tooMany),
            GO_ON,
            escalate(RUNNING, ReviewNarration.pipelineChanged("a-third"))),
        row(
            "G6 P a review whose pipeline cannot be read",
            facts().review(RUNNING, renamed).pipeline(new LoopFacts.Pipeline.Unreadable("bad")),
            GO_ON,
            escalate(RUNNING, ReviewNarration.pipelineUnreadable("bad"))),
        row(
            "G11 a blocking finding of the failed stage that outlived its fixes",
            owedAFix(GATE_FAILED).failedStage(aged(HIGH, 9), aged(CRITICAL, 2)),
            GO_ON,
            escalate(GATE_FAILED, ReviewNarration.stuckOn(CRITICAL.title(), 2))),
        row(
            "G11 a blocking finding younger than the limit is not stuck",
            owedAFix(GATE_FAILED).failedStage(aged(CRITICAL, 1)),
            GO_ON,
            new LoopStep.LaunchFix(GATE_FAILED, List.of(HIGH))),
        row(
            "G11 a finding the gate lets through may age freely",
            owedAFix(GATE_FAILED).failedStage(aged(HIGH, 9)),
            GO_ON,
            new LoopStep.LaunchFix(GATE_FAILED, List.of(HIGH))),
        row(
            "G11 with no stage failed nothing is weighed",
            facts()
                .review(GATE_FAILED, stages("pending", "pending"))
                .open(HIGH)
                .failedStage(aged(CRITICAL, 9)),
            GO_ON,
            new LoopStep.LaunchFix(GATE_FAILED, List.of(HIGH))),
        row(
            "G11 a finding is weighed against the gate of the stage that failed, not the first",
            facts()
                .pipeline(staged(TWO_AGENTS))
                .review(
                    GATE_FAILED,
                    List.of(stage("codex", "agent", "passed"), stage("claude", "agent", "failed")))
                .open(HIGH)
                .failedStage(aged(HIGH, 9)),
            GO_ON,
            escalate(GATE_FAILED, ReviewNarration.stuckOn(HIGH.title(), 9))),
        row(
            "G11 stuck is said before the iterations are counted",
            owedAFix(review(3, "failed", null, null)).failedStage(aged(CRITICAL, 2)),
            GO_ON,
            escalate(
                review(3, "failed", null, null), ReviewNarration.stuckOn(CRITICAL.title(), 2))),
        row(
            "G12 the last iteration the project allows",
            owedAFix(review(3, "failed", null, null)),
            GO_ON,
            escalate(review(3, "failed", null, null), ReviewNarration.iterationsExhausted(3))),
        row(
            "G12 the iteration before the last is fixed",
            owedAFix(review(2, "failed", null, null)),
            GO_ON,
            new LoopStep.LaunchFix(review(2, "failed", null, null), List.of(HIGH))),
        row(
            "G12 the last iteration with nothing left open is still the last",
            facts().review(review(3, "failed", null, null), stages("failed", "pending")),
            GO_ON,
            escalate(review(3, "failed", null, null), ReviewNarration.iterationsExhausted(3))),
        row(
            "G13 a failed gate with nothing left open is reviewed again",
            facts().review(GATE_FAILED, stages("failed", "pending")),
            GO_ON,
            new LoopStep.StartReview(2)),
        row(
            "G14 a failed gate with findings open",
            owedAFix(GATE_FAILED).open(CRITICAL, HIGH),
            GO_ON,
            new LoopStep.LaunchFix(GATE_FAILED, List.of(CRITICAL, HIGH))),
        row(
            "G14 P a fix is not launched under a pipeline that changed",
            facts().review(GATE_FAILED, renamed).open(HIGH),
            GO_ON,
            escalate(GATE_FAILED, ReviewNarration.pipelineChanged("the-stage-before"))));
  }

  private static Stream<Row> reviewRunning() {
    return Stream.of(
        row("G6b no review: a re-dispatch superseded it", facts(), REVIEW_RUNNING, NOTHING),
        row(
            "G9b a review just written is in its first stage",
            facts().review(RUNNING, List.of()),
            REVIEW_RUNNING,
            new LoopStep.LaunchReviewer(RUNNING, 0, "codex")),
        row(
            "G9b a stage that did not pass is the one the review is in",
            facts().review(RUNNING, stages("failed", "pending")),
            REVIEW_RUNNING,
            new LoopStep.LaunchReviewer(RUNNING, 0, "codex")),
        row(
            "G9b a stage that names no agent is reviewed by the project's roster reviewer",
            facts()
                .review(RUNNING, List.of())
                .pipeline(new LoopFacts.Pipeline.Staged(UNNAMED_REVIEWER, "codex")),
            REVIEW_RUNNING,
            new LoopStep.LaunchReviewer(RUNNING, 0, "codex")),
        row(
            "G9b a stage's own agent reviews it, whoever the roster would name",
            facts()
                .review(RUNNING, List.of())
                .pipeline(new LoopFacts.Pipeline.Staged(AN_AGENT_THEN_A_PERSON, "claude-code")),
            REVIEW_RUNNING,
            new LoopStep.LaunchReviewer(RUNNING, 0, "codex")),
        row(
            "G9 an agent stage no reviewer resolves for",
            facts().review(RUNNING, List.of()).pipeline(staged(UNNAMED_REVIEWER)),
            REVIEW_RUNNING,
            new LoopStep.ErrorReview(
                RUNNING,
                0,
                "no reviewer agent resolved; set stages[].agent or agent.install in sail.yaml",
                false)),
        row(
            "G7 a person's stage not yet opened",
            facts().review(RUNNING, stages("passed", "pending")),
            REVIEW_RUNNING,
            new LoopStep.AwaitAPerson(RUNNING, 1)),
        row(
            "G7 a person's stage whose row is not written yet",
            facts().review(RUNNING, List.of(stage("codex", "agent", "passed"))),
            REVIEW_RUNNING,
            new LoopStep.AwaitAPerson(RUNNING, 1)),
        row(
            "G8 a person's stage already open",
            facts().review(RUNNING, stages("passed", "running")),
            REVIEW_RUNNING,
            NOTHING),
        row(
            "G6c no stage is started for a spec someone took while the last step ran",
            facts().spec(SpecStatus.CANCELLED).review(RUNNING, List.of()),
            REVIEW_RUNNING,
            NOTHING),
        row(
            "G6c nor is a person's stage opened for it",
            facts().spec(SpecStatus.CANCELLED).review(RUNNING, stages("passed", "pending")),
            REVIEW_RUNNING,
            NOTHING),
        row(
            "G10 a review whose every stage passed ends, whoever moved its spec meanwhile",
            facts().spec(SpecStatus.CANCELLED).review(RUNNING, stages("passed", "passed")),
            REVIEW_RUNNING,
            new LoopStep.Pass(RUNNING)),
        row(
            "P a review its pipeline can no longer judge is a person's, whoever moved its spec",
            facts()
                .spec(SpecStatus.CANCELLED)
                .review(RUNNING, List.of())
                .pipeline(new LoopFacts.Pipeline.None()),
            REVIEW_RUNNING,
            escalate(RUNNING, ReviewNarration.noStages())),
        row(
            "G10 every stage passed",
            facts().review(RUNNING, stages("passed", "passed")),
            REVIEW_RUNNING,
            new LoopStep.Pass(RUNNING)),
        row(
            "P a review whose pipeline lost its stages",
            facts().review(RUNNING, List.of()).pipeline(new LoopFacts.Pipeline.None()),
            REVIEW_RUNNING,
            escalate(RUNNING, ReviewNarration.noStages())));
  }

  /** The rows of a review whose agent stage is {@code running} since {@code started}. */
  private static List<ReviewStore.StageRow> runningSince(Instant started) {
    return List.of(
        new ReviewStore.StageRow(
            "stage-codex",
            REVIEW,
            "codex",
            "agent",
            "running",
            "codex",
            started.toString(),
            null,
            null),
        stage("approve", "human", "pending"));
  }

  private static Stream<Row> reviewerEnded() {
    var anotherBoxs = run("reviewer-1", "node-b", "review", REVIEW, "stopped", ENDED);
    var ofAnEarlierReview = ended("reviewer-1", "review", "review-0");
    return Stream.of(
        row(
            "R2 a stage started the moment its reviewer was recorded is that reviewer's",
            facts().review(RUNNING, runningSince(RECORDED)).serving(REVIEWER),
            reviewerEnded(REVIEWER),
            new LoopStep.ReadVerdict(RUNNING, 0, REVIEWER, Optional.empty())),
        row(
            "R2 a stage started the moment its reviewer ended is that reviewer's",
            facts().review(RUNNING, runningSince(ENDED)).serving(REVIEWER),
            reviewerEnded(REVIEWER),
            new LoopStep.ReadVerdict(RUNNING, 0, REVIEWER, Optional.empty())),
        row(
            "R2 a stage started before the reviewer was recorded is another reviewer's",
            facts().review(RUNNING, runningSince(RECORDED.minusSeconds(1))).serving(REVIEWER),
            reviewerEnded(REVIEWER),
            new LoopStep.Resume(RUNNING)),
        row(
            "R1 a late reviewer's stop looks at no pipeline: its review ended",
            facts()
                .review(review("passed"), stages("passed", "passed"))
                .serving(REVIEWER)
                .pipeline(new LoopFacts.Pipeline.None()),
            reviewerEnded(REVIEWER),
            NOTHING),
        row(
            "P a reviewer no stage waits on, under a pipeline that lost its stages",
            facts()
                .review(RUNNING, stages("pending", "pending"))
                .serving(REVIEWER)
                .pipeline(new LoopFacts.Pipeline.None()),
            reviewerEnded(REVIEWER),
            escalate(RUNNING, ReviewNarration.noStages())),
        row(
            "R4 the reviewer its review waits on",
            reviewed(),
            reviewerEnded(REVIEWER),
            new LoopStep.ReadVerdict(RUNNING, 0, REVIEWER, Optional.empty())),
        row(
            "R4 a legacy pending review's reviewer",
            facts().review(review("pending"), stages("running", "pending")).serving(REVIEWER),
            reviewerEnded(REVIEWER),
            new LoopStep.ReadVerdict(review("pending"), 0, REVIEWER, Optional.empty())),
        row(
            "R3 a reviewer that did not end well",
            reviewed(),
            new LoopTrigger.ReviewerEnded(REVIEWER, Optional.of("killed: time limit (45m)")),
            new LoopStep.ReadVerdict(
                RUNNING, 0, REVIEWER, Optional.of("reviewer killed: time limit (45m)"))),
        row(
            "R1 a reviewer another box executed",
            reviewed().serving().newestLoopRun(anotherBoxs),
            reviewerEnded(anotherBoxs),
            new LoopStep.Resume(RUNNING)),
        row(
            "R1 a reviewer that names no review",
            reviewed(),
            reviewerEnded(ended("reviewer-1", "review", null)),
            NOTHING),
        row(
            "R1 a reviewer of a spec that is not the loop's",
            reviewed().spec(SpecStatus.CANCELLED),
            reviewerEnded(REVIEWER),
            NOTHING),
        row(
            "R1 a reviewer a newer run replaced",
            reviewed().newestLoopRun(run("fix-9", "fix", REVIEW)),
            reviewerEnded(REVIEWER),
            NOTHING),
        row(
            "R1 a reviewer of a review that is not the spec's latest",
            reviewed().serving().newestLoopRun(ofAnEarlierReview),
            reviewerEnded(ofAnEarlierReview),
            new LoopStep.Resume(RUNNING)),
        row(
            "R1 a reviewer whose review already failed its gate",
            facts().review(GATE_FAILED, stages("failed", "pending")).serving(REVIEWER),
            reviewerEnded(REVIEWER),
            new LoopStep.StartReview(2)),
        row(
            "R1 a reviewer whose review failed is not judged, though its stage still runs",
            facts().review(GATE_FAILED, stages("running", "pending")).serving(REVIEWER),
            reviewerEnded(REVIEWER),
            new LoopStep.StartReview(2)),
        row(
            "P a reviewer whose pipeline lost its stages",
            reviewed().pipeline(new LoopFacts.Pipeline.None()),
            reviewerEnded(REVIEWER),
            escalate(RUNNING, ReviewNarration.noStages())),
        row(
            "R2 a reviewer no stage waits on",
            facts().review(RUNNING, stages("pending", "pending")).serving(REVIEWER),
            reviewerEnded(REVIEWER),
            new LoopStep.Resume(RUNNING)),
        row(
            "R2 a stage started after the reviewer ended is another reviewer's",
            facts().review(RUNNING, runningSince(ENDED.plusSeconds(1))).serving(REVIEWER),
            reviewerEnded(REVIEWER),
            new LoopStep.Resume(RUNNING)),
        row(
            "P is asked before a stage is looked for: a reviewer its review awaits, no stage of it",
            facts()
                .review(RUNNING, stages("passed", "running"))
                .serving(REVIEWER)
                .pipeline(new LoopFacts.Pipeline.None()),
            reviewerEnded(REVIEWER),
            escalate(RUNNING, ReviewNarration.noStages())),
        row(
            "R2 a person's stage is never a reviewer's to judge",
            facts().review(RUNNING, stages("passed", "running")).serving(REVIEWER),
            reviewerEnded(REVIEWER),
            NOTHING));
  }

  private static Stream<Row> stageJudged() {
    var judged = facts().review(RUNNING, stages("passed", "pending")).serving(REVIEWER);
    return Stream.of(
        row(
            "J1 a stage that passed leads to the next",
            judged,
            new LoopTrigger.StageJudged(REVIEW, 0, new StageVerdicts.StageOutcome.Passed()),
            new LoopStep.AwaitAPerson(RUNNING, 1)),
        row(
            "J1 a stage that passed leads to no other for a spec someone took meanwhile",
            facts()
                .spec(SpecStatus.CANCELLED)
                .review(RUNNING, stages("passed", "pending"))
                .serving(REVIEWER),
            new LoopTrigger.StageJudged(REVIEW, 0, new StageVerdicts.StageOutcome.Passed()),
            NOTHING),
        row(
            "J2 a stage that failed its gate",
            judged,
            new LoopTrigger.StageJudged(REVIEW, 0, new StageVerdicts.StageOutcome.GateFailed()),
            new LoopStep.FailGate(RUNNING)),
        row(
            "J3 a stage that errored",
            judged,
            new LoopTrigger.StageJudged(
                REVIEW, 1, new StageVerdicts.StageOutcome.Errored("unparseable")),
            new LoopStep.ErrorReview(RUNNING, 1, "unparseable", true)),
        row(
            "J0 a verdict on a review a re-dispatch superseded",
            facts(),
            new LoopTrigger.StageJudged(REVIEW, 0, new StageVerdicts.StageOutcome.GateFailed()),
            NOTHING),
        row(
            "J0 a verdict on a review a re-dispatch's review replaced",
            judged,
            new LoopTrigger.StageJudged("review-0", 0, new StageVerdicts.StageOutcome.GateFailed()),
            NOTHING));
  }

  private static Stream<Row> fixEnded() {
    var errored = review(1, "failed", "fix could not start", null);
    var answeredAnother = ended("fix-1", "fix", "review-0");
    return Stream.of(
        row(
            "F3 the fix agent its review waits on",
            fixed(),
            fixEnded(FIXER),
            new LoopStep.CommitFixLeftovers(GATE_FAILED, FIXER)),
        row(
            "F2 a fix agent that did not end well",
            fixed(),
            new LoopTrigger.FixEnded(FIXER, Optional.of("failed: exit 1")),
            new LoopStep.FailFix(GATE_FAILED, "fix agent failed: exit 1")),
        row(
            "F1 a fix agent a newer run replaced",
            fixed().newestLoopRun(run("reviewer-9", "review", REVIEW)),
            fixEnded(FIXER),
            NOTHING),
        row(
            "F1 a fix agent whose review failed by error",
            facts().review(errored, stages("failed", "pending")).serving(FIXER).erroredAttempts(1),
            fixEnded(FIXER),
            new LoopStep.StartReview(1)),
        row(
            "F1 a fix agent whose review is running again",
            facts().review(RUNNING, List.of()).serving(FIXER),
            fixEnded(FIXER),
            new LoopStep.Resume(RUNNING)),
        row(
            "F5 what a fix agent left is committed: the branch is judged again",
            fixed(),
            new LoopTrigger.FixCommitted(FIXER),
            new LoopStep.StartReview(2)),
        row(
            "F4 committed, and the review was superseded meanwhile",
            facts().serving(FIXER),
            new LoopTrigger.FixCommitted(FIXER),
            NOTHING),
        row(
            "F4 committed, and the review is no longer the failed one the fix answered",
            facts().review(RUNNING, List.of()).serving(FIXER),
            new LoopTrigger.FixCommitted(FIXER),
            NOTHING),
        row(
            "F5 P committed under a pipeline that changed while the fix ran",
            fixed().pipeline(new LoopFacts.Pipeline.None()),
            new LoopTrigger.FixCommitted(FIXER),
            escalate(GATE_FAILED, ReviewNarration.noStages())),
        row(
            "F7 what a fix agent left could not be committed",
            fixed(),
            new LoopTrigger.FixNotCommitted(FIXER, "push rejected"),
            new LoopStep.FailFix(
                GATE_FAILED, "fix agent's work could not be committed: push rejected")),
        row(
            "F6 not committed, and the review was superseded meanwhile",
            facts().serving(FIXER),
            new LoopTrigger.FixNotCommitted(FIXER, "push rejected"),
            NOTHING),
        row(
            "F6 not committed, and a re-dispatch's review replaced the one the fix answered",
            facts().review(GATE_FAILED, stages("failed", "pending")).serving(answeredAnother),
            new LoopTrigger.FixNotCommitted(answeredAnother, "push rejected"),
            NOTHING),
        row(
            "F6 not committed, and the review is no longer the failed one the fix answered",
            facts().review(RUNNING, List.of()).serving(FIXER),
            new LoopTrigger.FixNotCommitted(FIXER, "push rejected"),
            NOTHING));
  }

  private static Stream<Row> notStarted() {
    var launching = facts().review(RUNNING, stages("passed", "pending"));
    return Stream.of(
        row(
            "L1 a reviewer that could not start",
            launching,
            new LoopTrigger.ReviewerNotStarted(REVIEW, 1, "container refused"),
            new LoopStep.ErrorReview(
                RUNNING, 1, "reviewer could not start: container refused", false)),
        row(
            "L0 a reviewer that could not start for a review a re-dispatch superseded",
            facts(),
            new LoopTrigger.ReviewerNotStarted(REVIEW, 1, "container refused"),
            NOTHING),
        row(
            "L0 a reviewer that could not start for a review a re-dispatch's review replaced",
            launching,
            new LoopTrigger.ReviewerNotStarted("review-0", 1, "container refused"),
            NOTHING),
        row(
            "L2 a fix agent that could not start",
            owedAFix(GATE_FAILED),
            new LoopTrigger.FixNotStarted(REVIEW, "container refused"),
            new LoopStep.FailFix(GATE_FAILED, "fix agent could not start: container refused")),
        row(
            "L0 a fix agent that could not start for a review a re-dispatch superseded",
            facts(),
            new LoopTrigger.FixNotStarted(REVIEW, "container refused"),
            NOTHING),
        row(
            "L0 a fix agent that could not start for a review a re-dispatch's review replaced",
            owedAFix(GATE_FAILED),
            new LoopTrigger.FixNotStarted("review-0", "container refused"),
            NOTHING));
  }

  private static Stream<Row> operatorStopped() {
    var stopping = run("reviewer-1", NODE, "review", REVIEW, "stopping", null);
    return Stream.of(
        row(
            "O3 an operator stopped the reviewer its review waits on",
            reviewed(),
            new LoopTrigger.OperatorStopped(REVIEWER),
            escalate(RUNNING, ReviewNarration.stoppedByAnOperator("reviewer"))),
        row(
            "O3 an operator stopped the fix agent its review waits on",
            fixed(),
            new LoopTrigger.OperatorStopped(FIXER),
            escalate(GATE_FAILED, ReviewNarration.stoppedByAnOperator("fix agent"))),
        row(
            "O3 the stop landed while the run launched, and the launch errored the review",
            facts().review(ERRORED, stages("failed", "pending")).serving(REVIEWER),
            new LoopTrigger.OperatorStopped(REVIEWER),
            escalate(ERRORED, ReviewNarration.stoppedByAnOperator("reviewer"))),
        row(
            "O3 an operator stopped the reviewer of a legacy pending review",
            facts().review(review("pending"), stages("running", "pending")).serving(REVIEWER),
            new LoopTrigger.OperatorStopped(REVIEWER),
            escalate(review("pending"), ReviewNarration.stoppedByAnOperator("reviewer"))),
        row(
            "O1 the halt is still under way",
            reviewed().serving(stopping),
            new LoopTrigger.OperatorStopped(stopping),
            NOTHING),
        row(
            "O2 an operator stopped a build",
            facts().newestLoopRun(BUILD),
            new LoopTrigger.OperatorStopped(BUILD),
            NOTHING),
        row(
            "O2 an operator stopped a reviewer whose review already passed",
            facts().review(review("passed"), stages("passed", "passed")).serving(REVIEWER),
            new LoopTrigger.OperatorStopped(REVIEWER),
            NOTHING));
  }

  private static Stream<Row> rows() {
    return Stream.of(
            buildEnded(),
            freed(),
            goOn(),
            reviewRunning(),
            reviewerEnded(),
            stageJudged(),
            fixEnded(),
            notStarted(),
            operatorStopped())
        .flatMap(rows -> rows);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rows")
  void theLoopTakesTheStepItsTableNames(Row row) {
    assertEquals(row.step(), LoopDecision.next(row.facts().build(), row.trigger()));
  }
}
