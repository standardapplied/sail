/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.FixTaskBuilder;
import ai.singlr.sail.engine.ReviewPromptBuilder;
import ai.singlr.sail.store.MessageStore.MessageRow;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.ReviewStore.ReviewRow;
import ai.singlr.sail.store.ReviewStore.StageRow;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Carries out the steps of the review loop, one method a step: the only place the loop writes a
 * review, a stage or a spec's status, and the only one that launches its agents. The writes that
 * end a review commit together, and what a step says on the bus is said after the commit. Nothing
 * waits on an agent: a launch returns once the run is started, and its stop says how it ended.
 */
final class LoopSteps {

  private final SpecStore specStore;
  private final ReviewStore reviewStore;
  private final ReviewLanes lanes;
  private final LoopFactsReader reader;
  private final LoopNarrator narrator;
  private final StageVerdicts verdicts;
  private final Runnable syncTrigger;
  private final Supplier<String> localHandle;

  LoopSteps(
      SpecStore specStore,
      ReviewStore reviewStore,
      ReviewLanes lanes,
      LoopFactsReader reader,
      LoopNarrator narrator,
      Runnable syncTrigger,
      Supplier<String> localHandle) {
    this.specStore = specStore;
    this.reviewStore = reviewStore;
    this.lanes = lanes;
    this.reader = reader;
    this.narrator = narrator;
    this.verdicts = new StageVerdicts(reviewStore, lanes, narrator);
    this.syncTrigger = syncTrigger;
    this.localHandle = localHandle;
  }

  /** Carries out {@code step} for the spec of {@code facts}, and says what it came to. */
  Optional<LoopTrigger> take(LoopFacts facts, LoopStep step) {
    return switch (step) {
      case LoopStep.Nothing nothing -> Optional.empty();
      case LoopStep.SayBuildFailed failed -> sayBuildFailed(facts, failed);
      case LoopStep.ParkForAPerson park -> parkForAPerson(facts);
      case LoopStep.StartReview start -> startReview(facts, start);
      case LoopStep.EscalateNew escalated -> escalateNew(facts, escalated);
      case LoopStep.Resume resumed -> resume(facts, resumed);
      case LoopStep.LaunchReviewer launch -> launchReviewer(facts, launch);
      case LoopStep.AwaitAPerson await -> awaitAPerson(facts, await);
      case LoopStep.Pass passed -> pass(facts, passed);
      case LoopStep.ReadVerdict verdict -> readVerdict(facts, verdict);
      case LoopStep.ErrorReview errored -> errorReview(facts, errored);
      case LoopStep.FailGate failed -> failGate(facts, failed);
      case LoopStep.LaunchFix launch -> launchFix(facts, launch);
      case LoopStep.CommitFixLeftovers leftovers -> commitFixLeftovers(facts, leftovers);
      case LoopStep.FailFix failed -> failFix(facts, failed);
      case LoopStep.Escalate escalated ->
          escalate(facts, escalated.review().id(), escalated.reason());
    };
  }

  private Optional<LoopTrigger> sayBuildFailed(LoopFacts facts, LoopStep.SayBuildFailed step) {
    var detail = "exit " + step.exitCode();
    narrator.publishEvent(
        facts.project(), facts.specId(), Event.WellKnownTypes.AGENT_FAILED, detail);
    return Optional.empty();
  }

  private Optional<LoopTrigger> parkForAPerson(LoopFacts facts) {
    advanceSpec(facts.specId(), SpecStatus.REVIEW);
    return Optional.empty();
  }

  /** The row is written before the spec moves: no crash leaves a spec in review, owed nothing. */
  private Optional<LoopTrigger> startReview(LoopFacts facts, LoopStep.StartReview step) {
    createReview(facts.specId(), step.iteration());
    advanceSpec(facts.specId(), SpecStatus.REVIEW);
    return Optional.of(new LoopTrigger.ReviewRunning());
  }

  private Optional<LoopTrigger> escalateNew(LoopFacts facts, LoopStep.EscalateNew step) {
    return escalate(facts, createReview(facts.specId(), step.iteration()), step.reason());
  }

  /** A review a legacy row left {@code pending} is running from here on, so its stop finds it. */
  private Optional<LoopTrigger> resume(LoopFacts facts, LoopStep.Resume step) {
    if (!"running".equals(step.review().status())) {
      reviewStore.updateReviewStatus(step.review().id(), "running");
    }
    advanceSpec(facts.specId(), SpecStatus.REVIEW);
    return Optional.of(new LoopTrigger.ReviewRunning());
  }

