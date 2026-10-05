/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.api.LoopFacts.Owed;
import ai.singlr.sail.api.LoopFacts.Pipeline;
import ai.singlr.sail.api.ReviewPipelineController.StageOutcome;
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
 * <p>A stop that is not the one the loop is waiting on — a duplicate, a replay, the stop of a run a
 * newer one has replaced — never repeats a step: the loop goes on from what the review's rows say
 * it is owed ({@link LoopFacts#owed}), which is both its retry and its crash recovery. And before
 * any step on an existing review, one the project's pipeline can no longer judge ({@link
 * LoopFacts#unfit}) is handed to a person saying why.
 */
final class LoopDecision {

  static final String NO_REVIEWER =
      "no reviewer agent resolved; set stages[].agent or agent.install in sail.yaml";

  private LoopDecision() {}

  static LoopStep next(LoopFacts facts, LoopTrigger trigger) {
    return switch (trigger) {
      case LoopTrigger.BuildEnded build -> buildEnded(facts, build);
      case LoopTrigger.ReviewerEnded reviewer -> reviewerEnded(facts, reviewer);
      case LoopTrigger.FixEnded fix -> fixEnded(facts, fix);
      case LoopTrigger.OperatorStopped stopped -> operatorStopped(facts, stopped.run());
      case LoopTrigger.GoOn goOn -> goOn(facts);
      case LoopTrigger.ReviewRunning running -> onReview(facts, review -> inItsStages(facts));
      case LoopTrigger.StageJudged judged ->
          onReview(
              facts,
              review ->
                  switch (judged.outcome()) {
                    case StageOutcome.Passed passed -> inItsStages(facts);
                    case StageOutcome.GateFailed failed -> new LoopStep.FailGate(review);
                    case StageOutcome.Errored errored ->
                        new LoopStep.ErrorReview(review, judged.stage(), errored.message(), true);
                  });
      case LoopTrigger.LaunchFailed failed ->
          onReview(facts, review -> launchFailed(review, failed));
      case LoopTrigger.FixCommitted committed ->
          facts
              .awaited(committed.run(), "failed")
              .map(review -> fit(facts, () -> new LoopStep.StartReview(review.iteration() + 1)))
              .orElseGet(LoopStep.Nothing::new);
      case LoopTrigger.FixNotCommitted failed ->
          onReview(facts, review -> new LoopStep.FailFix(review, uncommitted(failed.why())));
    };
  }

  /**
   * A build ended: a non-zero exit is the agent's failure, surfaced and left for triage; anything
   * else — a clean exit, or a run ended without an exit code — hands the spec to review, which
   * judges the work the build left. A dispatch supersedes the reviews before it, so a build's own
   * stop finds none and starts the first. A stop that finds one, or that a newer run of the spec's
   * loop has replaced — a build re-dispatched before its stop was heard — is late or replayed, and
   * the loop only goes on from what the review is owed: never a second review beside a working fix
   * agent, nor one beside the build that replaced this one.
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

  /**
   * A reviewer ended. When it is the run its review waits on, the stage it ran is judged: on the
   * findings in its own log, or — for one the watcher killed or that exited non-zero — as an
   * infrastructure error naming why.
   */
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
   * A fix agent ended. When it is the run its review waits on and it ended well, what it left
   * uncommitted is committed and pushed before the branch is judged again. One the watcher killed
   * or that exited non-zero did not address the findings: nothing of its is committed, the branch
   * still holds the code the reviewer just failed, and the spec escalates.
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
   * An operator stopped a run. For a reviewer or a fix agent the stop was a person's decision about
   * the loop, so the loop does not retry over it: the review escalates — also when the stop landed
   * while the run was still launching and the failed launch already errored the review. A build an
   * operator stopped has no review to escalate and starts none. While the halt is still under way
   * nothing is decided, since a halt that fails gives the run back.
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
   * The step a review is owed. An errored review runs its iteration again — an infrastructure error
   * burns none — until {@link ReviewPipelineController#MAX_ERRORED_RETRIES} attempts of it have
   * errored, when the spec escalates. A review a run still serves, one that passed or escalated,
   * and one whose reviewer or fix agent has ended — whose own stop is the only word on what its
   * work is worth — are left as they are, and one that recorded a wait takes the step the gate
   * refused it only once the run it waits on has ended.
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
   * The step of a running review, from its stages: the first configured stage whose row has not
   * passed — or is not written yet — is the one the review is in. A human stage opens and waits for
   * a person; an agent stage gets its reviewer launched, and one with no reviewer to resolve is an
   * infrastructure error like any other. With every stage passed the review has.
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
   * What follows a gate failure: the spec escalates when a finding of the failed stage is stuck or
   * the iterations are spent, and otherwise the review's open findings go to a fix agent. With none
   * left open — a person resolved them before the fix launched — there is nothing to fix and the
   * branch is judged again as the next iteration.
   *
   * <p>The convergence check: a gate-blocking finding whose {@code carried_from} chain shows it has
   * already survived {@code maxFindingAge} fix iterations. Sub-gate findings may age freely — they
   * do not drive the loop — and a loop resolving old blockers while new ones surface never trips
   * this. Scoped to the failed stage's own findings: a finding is gate-blocking only under the gate
   * of the stage that owns it.
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

  private static String uncommitted(String why) {
    return "fix agent's work could not be committed: " + why;
  }

  /** {@code step}, unless the project's pipeline can no longer judge the spec's review. */
  private static LoopStep fit(LoopFacts facts, Supplier<LoopStep> step) {
    return facts
        .unfit()
        .<LoopStep>map(unfit -> new LoopStep.Escalate(facts.review().orElseThrow(), unfit))
        .orElseGet(step);
  }

  /** The step for the spec's review, or nothing when a re-dispatch superseded it meanwhile. */
  private static LoopStep onReview(LoopFacts facts, Function<ReviewRow, LoopStep> step) {
    return facts.review().map(step).orElseGet(LoopStep.Nothing::new);
  }

  /** What the loop calls the agent of a review lane when it says how that agent ended. */
  private static String noun(Lane lane) {
    return lane == Lane.FIX ? "fix agent" : "reviewer";
  }
}
