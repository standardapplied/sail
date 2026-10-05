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
import ai.singlr.sail.store.SpecStore.SpecRow;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Carries out the steps of the review loop, one method a step: the only place the loop writes a
 * review, a stage or a spec's status, and the only one that launches its agents. The writes that
 * end a review commit together, and what a step says on the bus is said after the commit.
 *
 * <p>Nothing waits on an agent. A launch returns once the run is started ({@link ReviewLanes}), and
 * the loop hears how the run ended from its stop.
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

  /**
   * @param syncTrigger fired after every state change the loop makes, so it reaches main at once
   * @param localHandle this box's FDE handle, under which the loop's runs are launched
   */
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

  /**
   * The row is written before the spec moves, so no crash leaves a spec in review, owed nothing.
   */
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

  /**
   * Advances a spec's status and signals sync so the transition reaches main, when the spec is
   * still the loop's to move ({@link SpecStore#moveFromLoop}).
   */
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
   * The review's stage rows, one per configured stage, in order — created here for every stage the
   * review does not hold yet. A review is written as a row and then its stages, so one a crash
   * caught in between holds too few; it is completed before anything reads it as a review whose
   * every stage has passed.
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
   * The review passed: its status, its spec parked in {@code awaiting_merge} and the verdict in the
   * room are one write ({@link ReviewStore#pass}), and only then is it announced. A failure
   * anywhere leaves none of them written, so no crash leaves a finished review beside a spec
   * nothing will move, or a verdict nobody was told. The spec's own write stays compare-and-set
   * from the statuses the pipeline owns: a spec someone moved meanwhile keeps their status, and the
   * review still ends.
   */
  private Optional<LoopTrigger> pass(LoopFacts facts, LoopStep.Pass step) {
    var review = step.review();
    var specId = facts.specId();
    stagesOf(facts, review);
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
   * Starts an agent stage: its reviewer claims the spec's repos as a run that serves the review,
   * and only once that claim has landed does the stage row turn {@code running}, before the
   * reviewer's unit starts — so a stage is {@code running} only while a run exists for it, and the
   * reviewer a stage waits on is the run that was live when it started ({@link
   * LoopFacts#stageReviewedBy}). A claim the gate refuses writes nothing of the stage. This box
   * announces the stage started only once a reviewer is running for it.
   */
  private Optional<LoopTrigger> launchReviewer(LoopFacts facts, LoopStep.LaunchReviewer step) {
    var stage = stagesOf(facts, step.review()).get(step.stage());
    var specId = facts.specId();
    var spec = facts.spec();
    var branch = spec.map(SpecRow::branch).orElse("main");
    var repos = spec.map(SpecRow::repos).orElse(List.of());
    Supplier<ReviewLanes.Invocation> invocation =
        () -> {
          var carried = reviewStore.carryForwardFindings(specId, stage.reviewId(), stage.name());
          var categories = step.stageConfig().categories();
          var built =
              ReviewPromptBuilder.build(
                  branch, repos, categories, narrator.roomMessages(specId), carried);
          return new ReviewLanes.Invocation(
              Lane.REVIEW,
              stage.reviewId(),
              facts.project(),
              specId,
              step.agent(),
              built.prompt(),
              branch,
              repos,
              null,
              spec.map(SpecRow::reasoningEffort).orElse(null),
              built.renderedMessages().stream().map(MessageRow::id).toList());
        };
    return launch(
        invocation,
        step.review(),
        why -> new LoopTrigger.LaunchFailed(Lane.REVIEW, step.stage(), why),
        () -> reviewStore.startStage(stage.id(), step.agent()),
        () -> narrator.publishEvent(facts.project(), specId, "review_stage_started", stage.name()));
  }

  /**
   * Launches the run {@code invocation} describes for {@code review}, running {@code claimed} once
   * its claim has landed and {@code started} once a run serves the review. A run that started is
   * what the review waits on now, so any wait it recorded is cleared. A claim the gate refused is
   * recorded as the run that holds it, and the room is told in the same write, so once per run
   * waited on. A launch that fails after its agent started — the launch command or the status read
   * failing over a live unit — still left a run serving the review, and the loop waits for that
   * run's stop rather than call a working agent a failure and act over it. Nothing started, and
   * nothing that will be, is {@code failed}, saying why.
   */
  private Optional<LoopTrigger> launch(
      Supplier<ReviewLanes.Invocation> invocation,
      ReviewRow review,
      Function<String, LoopTrigger> failed,
      Runnable claimed,
      Runnable started) {
    boolean serving;
    try {
      serving =
          switch (lanes.launch(invocation.get(), localHandle.get(), claimed)) {
            case ReviewLanes.Launch.Started ran -> serving(review);
            case ReviewLanes.Launch.Deferred deferred -> {
              var holder = deferred.holder();
              Runnable said =
                  () -> narrator.appendRoom(review.specId(), ReviewNarration.waiting(holder));
              reviewStore.waitOn(review.id(), holder.runId(), said);
              syncTrigger.run();
              yield false;
            }
          };
    } catch (Exception e) {
      if (!reader.served(review.id())) {
        return Optional.of(failed.apply(reasonOf(e)));
      }
      System.err.println(
          "review-pipeline: a launch for review %s reported a failure after its agent started (%s);"
                  .formatted(review.id(), reasonOf(e))
              + " waiting for that run's stop");
      serving = serving(review);
    }
    if (serving) {
      started.run();
    }
    return Optional.empty();
  }

  private boolean serving(ReviewRow review) {
    reviewStore.clearWait(review.id());
    syncTrigger.run();
    return true;
  }

  /**
   * Why a launch failed, as far down as it is said: a launch error wraps what refused it — an agent
   * sail does not know, a container that will not answer — and the wrapper alone says only that the
   * launch failed.
   */
  private static String reasonOf(Exception failure) {
    var cause = failure.getCause();
    return cause == null || Strings.isBlank(cause.getMessage())
        ? failure.getMessage()
        : failure.getMessage() + " " + cause.getMessage();
  }

  /**
   * Judges the stage a reviewer ran: one that did not end well is an infrastructure error saying
   * how it ended, and one that ended cleanly is judged on the findings in its own log.
   */
  private Optional<LoopTrigger> readVerdict(LoopFacts facts, LoopStep.ReadVerdict step) {
    var stage = facts.stages().get(step.stage());
    var stageConfig = facts.staged().config().stages().get(step.stage());
    var outcome =
        step.error()
            .map(error -> verdicts.errored(stage, error))
            .orElseGet(() -> verdicts.read(stage, stageConfig, step.run()));
    syncTrigger.run();
    return Optional.of(new LoopTrigger.StageJudged(step.stage(), outcome));
  }

  /**
   * An errored stage is an infrastructure failure, not a review verdict: record why on the review,
   * say so loudly, and stop — without a fix iteration (there are no findings to fix) and without
   * counting against {@code max_iterations} (the next stop retries the same iteration). A stage
   * that could not start — no reviewer to resolve, a container that will not take the launch — is
   * closed for that reason first.
   */
  private Optional<LoopTrigger> errorReview(LoopFacts facts, LoopStep.ErrorReview step) {
    var stage = stagesOf(facts, step.review()).get(step.stage());
    var why = step.why();
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
   * Hands a failed review's findings to the spec's own agent as a fix run that serves that review.
   * The run acts as itself — principal {@code <agent>/fix-<runId>} — so the room's audit trail
   * attributes its posts to the fix lane, never to the reviewer, and it runs with the stop gate
   * asking for a committed, pushed tree. Once the run exists the spec is back {@code in_progress}
   * and the room is told a fix iteration started. A claim the gate refuses changes nothing but the
   * run the review waits on: the fix is still owed, and is launched once that run has ended.
   */
  private Optional<LoopTrigger> launchFix(LoopFacts facts, LoopStep.LaunchFix step) {
    var spec = facts.spec().orElseThrow();
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
    Function<String, LoopTrigger> failed = why -> new LoopTrigger.LaunchFailed(Lane.FIX, 0, why);
    return launch(invocation, step.review(), failed, () -> {}, started);
  }

  /**
   * Commits and pushes whatever the fix agent left uncommitted on the spec's branch — the gate is a
   * nudge, not a jail, and otherwise the re-review judges a branch without the fixes and the shared
   * clone carries the leftovers into the next dispatch.
   */
  private Optional<LoopTrigger> commitFixLeftovers(
      LoopFacts facts, LoopStep.CommitFixLeftovers step) {
    var spec = facts.spec().orElseThrow();
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
      return Optional.of(new LoopTrigger.FixNotCommitted(e.getMessage()));
    }
  }

  /**
   * A fix iteration that did not address the findings: said so loudly, with why, as {@code
   * review_iteration_failed} (parallel to {@code review_errored}), and escalated — there is no
   * re-review to run, because the branch still holds the code the reviewer just failed.
   */
  private Optional<LoopTrigger> failFix(LoopFacts facts, LoopStep.FailFix step) {
    var reviewId = step.review().id();
    System.err.println(
        "review-pipeline: fix iteration of review %s for spec %s failed: %s"
            .formatted(reviewId, facts.specId(), step.why()));
    narrator.publishEvent(facts.project(), facts.specId(), "review_iteration_failed", step.why());
    return escalate(facts, reviewId, ReviewNarration.fixFailed(step.why()));
  }

  /**
   * Hands the review to a person: its status, the reason recorded on its row, its stages closed,
   * its spec in {@code review} and the room line are one write ({@link ReviewStore#escalate}). The
   * reason rides the synced row, so main says what this box says, and travels as the event detail,
   * so Slack says why — not a one-size-fits-all line.
   */
  private Optional<LoopTrigger> escalate(LoopFacts facts, String reviewId, String reason) {
    var specId = facts.specId();
    System.err.println("review-pipeline: spec " + specId + " escalated — " + reason);
    reviewStore.escalate(
        reviewId,
        reason,
        () -> {
          moveSpec(specId, SpecStatus.REVIEW);
          narrator.appendRoom(specId, ReviewNarration.escalated(reason));
        });
    syncTrigger.run();
    narrator.publishEvent(facts.project(), specId, "review_escalated", reason);
    return Optional.empty();
  }
}
