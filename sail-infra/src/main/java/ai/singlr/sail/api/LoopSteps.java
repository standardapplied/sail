/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.ReviewPipelineConfig.StageConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.FindingParser;
import ai.singlr.sail.engine.FixTaskBuilder;
import ai.singlr.sail.engine.ReviewPromptBuilder;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Carries out the steps of the review loop, one method a step: the only place the loop writes a
 * review, a stage or a spec's status, and the only one that launches its agents. A step's writes
 * that end a review commit together, and what it says on the bus is said after the commit.
 *
 * <p>Nothing waits on an agent. A launch returns once the run is started ({@link ReviewLanes}), and
 * the loop hears how the run ended from its stop.
 */
final class LoopSteps {

  private final SpecStore specStore;
  private final ReviewStore reviewStore;
  private final ReviewLanes lanes;
  private final LoopFactsReader reader;
  private final EventBus eventBus;
  private final Runnable syncTrigger;
  private final Supplier<String> localHandle;
  private MessageStore messageStore;

  /**
   * @param syncTrigger fired after every state change the loop makes, so it reaches main at once
   * @param localHandle this box's FDE handle, under which the loop's runs are launched
   */
  LoopSteps(
      SpecStore specStore,
      ReviewStore reviewStore,
      ReviewLanes lanes,
      LoopFactsReader reader,
      EventBus eventBus,
      Runnable syncTrigger,
      Supplier<String> localHandle) {
    this.specStore = specStore;
    this.reviewStore = reviewStore;
    this.lanes = lanes;
    this.reader = reader;
    this.eventBus = eventBus;
    this.syncTrigger = syncTrigger;
    this.localHandle = localHandle;
  }

