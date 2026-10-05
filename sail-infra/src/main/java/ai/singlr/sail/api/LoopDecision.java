/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.api.LoopFacts.Owed;
import ai.singlr.sail.api.LoopFacts.Pipeline;
import ai.singlr.sail.api.StageVerdicts.StageOutcome;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.ReviewPipelineConfig.StageType;
import ai.singlr.sail.config.RunStatus;
import ai.singlr.sail.store.ReviewStore.ReviewRow;
import ai.singlr.sail.store.RunStore.RunRow;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The one decision of the review loop: the step a spec's loop takes for what happened, read off its
 * {@link LoopFacts}. Pure — no store, no clock, no bus, no container — so every row of the loop's
 * table (ARCHITECTURE.md, "The loop machine") is a case of {@code LoopDecisionTest}.
 *
 * <p>A stop the loop is not waiting on — a duplicate, a replay, the stop of a run a newer one has
 * replaced — never repeats a step: the loop goes on from what the review's rows say it is owed
 * ({@link LoopFacts#owed}), which is both its retry and its crash recovery. And before any step on
 * an existing review, one the pipeline can no longer judge ({@link LoopFacts#unfit}) is escalated.
 */
final class LoopDecision {

  static final String NO_REVIEWER =
      "no reviewer agent resolved; set stages[].agent or agent.install in sail.yaml";

  private static final String NOT_COMMITTED = "fix agent's work could not be committed: ";

  private LoopDecision() {}

  static LoopStep next(LoopFacts facts, LoopTrigger trigger) {
    return switch (trigger) {
      case LoopTrigger.BuildEnded build -> buildEnded(facts, build);
      case LoopTrigger.ReviewerEnded reviewer -> reviewerEnded(facts, reviewer);
      case LoopTrigger.FixEnded fix -> fixEnded(facts, fix);
      case LoopTrigger.OperatorStopped stopped -> operatorStopped(facts, stopped.run());
      case LoopTrigger.GoOn goOn -> goOn(facts);
      case LoopTrigger.ReviewRunning running -> on(facts.review(), review -> inItsStages(facts));
      case LoopTrigger.StageJudged judged ->
          on(
              facts.latest(judged.reviewId()),
              review ->
                  switch (judged.outcome()) {
                    case StageOutcome.Passed passed -> inItsStages(facts);
                    case StageOutcome.GateFailed failed -> new LoopStep.FailGate(review);
                    case StageOutcome.Errored errored ->
                        new LoopStep.ErrorReview(review, judged.stage(), errored.message(), true);
                  });
      case LoopTrigger.LaunchFailed failed ->
          on(facts.latest(failed.reviewId()), review -> launchFailed(review, failed));
      case LoopTrigger.FixCommitted committed ->
          on(
              facts.awaited(committed.run(), "failed"),
              review -> fit(facts, () -> new LoopStep.StartReview(review.iteration() + 1)));
      case LoopTrigger.FixNotCommitted failed ->
          on(
              facts.awaited(failed.run(), "failed"),
              review -> new LoopStep.FailFix(review, NOT_COMMITTED + failed.why()));
    };
  }

  /**
   * A non-zero exit is the agent's failure, left for triage; anything else — a clean exit, or a run
   * ended without an exit code — hands the spec to review. A dispatch supersedes the reviews before
   * it, so a build's own stop finds none and starts the first. A stop that finds one, or that a
   * newer run of the spec's loop has replaced, is late or replayed: never a second review beside a
   * working fix agent, nor one beside the build that replaced this one.
   */
  private static LoopStep buildEnded(LoopFacts facts, LoopTrigger.BuildEnded build) {
    if (!facts.loops()) {
      return new LoopStep.Nothing();
    }
    if (build.runId() != null && facts.replaced(build.runId())) {
      return goOn(facts);
    }
    if (build.exitCode() != null && build.exitCode() != 0) {
      return new LoopStep.SayBuildFailed(build.exitCode());
    }
    if (facts.review().isPresent()) {
      return goOn(facts);
    }
    return switch (facts.pipeline()) {
      case Pipeline.Staged staged -> new LoopStep.StartReview(1);
      case Pipeline.None none -> new LoopStep.ParkForAPerson();
      case Pipeline.Unreadable unreadable ->
          new LoopStep.EscalateNew(1, ReviewNarration.pipelineUnreadable(unreadable.why()));
    };
  }

  /** The reviewer its review waits on has its stage judged; any other reviewer's stop is late. */
  private static LoopStep reviewerEnded(LoopFacts facts, LoopTrigger.ReviewerEnded ended) {
    var review = facts.awaited(ended.run(), "pending", "running").orElse(null);
    var stage = facts.stageReviewedBy(ended.run()).orElse(null);
    if (review == null) {
      return goOn(facts);
    }
    var error = ended.failure().map(how -> noun(Lane.REVIEW) + " " + how);
    return fit(
        facts,
        () ->
            stage == null
                ? goOn(facts)
                : new LoopStep.ReadVerdict(review, stage, ended.run(), error));
  }

  /**
   * A fix agent that ended well has what it left uncommitted committed before the branch is judged
   * again. One the watcher killed or that exited non-zero did not address the findings: nothing of
   * its is committed, the branch still holds the code the reviewer failed, and the spec escalates.
   */
  private static LoopStep fixEnded(LoopFacts facts, LoopTrigger.FixEnded ended) {
    var review = facts.awaited(ended.run(), "failed").filter(failed -> !failed.errored());
    if (review.isEmpty()) {
      return goOn(facts);
    }
    return ended
        .failure()
        .<LoopStep>map(how -> new LoopStep.FailFix(review.get(), noun(Lane.FIX) + " " + how))
        .orElseGet(() -> new LoopStep.CommitFixLeftovers(review.get(), ended.run()));
  }

  /**
   * An operator's stop of a reviewer or a fix agent is a person's decision about the loop, so the
   * loop does not retry over it: the review escalates — also when the stop landed while the run was
   * still launching and the failed launch already errored the review. A build they stopped has no
   * review to escalate. While the halt is under way nothing is decided: a failed one gives it back.
   */
  private static LoopStep operatorStopped(LoopFacts facts, RunRow run) {
    var reason = ReviewNarration.stoppedByAnOperator(noun(run.lane().orElse(Lane.REVIEW)));
    return Optional.of(run)
        .filter(stopped -> RunStatus.isTerminal(stopped.status()))
        .flatMap(stopped -> facts.awaited(stopped, "pending", "running", "failed"))
        .<LoopStep>map(review -> new LoopStep.Escalate(review, reason))
        .orElseGet(LoopStep.Nothing::new);
  }

  private static LoopStep goOn(LoopFacts facts) {
    return facts.loops() ? owed(facts, facts.owed()) : new LoopStep.Nothing();
  }

  /**
   * An errored review runs its iteration again — an infrastructure error burns none — within its
   * budget. One whose reviewer or fix agent has ended is left to that run's own stop, the only word
   * on what its work is worth, and a wait holds its step until the run it waits on has ended.
   */
  private static LoopStep owed(LoopFacts facts, Owed owed) {
    var retries = ReviewPipelineController.MAX_ERRORED_RETRIES;
    return switch (owed) {
      case Owed.Retry(var review) when facts.erroredAttempts() >= retries ->
          new LoopStep.Escalate(review, ReviewNarration.erroredOut(retries, review.iteration()));
      case Owed.Retry(var review) -> fit(facts, () -> new LoopStep.StartReview(review.iteration()));
      case Owed.Advance(var review) -> fit(facts, () -> new LoopStep.Resume(review));
      case Owed.Fix(var review) -> fit(facts, () -> fix(facts, review));
      case Owed.Waiting waiting when facts.holderEnded() -> owed(facts, waiting.step());
      case Owed.Waiting stillHeld -> new LoopStep.Nothing();
      case Owed.Stop awaitsItsStop -> new LoopStep.Nothing();
      case Owed.Nothing nothing -> new LoopStep.Nothing();
    };
  }

  /**
   * The step of a running review: the first configured stage whose row has not passed, or is not
   * written yet, is the one it is in. An agent stage with no reviewer to resolve is an
   * infrastructure error like any other, and with every stage passed the review has.
   */
  private static LoopStep inItsStages(LoopFacts facts) {
    return fit(facts, () -> stageStep(facts, facts.review().orElseThrow()));
  }

  private static LoopStep stageStep(LoopFacts facts, ReviewRow review) {
    var configured = facts.staged().config().stages();
    for (var place = 0; place < configured.size(); place++) {
      var status = place < facts.stages().size() ? facts.stages().get(place).status() : "pending";
      if ("passed".equals(status)) {
        continue;
      }
      var stage = place;
      var stageConfig = configured.get(place);
      if (stageConfig.type() == StageType.HUMAN) {
        return "running".equals(status)
            ? new LoopStep.Nothing()
            : new LoopStep.AwaitAPerson(review, stage);
      }
      return facts
          .staged()
          .reviewer(stageConfig)
          .<LoopStep>map(agent -> new LoopStep.LaunchReviewer(review, stage, stageConfig, agent))
          .orElseGet(() -> new LoopStep.ErrorReview(review, stage, NO_REVIEWER, false));
    }
    return new LoopStep.Pass(review);
  }

  /**
   * What follows a gate failure. With no finding left open — a person resolved them before the fix
   * launched — there is nothing to fix and the branch is judged again as the next iteration.
   *
   * <p>The convergence check: a gate-blocking finding that has already survived {@code
   * maxFindingAge} fix iterations. Sub-gate findings may age freely, and a loop resolving old
   * blockers while new ones surface never trips this. Only the failed stage's own are weighed.
   */
  private static LoopStep fix(LoopFacts facts, ReviewRow review) {
    var config = facts.staged().config();
    var failed = facts.stages().stream().filter(stage -> "failed".equals(stage.status()));
    var gate =
        failed
            .findFirst()
            .flatMap(
                stage ->
                    config.stages().stream()
                        .filter(stageConfig -> stageConfig.name().equals(stage.name()))
                        .findFirst());
    var stuck =
        facts.failedStageFindings().stream()
            .filter(aged -> gate.filter(stage -> stage.gate().blocks(aged.finding())).isPresent())
            .filter(aged -> aged.age() >= config.maxFindingAge())
            .findFirst();
    if (stuck.isPresent()) {
      return new LoopStep.Escalate(
          review, ReviewNarration.stuckOn(stuck.get().finding().title(), stuck.get().age()));
    }
    if (review.iteration() >= config.maxIterations()) {
      return new LoopStep.Escalate(
          review, ReviewNarration.iterationsExhausted(config.maxIterations()));
    }
    return facts.openFindings().isEmpty()
        ? new LoopStep.StartReview(review.iteration() + 1)
        : new LoopStep.LaunchFix(review, facts.openFindings());
  }

  private static LoopStep launchFailed(ReviewRow review, LoopTrigger.LaunchFailed failed) {
    var why = noun(failed.lane()) + " could not start: " + failed.why();
    return failed.lane() == Lane.FIX
        ? new LoopStep.FailFix(review, why)
        : new LoopStep.ErrorReview(review, failed.stage(), why, false);
  }

  /** {@code step}, unless the project's pipeline can no longer judge the spec's review. */
  private static LoopStep fit(LoopFacts facts, Supplier<LoopStep> step) {
    return facts
        .unfit()
        .<LoopStep>map(unfit -> new LoopStep.Escalate(facts.review().orElseThrow(), unfit))
        .orElseGet(step);
  }

  /** The step for the review a follow-up is about, or nothing when a re-dispatch replaced it. */
  private static LoopStep on(Optional<ReviewRow> review, Function<ReviewRow, LoopStep> step) {
    return review.map(step).orElseGet(LoopStep.Nothing::new);
  }

  /** What the loop calls the agent of a review lane when it says how that agent ended. */
  private static String noun(Lane lane) {
    return lane == Lane.FIX ? "fix agent" : "reviewer";
  }
}