  /** Moves the spec when it is still the loop's to move, and signals sync when it did. */
  private void advanceSpec(String specId, SpecStatus status) {
    if (moveSpec(specId, status)) {
      syncTrigger.run();
    }
  }

  private boolean moveSpec(String specId, SpecStatus status) {
    var moved = specStore.moveFromLoop(specId, status);
    if (!moved) {
      System.err.println(
          "review-pipeline: spec %s no longer in a pipeline-owned status; not advancing it to %s"
              .formatted(specId, status.wire()));
    }
    return moved;
  }

  private String createReview(String specId, int iteration) {
    var reviewId = reviewStore.createReview(specId, iteration);
    syncTrigger.run();
    return reviewId;
  }

  /**
   * The review's stage rows, one per configured stage, created here for every stage it does not
   * hold yet: a review a crash caught between its row and its stages holds too few, and is
   * completed before anything reads it as passed.
   */
  private List<StageRow> stagesOf(LoopFacts facts, ReviewRow review) {
    var configured = facts.staged().config().stages();
    var held = reviewStore.stagesForReview(review.id()).size();
    if (held >= configured.size()) {
      return reviewStore.stagesForReview(review.id());
    }
    for (var stage : configured.subList(held, configured.size())) {
      reviewStore.createStage(review.id(), stage.name(), LoopFacts.stageType(stage));
    }
    syncTrigger.run();
    return reviewStore.stagesForReview(review.id());
  }

  /**
   * The review's status, its spec parked in {@code awaiting_merge} and the verdict in the room are
   * one write ({@link ReviewStore#pass}), announced only then: no crash leaves a finished review
   * beside a spec nothing will move. A spec someone moved meanwhile keeps their status.
   */
  private Optional<LoopTrigger> pass(LoopFacts facts, LoopStep.Pass step) {
    var review = step.review();
    var specId = facts.specId();
    var verdict =
        ReviewNarration.passed(
            review.iteration(),
            reviewStore.openFindingsForReview(review.id()),
            reviewStore.disputedFindings(specId));
    reviewStore.pass(
        review.id(),
        () -> {
          moveSpec(specId, SpecStatus.AWAITING_MERGE);
          narrator.appendRoom(specId, verdict);
        });
    syncTrigger.run();
    narrator.publishEvent(facts.project(), specId, "review_completed", null);
    return Optional.empty();
  }

  private Optional<LoopTrigger> awaitAPerson(LoopFacts facts, LoopStep.AwaitAPerson step) {
    var stage = stagesOf(facts, step.review()).get(step.stage());
    var specId = facts.specId();
    reviewStore.startStage(stage.id(), "human");
    narrator.publishEvent(facts.project(), specId, "review_stage_started", stage.name());
    narrator.postRoom(specId, ReviewNarration.awaitingHuman(reviewStore.disputedFindings(specId)));
    syncTrigger.run();
    return Optional.empty();
  }

  /**
   * The stage row turns {@code running} only once its reviewer's claim has landed, before that
   * reviewer's unit starts — so a stage is {@code running} only while a run exists for it ({@link
   * LoopFacts.Rows#stageReviewedBy}). A claim the gate refuses writes nothing of the stage, and the
   * stage is announced started only once a reviewer is running for it.
   */
  private Optional<LoopTrigger> launchReviewer(LoopFacts facts, LoopStep.LaunchReviewer step) {
    var stage = stagesOf(facts, step.review()).get(step.stage());
    var stageConfig = facts.staged().config().stages().get(step.stage());
    var specId = facts.specId();
    var spec = facts.rows().spec().orElseThrow();
    Supplier<ReviewLanes.Invocation> invocation =
        () -> {
          var carried = reviewStore.carryForwardFindings(specId, stage.reviewId(), stage.name());
          var built =
              ReviewPromptBuilder.build(
                  spec.branch(),
                  spec.repos(),
                  stageConfig.categories(),
                  narrator.roomMessages(specId),
                  carried);
          return new ReviewLanes.Invocation(
              Lane.REVIEW,
              stage.reviewId(),
              facts.project(),
              specId,
              step.agent(),
              built.prompt(),
              spec.branch(),
              spec.repos(),
              null,
              spec.reasoningEffort(),
              built.renderedMessages().stream().map(MessageRow::id).toList());
        };
    return launch(
        invocation,
        step.review(),
        () -> reviewStore.startStage(stage.id(), step.agent()),
        () -> narrator.publishEvent(facts.project(), specId, "review_stage_started", stage.name()),
        why -> new LoopTrigger.ReviewerNotStarted(step.review().id(), step.stage(), why));
  }