  void useMessages(MessageStore messages) {
    this.messageStore = messages;
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
      case LoopStep.Escalate escalated -> escalate(facts, escalated);
    };
  }

  private Optional<LoopTrigger> sayBuildFailed(LoopFacts facts, LoopStep.SayBuildFailed step) {
    publishEvent(
        facts.project(),
        facts.specId(),
        Event.WellKnownTypes.AGENT_FAILED,
        "exit " + step.exitCode());
    return Optional.empty();
  }

  private Optional<LoopTrigger> parkForAPerson(LoopFacts facts) {
    advanceSpec(facts.specId(), SpecStatus.REVIEW);
    return Optional.empty();
  }

  /**
   * Writes iteration {@code iteration} of the spec's review, {@code running}, and moves the spec to
   * review. The row is written before the spec moves, so no crash leaves a spec in {@code review}
   * with nothing owed.
   */
  private Optional<LoopTrigger> startReview(LoopFacts facts, LoopStep.StartReview step) {
    createReview(facts.specId(), step.iteration());
    advanceSpec(facts.specId(), SpecStatus.REVIEW);
    return Optional.of(new LoopTrigger.ReviewRunning());
  }

  private Optional<LoopTrigger> escalateNew(LoopFacts facts, LoopStep.EscalateNew step) {
    escalate(facts, createReview(facts.specId(), step.iteration()), step.reason());
    return Optional.empty();
  }

  /**
   * Goes on with a review no run serves. A review a legacy row left {@code pending} is running from
   * here on, so its reviewer's stop finds it.
   */
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
          "review-pipeline: spec "
              + specId
              + " no longer in a pipeline-owned status; not advancing it to "
              + status.wire());
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
  private List<ReviewStore.StageRow> stagesOf(LoopFacts facts, ReviewStore.ReviewRow review) {
    var reviewId = review.id();
    var config = facts.staged().config();
    var held = reviewStore.stagesForReview(reviewId).size();
    if (held >= config.stages().size()) {
      return reviewStore.stagesForReview(reviewId);
    }
    for (var stageConfig : config.stages().subList(held, config.stages().size())) {
      reviewStore.createStage(reviewId, stageConfig.name(), LoopFacts.stageType(stageConfig));
    }
    syncTrigger.run();
    return reviewStore.stagesForReview(reviewId);
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
    stagesOf(facts, review);
    var verdict =
        ReviewNarration.passed(
            review.iteration(),
            reviewStore.openFindingsForReview(review.id()),
            reviewStore.disputedFindings(facts.specId()));
    reviewStore.pass(
        review.id(),
        () -> {
          moveSpec(facts.specId(), SpecStatus.AWAITING_MERGE);
          appendRoom(facts.specId(), verdict);
        });
    syncTrigger.run();
    publishEvent(facts.project(), facts.specId(), "review_completed", null);
    return Optional.empty();
  }

  private Optional<LoopTrigger> awaitAPerson(LoopFacts facts, LoopStep.AwaitAPerson step) {
    var stage = stagesOf(facts, step.review()).get(step.stage());
    reviewStore.startStage(stage.id(), "human");
    publishEvent(facts.project(), facts.specId(), "review_stage_started", stage.name());
    postRoom(
        facts.specId(),
        ReviewNarration.awaitingHuman(reviewStore.disputedFindings(facts.specId())));
    syncTrigger.run();
    return Optional.empty();
  }

  /**
   * Starts an agent stage: its reviewer claims the spec's repos as a run that serves the review,
   * and only once that claim has landed does the stage row turn {@code running}, before the
   * reviewer's unit starts — so a stage is {@code running} only while a run exists for it, and the
   * reviewer a stage waits on is the run that was live when it started ({@link
   * LoopFacts#stageReviewedBy}). A claim the gate refuses writes nothing of the stage: it stays as
   * it was, and the review records the run it waits on. This box announces the stage started only
   * once a reviewer is running for it.
   */
  private Optional<LoopTrigger> launchReviewer(LoopFacts facts, LoopStep.LaunchReviewer step) {
    var stage = stagesOf(facts, step.review()).get(step.stage());
    var specId = facts.specId();
    var spec = facts.spec();
    var branch = spec.map(SpecStore.SpecRow::branch).orElse("main");
    var repos = spec.map(SpecStore.SpecRow::repos).orElse(List.of());
    var launched =
        launch(
            () -> {
              var carried =
                  reviewStore.carryForwardFindings(specId, stage.reviewId(), stage.name());
              var built =
                  ReviewPromptBuilder.build(
                      branch,
                      repos,
                      step.stageConfig().categories(),
                      roomMessages(specId),
                      carried);
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
                  spec.map(SpecStore.SpecRow::reasoningEffort).orElse(null),
                  built.renderedMessages().stream().map(MessageStore.MessageRow::id).toList());
            },
            stage.reviewId(),
            specId,
            () -> reviewStore.startStage(stage.id(), step.agent()));
    return switch (launched) {
      case Launched.Serving serving -> {
        publishEvent(facts.project(), specId, "review_stage_started", stage.name());
        yield Optional.empty();
      }
      case Launched.Waiting waiting -> Optional.empty();
      case Launched.Failed failed ->
          Optional.of(new LoopTrigger.LaunchFailed(Lane.REVIEW, step.stage(), failed.why()));
    };
  }

  /** How a launch left the review it serves. */
  private sealed interface Launched {

    /** A run now serves the review, and the loop waits for its stop. */
    record Serving() implements Launched {}

    /** The gate refused the claim: the review recorded the run it waits on. */
    record Waiting() implements Launched {}

    /** Nothing was started, and nothing will be: an infrastructure error naming why. */
    record Failed(String why) implements Launched {}
  }

  /**
   * Launches the run {@code invocation} describes for review {@code reviewId}, running {@code
   * claimed} once its claim has landed. A run that started is what the review waits on now, so any
   * wait it recorded is cleared. A claim the gate refused is recorded as the run that holds it, and
   * the room is told in the same write, so once per run waited on. A launch that fails after its
   * agent started — the launch command or the status read failing over a live unit — still left a
   * run serving the review, and the loop waits for that run's stop rather than call a working agent
   * a failure and act over it.
   */
  private Launched launch(
      Supplier<ReviewLanes.Invocation> invocation,
      String reviewId,
      String specId,
      Runnable claimed) {
    try {
      return switch (lanes.launch(invocation.get(), localHandle.get(), claimed)) {
        case ReviewLanes.Launch.Started started -> serving(reviewId);
        case ReviewLanes.Launch.Deferred deferred -> {
          var holder = deferred.holder();
          reviewStore.waitOn(
              reviewId, holder.runId(), () -> appendRoom(specId, ReviewNarration.waiting(holder)));
          syncTrigger.run();
          yield new Launched.Waiting();
        }
      };
    } catch (Exception e) {
      if (!reader.served(reviewId)) {
        return new Launched.Failed(reasonOf(e));
      }
      System.err.println(
          "review-pipeline: a launch for review "
              + reviewId
              + " reported a failure after its agent started ("
              + reasonOf(e)
              + "); waiting for that run's stop");
      return serving(reviewId);
    }
  }

  private Launched serving(String reviewId) {
    reviewStore.clearWait(reviewId);
    syncTrigger.run();
    return new Launched.Serving();
  }

  /**
   * Judges the stage a reviewer ran: one that did not end well is an infrastructure error saying
   * how it ended, and one that ended cleanly is judged on the findings in its own log.
   */
  private Optional<LoopTrigger> readVerdict(LoopFacts facts, LoopStep.ReadVerdict step) {
    var stage = facts.stages().get(step.stage());
    var outcome =
        step.error()
            .map(error -> errored(stage, error))
            .orElseGet(
                () ->
                    resolveStage(
                        stage, facts.staged().config().stages().get(step.stage()), step.run()));
    syncTrigger.run();
    return Optional.of(new LoopTrigger.StageJudged(step.stage(), outcome));
  }

  /** How an agent stage ended: gate verdicts are review outcomes; errors are infrastructure. */
  sealed interface StageOutcome {
    record Passed() implements StageOutcome {}

    record GateFailed() implements StageOutcome {}

    record Errored(String message) implements StageOutcome {}
  }

  private StageOutcome errored(ReviewStore.StageRow stage, String message) {
    reviewStore.completeStage(stage.id(), "failed", message);
    return new StageOutcome.Errored(message);
  }

  /**
   * Judges a stage on what its reviewer said: the findings parsed from that run's own log, applied
   * with the reviewer's rulings on the findings the stage carries, then weighed against the stage's
   * gate.
   */
  private StageOutcome resolveStage(
      ReviewStore.StageRow stage, StageConfig stageConfig, RunStore.RunRow run) {
    try {
      var parseResult = FindingParser.parse(lanes.output(run));
      if (parseResult instanceof FindingParser.ParseResult.Unparseable unparseable) {
        return errored(
            stage, "reviewer output unparseable: " + String.join("; ", unparseable.warnings()));
      }
      var parsed = (FindingParser.ParseResult.Parsed) parseResult;
      var carried = reviewStore.carryForwardFindings(run.specId(), stage.reviewId(), stage.name());
      applyStageResult(run.specId(), stage.id(), carried, parsed);

      var findings = reviewStore.findingsForStage(stage.id());
      var passed = stageConfig.gate().passes(findings);

      reviewStore.completeStage(stage.id(), passed ? "passed" : "failed");
      publishEvent(
          run.project(),
          run.specId(),
          passed ? "review_stage_passed" : "review_stage_failed",
          stage.name(),
          ReviewNarration.severityCounts(findings));

      return passed ? new StageOutcome.Passed() : new StageOutcome.GateFailed();
    } catch (Exception e) {
      System.err.println(
          "review-pipeline: agent stage '" + stage.name() + "' errored: " + e.getMessage());
      return errored(stage, e.getMessage());
    }
  }

  /**
   * Applies the reviewer's complete result — verdicts and new findings — as one atomic write,
   * fail-closed on both axes. Verdicts: {@code fixed} and {@code disputed} (both evidence-backed,
   * enforced by {@link FindingParser#reconcile}) resolve the predecessor row with the evidence
   * recorded; everything else — {@code still_open}, a missing verdict, a ruling without evidence —
   * re-attaches the finding to this stage as a carried row, so it keeps aging and keeps facing the
   * gate. A reviewer that stops mentioning last iteration's finding launders nothing. A finding a
   * human resolved while the stage ran keeps that resolution, and the room is told the ruling on it
   * was set aside — whether it was resolved before the reviewer's run ended, and so is no longer
   * among the findings this stage carries, or in the moment between. Atomicity: if any new finding
   * fails to insert, the resolutions roll back too, so an errored stage retries with every carried
   * finding still {@code OPEN} instead of silently retired.
   */
  private void applyStageResult(
      String specId,
      String stageId,
      List<Finding> carried,
      FindingParser.ParseResult.Parsed parsed) {
    var reconciled = FindingParser.reconcile(carried, parsed.verdicts());
    warn(reconciled.warnings());
    var resolvedMeanwhile =
        reconciled.unmatchedVerdictIds().stream().filter(this::resolvedFinding).count();
    var misaddressed = reconciled.unmatchedVerdictIds().size() - resolvedMeanwhile;
    if (misaddressed > 0) {
      postRoom(specId, ReviewNarration.misaddressedVerdicts((int) misaddressed));
    }
    var rulings =
        carried.stream()
            .map(
                finding -> {
                  var verdict = reconciled.rulings().get(finding.id());
                  return new ReviewStore.StageRuling(
                      finding, resolutionOf(verdict.ruling()), verdict.evidence());
                })
            .toList();
    var setAside =
        reviewStore.applyStageResult(stageId, rulings, parsed.findings()).size()
            + (int) resolvedMeanwhile;
    if (setAside > 0) {
      postRoom(specId, ReviewNarration.alreadyResolved(setAside));
    }
  }

  /**
   * Whether {@code findingId} names a finding someone resolved: the reviewer was asked to rule on
   * it when its stage began, and it left the carried set before the reviewer answered.
   */
  private boolean resolvedFinding(String findingId) {
    return reviewStore
        .findFinding(findingId)
        .filter(finding -> finding.resolution() != Finding.Resolution.OPEN)
        .isPresent();
  }

  private static Finding.Resolution resolutionOf(FindingParser.Ruling ruling) {
    return switch (ruling) {
      case FIXED -> Finding.Resolution.FIXED;
      case DISPUTED -> Finding.Resolution.DISPUTED;
      case STILL_OPEN -> Finding.Resolution.OPEN;
    };
  }

  private static void warn(List<String> warnings) {
    warnings.forEach(warning -> System.err.println("review-pipeline: " + warning));
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
    var reviewId = step.review().id();
    if (!step.closed()) {
      System.err.println("review-pipeline: agent stage '" + stage.name() + "': " + step.why());
      reviewStore.completeStage(stage.id(), "failed", step.why());
    }
    reviewStore.failReviewWithError(reviewId, step.why());
    syncTrigger.run();
    System.err.println(
        "review-pipeline: review "
            + reviewId
            + " for spec "
            + facts.specId()
            + " errored at stage '"
            + stage.name()
            + "': "
            + step.why());
    publishEvent(facts.project(), facts.specId(), "review_errored", step.why());
    return Optional.empty();
  }

  /**
   * A stage failed its gate: the review has failed and the room is told what it found. Its findings
   * go to a fix agent, or the spec escalates, as the loop goes on.
   */
  private Optional<LoopTrigger> failGate(LoopFacts facts, LoopStep.FailGate step) {
    var reviewId = step.review().id();
    reviewStore.updateReviewStatus(reviewId, "failed");
    postRoom(
        facts.specId(),
        ReviewNarration.failed(
            step.review().iteration(),
            reviewStore.openFindingsForReview(reviewId),
            reviewStore.disputedFindings(facts.specId())));
    return Optional.of(new LoopTrigger.GoOn());
  }

  /**
   * Hands a failed review's findings to the spec's own agent as a fix run that serves that review.
   * The run acts as itself — principal {@code <agent>/fix-<runId>} — so the room's audit trail
   * attributes its posts to the fix lane, never to the reviewer, and it runs with the stop gate
   * asking for a committed, pushed tree. Once the run exists the spec is back {@code in_progress}
   * and the room is told a fix iteration started; the loop then waits for its stop. A claim the
   * gate refuses changes nothing but the run the review waits on: the fix is still owed, and is
   * launched once that run has ended.
   */
  private Optional<LoopTrigger> launchFix(LoopFacts facts, LoopStep.LaunchFix step) {
    var spec = facts.spec().orElseThrow();
    var specId = facts.specId();
    var launched =
        launch(
            () -> {
              var built =
                  FixTaskBuilder.build(specId, spec.title(), step.findings(), roomMessages(specId));
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
                  built.renderedMessages().stream().map(MessageStore.MessageRow::id).toList());
            },
            step.review().id(),
            specId,
            () -> {});
    return switch (launched) {
      case Launched.Serving serving -> {
        advanceSpec(specId, SpecStatus.IN_PROGRESS);
        publishEvent(facts.project(), specId, "review_iteration_started", null);
        yield Optional.empty();
      }
      case Launched.Waiting waiting -> Optional.empty();
      case Launched.Failed failed ->
          Optional.of(new LoopTrigger.LaunchFailed(Lane.FIX, 0, failed.why()));
    };
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
   * Commits and pushes whatever the fix agent left uncommitted on the spec's branch — the gate is a
   * nudge, not a jail, and otherwise the re-review judges a branch without the fixes and the shared
   * clone carries the leftovers into the next dispatch.
   */
  private Optional<LoopTrigger> commitFixLeftovers(
      LoopFacts facts, LoopStep.CommitFixLeftovers step) {
    var spec = facts.spec().orElseThrow();
    try {
      var rescued =
          lanes.ensureCommitted(
              facts.project(),
              spec.repos(),
              spec.branch(),
              FixTaskBuilder.commitMessage(reviewStore.openFindingsForReview(step.review().id())));
      if (!rescued.isEmpty()) {
        publishGuardrail(
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
    System.err.println(
        "review-pipeline: fix iteration of review "
            + step.review().id()
            + " for spec "
            + facts.specId()
            + " failed: "
            + step.why());
    publishEvent(facts.project(), facts.specId(), "review_iteration_failed", step.why());
    escalate(facts, step.review().id(), ReviewNarration.fixFailed(step.why()));
    return Optional.empty();
  }

  private Optional<LoopTrigger> escalate(LoopFacts facts, LoopStep.Escalate step) {
    escalate(facts, step.review().id(), step.reason());
    return Optional.empty();
  }

  /**
   * Hands the review to a person: its status, the reason recorded on its row, its stages closed,
   * its spec in {@code review} and the room line are one write ({@link ReviewStore#escalate}). The
   * reason rides the synced row, so main says what this box says, and travels as the event detail,
   * so Slack says why — not a one-size-fits-all line.
   */
  private void escalate(LoopFacts facts, String reviewId, String reason) {
    System.err.println("review-pipeline: spec " + facts.specId() + " escalated — " + reason);
    reviewStore.escalate(
        reviewId,
        reason,
        () -> {
          moveSpec(facts.specId(), SpecStatus.REVIEW);
          appendRoom(facts.specId(), ReviewNarration.escalated(reason));
        });
    syncTrigger.run();
    publishEvent(facts.project(), facts.specId(), "review_escalated", reason);
  }

  /**
   * A failure in this handler used to be one journal line and a silently stranded spec — the review
   * never started and nothing downstream noticed (the field incident: a raced SQLite statement
   * killed the kickoff twice in one week). Publish it loudly so Slack shows the failure, and rely
   * on {@link MissedStopReconciler}'s replay to deliver the stop again on a later sweep. Publishing
   * must never mask the original failure.
   */
  void publishPipelineError(String project, String specId, Exception failure) {
    try {
      publishEvent(
          project,
          specId,
          "review_pipeline_error",
          Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()));
    } catch (RuntimeException e) {
      System.err.println("review-pipeline: could not publish pipeline error: " + e.getMessage());
    }
  }

  private String roomOf(String specId) {
    return specStore.findById(specId).map(SpecStore.SpecRow::roomIdOrIdentity).orElse(specId);
  }

  /**
   * The room's recent messages for the reviewer's prompt and the fix task. Best-effort: the
   * conversation enriches the prompt, it is not a precondition — a room that cannot be read must
   * degrade the review, never error it.
   */
  private List<MessageStore.MessageRow> roomMessages(String specId) {
    if (messageStore == null) {
      return List.of();
    }
    try {
      return messageStore.list(roomOf(specId), null, 20);
    } catch (RuntimeException e) {
      System.err.println(
          "review-pipeline: could not read the room of spec " + specId + ": " + e.getMessage());
      return List.of();
    }
  }

  /**
   * Posts the pipeline's own narration to the spec's room: the findings themselves, which events
   * cannot carry. This is also the loop's cross-iteration memory: the reviewer's prompt includes
   * the room's recent messages, so the next pass sees the previous verdict. Best-effort — a line
   * that tells of work still under way must never fail that work. The lines that end a review, or
   * record its wait, are written with what they tell of instead ({@link #appendRoom}).
   */
  private void postRoom(String specId, String body) {
    try {
      appendRoom(specId, body);
      syncTrigger.run();
    } catch (RuntimeException e) {
      System.err.println(
          "review-pipeline: could not post to the room of spec " + specId + ": " + e.getMessage());
    }
  }

  /**
   * Writes one line to the spec's room, cut to what a room message can hold ({@link
   * MessageStore#fitted}): a verdict or a reason is as long as its findings and its error text, and
   * a line too long to write must never undo the review's end it is written with.
   */
  private void appendRoom(String specId, String body) {
    if (messageStore != null) {
      messageStore.append(
          roomOf(specId), MessageStore.SAIL_AUTHOR, MessageStore.fitted(body), null);
    }
  }

  private void publishGuardrail(String project, String specId, String reason, String action) {
    if (eventBus == null) return;
    eventBus.publish(
        Event.of(
            project,
            specId,
            Event.WellKnownTypes.GUARDRAIL_TRIGGERED,
            Event.SAIL_AGENT,
            hostname(),
            Map.of("reason", reason, "action", action)));
  }

  private void publishEvent(String project, String specId, String type, String detail) {
    publishEvent(project, specId, type, detail, Map.of());
  }

  private void publishEvent(
      String project, String specId, String type, String detail, Map<String, Object> findings) {
    if (eventBus == null) return;
    var data = new LinkedHashMap<String, Object>();
    if (detail != null) {
      data.put("detail", detail);
    }
    if (!findings.isEmpty()) {
      data.put("findings", findings);
    }
    eventBus.publish(Event.of(project, specId, type, Event.SAIL_AGENT, hostname(), data));
  }

  private static String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "unknown";
    }
  }
}