  /**
   * Launches {@code invocation} for {@code review}, running {@code claimed} once its claim has
   * landed and {@code started} once a run serves the review, when any wait it recorded is cleared.
   * A claim the gate refused is recorded as the run that holds it, and the room is told in the same
   * write, so once per run waited on. A launch that fails after its agent started still left a run
   * serving the review, and the loop waits for that run's stop rather than act over it; one that
   * left none is {@code notStarted}, for the reason it gives.
   */
  private Optional<LoopTrigger> launch(
      Supplier<ReviewLanes.Invocation> invocation,
      ReviewRow review,
      Runnable claimed,
      Runnable started,
      Function<String, LoopTrigger> notStarted) {
    try {
      switch (lanes.launch(invocation.get(), localHandle.get(), claimed)) {
        case ReviewLanes.Launch.Started ran -> served(review);
        case ReviewLanes.Launch.Deferred deferred -> {
          var holder = deferred.holder();
          Runnable said =
              () -> narrator.appendRoom(review.specId(), ReviewNarration.waiting(holder));
          reviewStore.waitOn(review.id(), holder.runId(), said);
          syncTrigger.run();
          return Optional.empty();
        }
      }
    } catch (Exception e) {
      if (!reader.served(review.id())) {
        return Optional.of(notStarted.apply(reasonOf(e)));
      }
      System.err.println(
          "review-pipeline: a launch for review %s reported a failure after its agent started (%s);"
                  .formatted(review.id(), reasonOf(e))
              + " waiting for that run's stop");
      served(review);
    }
    started.run();
    return Optional.empty();
  }

  /** A run serves {@code review} now: what it waits on is that run, and no other. */
  private void served(ReviewRow review) {
    reviewStore.clearWait(review.id());
    syncTrigger.run();
  }

  /** A launch error wraps what refused it, and the wrapper alone says only that it failed. */
  private static String reasonOf(Exception failure) {
    var cause = failure.getCause();
    return cause == null || Strings.isBlank(cause.getMessage())
        ? failure.getMessage()
        : failure.getMessage() + " " + cause.getMessage();
  }

  private Optional<LoopTrigger> readVerdict(LoopFacts facts, LoopStep.ReadVerdict step) {
    var stage = facts.rows().stages().get(step.stage());
    var stageConfig = facts.staged().config().stages().get(step.stage());
    var outcome =
        step.error()
            .map(error -> verdicts.errored(stage, error))
            .orElseGet(() -> verdicts.read(stage, stageConfig, step.run()));
    syncTrigger.run();
    return Optional.of(new LoopTrigger.StageJudged(step.review().id(), step.stage(), outcome));
  }

  /**
   * An infrastructure failure, not a verdict: no fix iteration, and no iteration is burned. A stage
   * its verdict already closed is left as that verdict left it; one that could not start is closed
   * here, once the review holds its row.
   */
  private Optional<LoopTrigger> errorReview(LoopFacts facts, LoopStep.ErrorReview step) {
    var why = step.why();
    var stages = step.closed() ? facts.rows().stages() : stagesOf(facts, step.review());
    var stage = stages.get(step.stage());
    if (!step.closed()) {
      System.err.println("review-pipeline: agent stage '" + stage.name() + "': " + why);
      reviewStore.completeStage(stage.id(), "failed", why);
    }
    reviewStore.failReviewWithError(step.review().id(), why);
    syncTrigger.run();
    System.err.println(
        "review-pipeline: review %s for spec %s errored at stage '%s': %s"
            .formatted(step.review().id(), facts.specId(), stage.name(), why));
    narrator.publishEvent(facts.project(), facts.specId(), "review_errored", why);
    return Optional.empty();
  }

  /** A stage failed its gate: the review has failed and the room is told what it found. */
  private Optional<LoopTrigger> failGate(LoopFacts facts, LoopStep.FailGate step) {
    var review = step.review();
    reviewStore.updateReviewStatus(review.id(), "failed");
    narrator.postRoom(
        facts.specId(),
        ReviewNarration.failed(
            review.iteration(),
            reviewStore.openFindingsForReview(review.id()),
            reviewStore.disputedFindings(facts.specId())));
    return Optional.of(new LoopTrigger.GoOn());
  }

  /**
   * The findings go to the spec's own agent as a fix run that serves the review and acts as itself
   * ({@code <agent>/fix-<runId>}), so the room attributes its posts to the fix lane. A refused
   * claim changes nothing but the run the review waits on: the fix is still owed.
   */
  private Optional<LoopTrigger> launchFix(LoopFacts facts, LoopStep.LaunchFix step) {
    var spec = facts.rows().spec().orElseThrow();
    var specId = facts.specId();
    Supplier<ReviewLanes.Invocation> invocation =
        () -> {
          var room = narrator.roomMessages(specId);
          var built = FixTaskBuilder.build(specId, spec.title(), step.findings(), room);
          return new ReviewLanes.Invocation(
              Lane.FIX,
              step.review().id(),
              facts.project(),
              specId,
              spec.agent() != null ? spec.agent() : "claude-code",
              built.task(),
              spec.branch(),
              spec.repos(),
              spec.model(),
              spec.reasoningEffort(),
              built.renderedMessages().stream().map(MessageRow::id).toList());
        };
    Runnable started =
        () -> {
          advanceSpec(specId, SpecStatus.IN_PROGRESS);
          narrator.publishEvent(facts.project(), specId, "review_iteration_started", null);
        };
    return launch(
        invocation,
        step.review(),
        () -> {},
        started,
        why -> new LoopTrigger.FixNotStarted(step.review().id(), why));
  }

  /**
   * The stop gate is a nudge, not a jail: without this the re-review judges a branch without the
   * fixes, and the shared clone carries the leftovers into the next dispatch.
   */
  private Optional<LoopTrigger> commitFixLeftovers(
      LoopFacts facts, LoopStep.CommitFixLeftovers step) {
    var spec = facts.rows().spec().orElseThrow();
    try {
      var message =
          FixTaskBuilder.commitMessage(reviewStore.openFindingsForReview(step.review().id()));
      var rescued = lanes.ensureCommitted(facts.project(), spec.repos(), spec.branch(), message);
      if (!rescued.isEmpty()) {
        narrator.publishGuardrail(
            facts.project(),
            facts.specId(),
            "fix agent left uncommitted changes in " + ReviewNarration.rescues(rescued),
            "committed and pushed them to " + spec.branch());
      }
      return Optional.of(new LoopTrigger.FixCommitted(step.run()));
    } catch (Exception e) {
      return Optional.of(new LoopTrigger.FixNotCommitted(step.run(), e.getMessage()));
    }
  }

  /**
   * There is no re-review to run: the branch still holds the code the reviewer just failed. The
   * iteration is said to have failed once the review's end is written, like every end.
   */
  private Optional<LoopTrigger> failFix(LoopFacts facts, LoopStep.FailFix step) {
    var reviewId = step.review().id();
    var why = step.why();
    System.err.println(
        "review-pipeline: fix iteration of review %s for spec %s failed: %s"
            .formatted(reviewId, facts.specId(), why));
    var reason = ReviewNarration.fixFailed(why);
    handToAPerson(facts.specId(), reviewId, reason);
    narrator.publishEvent(facts.project(), facts.specId(), "review_iteration_failed", why);
    narrator.publishEvent(facts.project(), facts.specId(), "review_escalated", reason);
    return Optional.empty();
  }

  private Optional<LoopTrigger> escalate(LoopFacts facts, String reviewId, String reason) {
    handToAPerson(facts.specId(), reviewId, reason);
    narrator.publishEvent(facts.project(), facts.specId(), "review_escalated", reason);
    return Optional.empty();
  }

  /**
   * The review's status, its reason, its stages closed, its spec in {@code review} and the room
   * line are one write ({@link ReviewStore#escalate}). The reason rides the synced row, so main
   * says what this box says, and travels as the event detail, so Slack says why.
   */
  private void handToAPerson(String specId, String reviewId, String reason) {
    reviewStore.escalate(
        reviewId,
        reason,
        () -> {
          moveSpec(specId, SpecStatus.REVIEW);
          narrator.appendRoom(specId, ReviewNarration.escalated(reason));
        });
    syncTrigger.run();
  }
}
