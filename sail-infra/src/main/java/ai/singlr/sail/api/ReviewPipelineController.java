/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.ReviewPipelineConfig.StageConfig;
import ai.singlr.sail.config.ReviewPipelineConfig.StageType;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AgentUnit;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Orchestrates the review pipeline when an agent completes a spec. Subscribes to the event bus,
 * creates review records with stages, executes agent review stages sequentially, evaluates gates,
 * and triggers fix iterations or escalation. A pass parks the spec in {@code awaiting_merge} — the
 * PR is open but unmerged, and only the human who merges it marks the spec {@code done}.
 *
 * <p>Agent execution is delegated to {@link ReviewAgentRunner}. This keeps the controller testable
 * without containers — tests inject a runner that returns canned agent output.
 *
 * <p>Review stages run in a virtual thread so the event bus drain is never blocked. The controller
 * is thread-safe: only one pipeline can run per spec at a time (checked via review status).
 */
public final class ReviewPipelineController implements EventSubscriber, AutoCloseable {

  private static final Set<String> TRIGGER_TYPES =
      Set.of(Event.WellKnownTypes.AGENT_SESSION_STOPPED);

  /**
   * How many errored (infrastructure-failed) review attempts of one iteration may accumulate before
   * the spec escalates. Errored attempts retry without burning an iteration, and the reconciler
   * replays a stop for each one — unbounded, that pair is an infinite loop of doomed reviews;
   * bounded, a transient failure self-heals and a persistent one surfaces to a human.
   */
  static final int MAX_ERRORED_RETRIES = 3;

  private final SpecStore specStore;
  private final ReviewStore reviewStore;
  private final Function<String, ReviewPipelineConfig> configResolver;
  private final Function<String, String> reviewerResolver;
  private final ReviewAgentRunner agentRunner;
  private final EventBus eventBus;
  private final Runnable syncTrigger;
  private final RunStore runStore;
  private final Supplier<String> localHandle;
  private MessageStore messageStore;
  private final ConcurrentHashMap<String, CompletableFuture<Void>> inFlight =
      new ConcurrentHashMap<>();
  private final ExecutorService pipelineExecutor;

  /**
   * @param reviewerResolver resolves a project's default reviewer agent (the
   *     installed-agent-that-isn't-the-coder, else the coder for self-review — see {@code
   *     AgentRoster.reviewer}) for stages that do not name one explicitly
   */
  public ReviewPipelineController(
      SpecStore specStore,
      ReviewStore reviewStore,
      Function<String, ReviewPipelineConfig> configResolver,
      Function<String, String> reviewerResolver,
      ReviewAgentRunner agentRunner,
      EventBus eventBus,
      Runnable syncTrigger) {
    this(
        specStore,
        reviewStore,
        configResolver,
        reviewerResolver,
        agentRunner,
        eventBus,
        syncTrigger,
        Executors.newVirtualThreadPerTaskExecutor(),
        null,
        null);
  }

  /**
   * Injectable-executor constructor. Production passes a virtual-thread-per-task executor so the
   * event-bus drain is never blocked; tests pass a same-thread executor so a pipeline runs to
   * completion synchronously within {@link #onEvent} — no latch, no completion race, deterministic
   * coverage.
   *
   * <p>{@code syncTrigger} is fired after every spec status transition the pipeline makes, so the
   * loop's own state changes (review, awaiting_merge, fix-iteration in_progress) propagate to main
   * immediately through the same sync-on-write seam manual edits use — main is the notification
   * authority and must see the loop advance without waiting for a periodic sync. On main and
   * standalone boxes it is a no-op.
   */
  ReviewPipelineController(
      SpecStore specStore,
      ReviewStore reviewStore,
      Function<String, ReviewPipelineConfig> configResolver,
      Function<String, String> reviewerResolver,
      ReviewAgentRunner agentRunner,
      EventBus eventBus,
      Runnable syncTrigger,
      ExecutorService pipelineExecutor) {
    this(
        specStore,
        reviewStore,
        configResolver,
        reviewerResolver,
        agentRunner,
        eventBus,
        syncTrigger,
        pipelineExecutor,
        null,
        null);
  }

  ReviewPipelineController(
      SpecStore specStore,
      ReviewStore reviewStore,
      Function<String, ReviewPipelineConfig> configResolver,
      Function<String, String> reviewerResolver,
      ReviewAgentRunner agentRunner,
      EventBus eventBus,
      Runnable syncTrigger,
      ExecutorService pipelineExecutor,
      RunStore runStore,
      Supplier<String> localHandle) {
    this.specStore = specStore;
    this.reviewStore = reviewStore;
    this.configResolver = configResolver;
    this.reviewerResolver = reviewerResolver;
    this.agentRunner = agentRunner;
    this.eventBus = eventBus;
    this.syncTrigger = Objects.requireNonNull(syncTrigger, "syncTrigger");
    this.pipelineExecutor = pipelineExecutor;
    this.runStore = runStore;
    this.localHandle = runStore == null ? null : Objects.requireNonNull(localHandle, "localHandle");
  }

  public ReviewPipelineController useMessages(MessageStore messages) {
    this.messageStore = Objects.requireNonNull(messages, "messages");
    return this;
  }

  /**
   * Advances a spec's status and signals sync so the transition reaches main. Compare-and-set from
   * the two states the pipeline owns ({@code in_progress}, {@code review}): once any other
   * transition wins — above all an operator's {@code cancelled}, which is terminal — the pipeline's
   * stale write is dropped instead of resurrecting the spec.
   */
  private void advanceSpec(String specId, SpecStatus status) {
    var advanced =
        specStore.compareAndSetStatus(specId, SpecStatus.IN_PROGRESS, status)
            || specStore.compareAndSetStatus(specId, SpecStatus.REVIEW, status);
    if (!advanced) {
      System.err.println(
          "review-pipeline: spec "
              + specId
              + " no longer in a pipeline-owned status; not advancing it to "
              + status.wire());
      return;
    }
    syncTrigger.run();
  }

  /**
   * Waits for all in-flight pipeline executions to complete. Call before closing the database or
   * shutting down the server.
   */
  public void awaitCompletion(long timeoutMillis) throws InterruptedException {
    var futures = List.copyOf(inFlight.values());
    if (futures.isEmpty()) return;
    try {
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
          .get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (ExecutionException | TimeoutException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() {
    try {
      awaitCompletion(5000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      pipelineExecutor.close();
    }
  }

  ExecutorService pipelineExecutor() {
    return pipelineExecutor;
  }

  @Override
  public String name() {
    return "review-pipeline";
  }

  @Override
  public Predicate<Event> filter() {
    return e -> TRIGGER_TYPES.contains(e.type()) && e.spec() != null && !e.spec().isBlank();
  }

  @Override
  public void onEvent(Event event) {
    try {
      handleAgentStopped(event);
    } catch (Exception e) {
      System.err.println(
          "review-pipeline: failed to process "
              + event.type()
              + " for spec "
              + event.spec()
              + ": "
              + e.getMessage());
      publishPipelineError(event, e);
    }
  }

  /**
   * A failure in this handler used to be one journal line and a silently stranded spec — the review
   * never started and nothing downstream noticed (the field incident: a raced SQLite statement
   * killed the kickoff twice in one week). Publish it loudly so Slack shows the failure, and rely
   * on {@link MissedStopReconciler}'s rescue replay to retry the kickoff on the next sweep.
   * Publishing must never mask the original failure.
   */
  private void publishPipelineError(Event event, Exception failure) {
    try {
      publishEvent(
          event.project(),
          event.spec(),
          "review_pipeline_error",
          Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()));
    } catch (RuntimeException e) {
      System.err.println("review-pipeline: could not publish pipeline error: " + e.getMessage());
    }
  }

  private void handleAgentStopped(Event event) {
    var specId = event.spec();
    var spec = specStore.findById(specId);
    if (spec.isEmpty()) return;

    if (!reviewable(spec.get().status())) return;

    if (!isAuthoritative(event)) return;

    var exitCode = exitCodeOf(event);
    if (exitCode != null && exitCode != 0) {
      publishEvent(event.project(), specId, Event.WellKnownTypes.AGENT_FAILED, "exit " + exitCode);
      return;
    }

    advanceSpec(specId, SpecStatus.REVIEW);

    var config = configResolver.apply(event.project());
    if (config == null || config.stages().isEmpty()) return;

    var existing = reviewStore.latestReviewForSpec(specId);
    if (existing.isPresent() && "running".equals(existing.get().status())) {
      System.err.println(
          "review-pipeline: skipping review for spec "
              + specId
              + " — review "
              + existing.get().id()
              + " is already running");
      return;
    }

    var iteration = existing.map(ReviewPipelineController::nextIteration).orElse(1);
    if (iteration > config.maxIterations()) {
      var reason =
          "review iterations exhausted ("
              + config.maxIterations()
              + "); re-dispatch with --restart to start a fresh attempt";
      System.err.println("review-pipeline: spec " + specId + " escalated — " + reason);
      escalate(event.project(), specId, existing.get().id(), reason);
      return;
    }

    if (existing.isPresent()
        && existing.get().errored()
        && erroredAttempts(specId, iteration) >= MAX_ERRORED_RETRIES) {
      var reason =
          MAX_ERRORED_RETRIES
              + " review attempts errored in a row at iteration "
              + iteration
              + "; fix the reviewer, then re-dispatch with --restart";
      System.err.println("review-pipeline: spec " + specId + " escalated — " + reason);
      escalate(event.project(), specId, existing.get().id(), reason);
      return;
    }

    var reviewId = createReviewWithStages(config, specId, iteration);
    var future =
        CompletableFuture.runAsync(
            () -> executePipeline(reviewId, config, event.project(), specId), pipelineExecutor);
    inFlight.put(reviewId, future);
    future.whenComplete((v, ex) -> inFlight.remove(reviewId));
  }

  /**
   * Whether an authoritative stop for a spec in this status should kick off a review. Both {@code
   * in_progress} (the normal path) and {@code review} qualify: a spec can be moved to {@code
   * review} out of band — a manual edit, or a sync revision from another box — while its agent is
   * still running here, and the old {@code == IN_PROGRESS} guard then dropped the real stop and
   * stranded the spec in {@code review} with no review ever created. Terminal and pre-dispatch
   * states ({@code done}, {@code awaiting_merge}, {@code archived}, {@code draft}, {@code pending})
   * are not reviewable, so their stops are ignored.
   */
  private static boolean reviewable(SpecStatus status) {
    return status == SpecStatus.IN_PROGRESS || status == SpecStatus.REVIEW;
  }

  private String createReviewWithStages(ReviewPipelineConfig config, String specId, int iteration) {
    var reviewId = reviewStore.createReview(specId, iteration);
    reviewStore.updateReviewStatus(reviewId, "running");
    for (var stageConfig : config.stages()) {
      reviewStore.createStage(
          reviewId, stageConfig.name(), stageConfig.type().name().toLowerCase());
    }
    syncTrigger.run();
    return reviewId;
  }

  void executePipeline(
      String reviewId, ReviewPipelineConfig config, String project, String specId) {
    try {
      var stages = reviewStore.stagesForReview(reviewId);
      var stageConfigs = config.stages();

      for (var i = 0; i < stages.size() && i < stageConfigs.size(); i++) {
        var stage = stages.get(i);
        var stageConfig = stageConfigs.get(i);

        if (stageConfig.type() == StageType.HUMAN) {
          reviewStore.startStage(stage.id(), "human");
          publishEvent(project, specId, "review_stage_started", stage.name());
          return;
        }

        var outcome = executeAgentStage(stage, stageConfig, project, specId);
        syncTrigger.run();
        if (outcome instanceof StageOutcome.Errored errored) {
          handleStageError(reviewId, project, specId, stage.name(), errored.message());
          return;
        }
        if (outcome instanceof StageOutcome.GateFailed) {
          handleStageFailure(reviewId, config, project, specId);
          return;
        }
      }

      reviewStore.updateReviewStatus(reviewId, "passed");
      advanceSpec(specId, SpecStatus.AWAITING_MERGE);
      postRoom(specId, passedVerdict(reviewId));
      publishEvent(project, specId, "review_completed", null);

    } catch (Exception e) {
      System.err.println(
          "review-pipeline: pipeline execution failed for review "
              + reviewId
              + ": "
              + e.getMessage());
      reviewStore.updateReviewStatus(reviewId, "failed");
      syncTrigger.run();
      failReviewRun(reviewId, e);
    } finally {
      completeReviewRun(reviewId);
    }
  }

  /** How an agent stage ended: gate verdicts are review outcomes; errors are infrastructure. */
  sealed interface StageOutcome {
    record Passed() implements StageOutcome {}

    record GateFailed() implements StageOutcome {}

    record Errored(String message) implements StageOutcome {}
  }

  private StageOutcome executeAgentStage(
      ReviewStore.StageRow stage, StageConfig stageConfig, String project, String specId) {
    var agent = stageConfig.agent() != null ? stageConfig.agent() : reviewerResolver.apply(project);
    if (agent == null) {
      var message = "no reviewer agent resolved; set stages[].agent or agent.install in sail.yaml";
      reviewStore.completeStage(stage.id(), "failed", message);
      return new StageOutcome.Errored(message);
    }
    reviewStore.startStage(stage.id(), agent);
    publishEvent(project, specId, "review_stage_started", stage.name());

    try {
      var spec = specStore.findById(specId);
      var branch = spec.map(SpecStore.SpecRow::branch).orElse("main");
      var repos = spec.map(SpecStore.SpecRow::repos).orElse(List.of());
      var prompt =
          ReviewPromptBuilder.build(branch, repos, stageConfig.categories(), roomMessages(specId));

      var credential = startReviewRun(stage.reviewId(), project, specId, agent, branch, prompt);
      var effort = spec.map(SpecStore.SpecRow::reasoningEffort).orElse(null);
      var output =
          agentRunner.run(project, agent, prompt, stage.reviewId(), credential, null, effort);
      var parseResult = FindingParser.parse(output);
      if (parseResult.findings().isEmpty() && !parseResult.warnings().isEmpty()) {
        var message = "reviewer output unparseable: " + String.join("; ", parseResult.warnings());
        reviewStore.completeStage(stage.id(), "failed", message);
        return new StageOutcome.Errored(message);
      }

      for (var finding : parseResult.findings()) {
        reviewStore.addFinding(stage.id(), finding);
      }

      var findings = reviewStore.findingsForStage(stage.id());
      var passed = stageConfig.gate().passes(findings);

      reviewStore.completeStage(stage.id(), passed ? "passed" : "failed");
      publishEvent(
          project,
          specId,
          passed ? "review_stage_passed" : "review_stage_failed",
          stage.name(),
          severityCounts(findings));

      return passed ? new StageOutcome.Passed() : new StageOutcome.GateFailed();

    } catch (Exception e) {
      System.err.println(
          "review-pipeline: agent stage '" + stage.name() + "' errored: " + e.getMessage());
      reviewStore.completeStage(stage.id(), "failed", e.getMessage());
      failReviewRun(stage.reviewId(), e);
      return new StageOutcome.Errored(e.getMessage());
    }
  }

  /**
   * An errored stage is an infrastructure failure, not a review verdict: record why on the review,
   * say so loudly, and stop — without a fix iteration (there are no findings to fix) and without
   * counting against {@code max_iterations} (the next stop retries the same iteration; see {@link
   * #nextIteration}).
   */
  private void handleStageError(
      String reviewId, String project, String specId, String stageName, String message) {
    reviewStore.failReviewWithError(reviewId, message);
    System.err.println(
        "review-pipeline: review "
            + reviewId
            + " for spec "
            + specId
            + " errored at stage '"
            + stageName
            + "': "
            + message);
    publishEvent(project, specId, "review_errored", message);
  }

  /** The iteration the next review runs as: errored iterations are retried, not burned. */
  private static int nextIteration(ReviewStore.ReviewRow latest) {
    return latest.errored() ? latest.iteration() : latest.iteration() + 1;
  }

  private void handleStageFailure(
      String reviewId, ReviewPipelineConfig config, String project, String specId) {
    reviewStore.updateReviewStatus(reviewId, "failed");

    var review = reviewStore.findReview(reviewId);
    if (review.isEmpty()) return;

    var openFindings = reviewStore.openFindingsForReview(reviewId);
    postRoom(specId, failedVerdict(review.get().iteration(), openFindings));

    if (review.get().iteration() >= config.maxIterations()) {
      escalate(
          project,
          specId,
          reviewId,
          "review iterations exhausted (" + config.maxIterations() + ")");
      return;
    }

    if (openFindings.isEmpty()) return;

    triggerFixIteration(reviewId, specId, openFindings, project);
    reReview(config, project, specId, review.get().iteration() + 1);
  }

  /**
   * Closes the review→fix→re-review loop: after the coding agent addresses the findings, run the
   * review again as the next iteration. Synchronous and bounded — each pass increments the
   * iteration and {@link #handleStageFailure} escalates instead of recursing once {@code
   * maxIterations} is reached.
   */
  private void reReview(ReviewPipelineConfig config, String project, String specId, int iteration) {
    advanceSpec(specId, SpecStatus.REVIEW);
    var reviewId = createReviewWithStages(config, specId, iteration);
    executePipeline(reviewId, config, project, specId);
  }

  /**
   * The fix agent runs under the failed review's identity, appending to that review's log. It runs
   * with the stop-readiness gate armed ({@link ReviewAgentRunner#runFix}) so it cannot end its turn
   * with a dirty tree, and {@link ReviewAgentRunner#ensureCommitted} stays as the deterministic
   * backstop — otherwise the re-review judges a branch without the fixes and the shared clone
   * carries the leftovers into the next dispatch.
   */
  private void triggerFixIteration(
      String reviewId, String specId, List<Finding> findings, String project) {
    var spec = specStore.findById(specId);
    if (spec.isEmpty()) return;

    var fixTask = FixTaskBuilder.build(spec.get().title(), findings);
    advanceSpec(specId, SpecStatus.IN_PROGRESS);
    publishEvent(project, specId, "review_iteration_started", null);

    try {
      var agent = spec.get().agent() != null ? spec.get().agent() : "claude-code";
      var credential =
          startReviewRun(reviewId, project, specId, agent, spec.get().branch(), fixTask);
      agentRunner.runFix(
          project,
          agent,
          fixTask,
          reviewId,
          credential,
          spec.get().branch(),
          spec.get().repos(),
          spec.get().model(),
          spec.get().reasoningEffort());
      var rescued =
          agentRunner.ensureCommitted(
              project,
              spec.get().repos(),
              spec.get().branch(),
              FixTaskBuilder.commitMessage(findings));
      if (!rescued.isEmpty()) {
        publishGuardrail(
            project,
            specId,
            "fix agent left uncommitted changes in " + describeRescues(rescued),
            "committed and pushed them to " + spec.get().branch());
      }
    } catch (Exception e) {
      failReviewRun(reviewId, e);
      System.err.println(
          "review-pipeline: fix iteration failed for spec " + specId + ": " + e.getMessage());
    }
    completeReviewRun(reviewId);
  }

  /** Errored attempts of this iteration in the current dispatch attempt — the retry budget. */
  private long erroredAttempts(String specId, int iteration) {
    return reviewStore.reviewsForSpec(specId).stream()
        .filter(r -> !r.superseded() && r.errored() && r.iteration() == iteration)
        .count();
  }

  /**
   * Posts the pipeline's own narration to the spec's room. Lifecycle beats (stage started, fix
   * iteration, guardrail, escalation) already ride the event stream; the room carries only what
   * events cannot — the findings themselves. This is also the loop's cross-iteration memory: the
   * reviewer's prompt includes the room's recent messages, so the next pass sees the previous
   * verdict. Best-effort by design — a room write must never fail the pipeline.
   */
  /**
   * The room's recent messages for the reviewer's prompt. Best-effort like {@link #postRoom}: the
   * conversation enriches the prompt, it is not a precondition — a dead message store must degrade
   * the review, never error it.
   */
  private List<MessageStore.MessageRow> roomMessages(String specId) {
    if (messageStore == null) {
      return List.of();
    }
    try {
      return messageStore.list(specId, null, 20);
    } catch (RuntimeException e) {
      System.err.println(
          "review-pipeline: could not read the room of spec " + specId + ": " + e.getMessage());
      return List.of();
    }
  }

  private void postRoom(String specId, String body) {
    if (messageStore == null) return;
    try {
      messageStore.append(specId, Event.SAIL_AGENT, body, null);
      syncTrigger.run();
    } catch (RuntimeException e) {
      System.err.println(
          "review-pipeline: could not post to the room of spec " + specId + ": " + e.getMessage());
    }
  }

  private static String failedVerdict(int iteration, List<Finding> findings) {
    return "Review failed (iteration "
        + iteration
        + "): "
        + severitySummary(findings)
        + "."
        + findingLines(findings);
  }

  private String passedVerdict(String reviewId) {
    var iteration =
        reviewStore.findReview(reviewId).map(ReviewStore.ReviewRow::iteration).orElse(0);
    var open =
        reviewStore.findingsForReview(reviewId).stream()
            .filter(f -> f.resolution() == Finding.Resolution.OPEN)
            .toList();
    if (open.isEmpty()) {
      return "Review passed (iteration " + iteration + "). Awaiting merge.";
    }
    return "Review passed (iteration "
        + iteration
        + ") with "
        + severitySummary(open)
        + " below the gate — worth a look before merge."
        + findingLines(open)
        + "\nAwaiting merge.";
  }

  /** {@code "2 high, 1 low"} in severity order, or {@code "no findings"}. */
  private static String severitySummary(List<Finding> findings) {
    var summary =
        severityCounts(findings).entrySet().stream()
            .map(e -> e.getValue() + " " + e.getKey())
            .reduce((a, b) -> a + ", " + b)
            .orElse("");
    return summary.isEmpty() ? "no findings" : summary;
  }

  /** One line per finding, capped so a noisy review stays one readable room message. */
  private static String findingLines(List<Finding> findings) {
    if (findings.isEmpty()) {
      return "";
    }
    var shown =
        findings.stream()
            .limit(10)
            .map(
                f ->
                    "- ["
                        + f.severity()
                        + "] "
                        + f.title()
                        + (f.file() == null ? "" : " (" + f.file() + ":" + f.lineStart() + ")"))
            .reduce((a, b) -> a + "\n" + b)
            .orElse("");
    var more = findings.size() - Math.min(findings.size(), 10);
    return "\n" + shown + (more > 0 ? "\n- +" + more + " more" : "");
  }

  /**
   * Names what each rescue swept — {@code "api (3 files: A.java, B.java, C.java)"} — capped so a
   * large sweep stays one readable notification line while still revealing debris immediately.
   */
  private static String describeRescues(List<ReviewAgentRunner.Rescue> rescued) {
    return rescued.stream()
        .map(
            rescue -> {
              var files = rescue.files();
              var shown = files.stream().limit(5).toList();
              var suffix = files.size() > shown.size() ? ", +" + (files.size() - shown.size()) : "";
              return "%s (%d file%s: %s%s)"
                  .formatted(
                      rescue.repo(),
                      files.size(),
                      files.size() == 1 ? "" : "s",
                      String.join(", ", shown),
                      suffix);
            })
        .reduce((a, b) -> a + ", " + b)
        .orElse("");
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

  /**
   * Ensures the review run exists and returns a live plaintext credential for it — created with the
   * row on the reviewer's first invocation, rotated when the fix lane rejoins the still-open
   * negotiation (the original plaintext is hashed at rest and unrecoverable). Blank without a run
   * store, launching the agent without an identity.
   */
  private String startReviewRun(
      String reviewId, String project, String specId, String agent, String branch, String task) {
    if (runStore == null) {
      return "";
    }
    if (runStore.findById(reviewId).isPresent()) {
      return runStore.rotateCredential(reviewId);
    }
    var unit = AgentUnit.forReview(reviewId);
    var owner =
        specStore.findById(specId).map(SpecStore.SpecRow::assignee).orElse(localHandle.get());
    var credential =
        runStore.createReview(
            reviewId,
            project,
            specId,
            localHandle.get(),
            owner,
            agent,
            branch,
            task,
            unit.logPath(),
            unit.unitName());
    syncTrigger.run();
    return credential;
  }

  private void failReviewRun(String reviewId, Exception failure) {
    if (runStore == null) {
      return;
    }
    var exitCode =
        failure instanceof ReviewAgentExecutionException execution ? execution.exitCode() : null;
    runStore
        .findById(reviewId)
        .filter(run -> "running".equals(run.status()))
        .ifPresent(run -> completeReviewRun(reviewId, "failed", exitCode));
  }

  private void completeReviewRun(String reviewId) {
    if (runStore == null) {
      return;
    }
    runStore
        .findById(reviewId)
        .filter(run -> "running".equals(run.status()))
        .ifPresent(run -> completeReviewRun(reviewId, "completed", 0));
  }

  private void completeReviewRun(String reviewId, String status, Integer exitCode) {
    runStore.complete(reviewId, status, exitCode);
    syncTrigger.run();
  }

  /** The reason travels as the event detail, so Slack says why — not a one-size-fits-all line. */
  private void escalate(String project, String specId, String reviewId, String reason) {
    reviewStore.updateReviewStatus(reviewId, "escalated");
    advanceSpec(specId, SpecStatus.REVIEW);
    publishEvent(project, specId, "review_escalated", reason);
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

  /** Finding counts keyed by lowercase severity, omitting zero severities — for event payloads. */
  private static Map<String, Object> severityCounts(List<Finding> findings) {
    var counts = new LinkedHashMap<String, Object>();
    for (var severity : Finding.Severity.values()) {
      var count = findings.stream().filter(f -> f.severity() == severity).count();
      if (count > 0) {
        counts.put(severity.name().toLowerCase(Locale.ROOT), (int) count);
      }
    }
    return counts;
  }

  /**
   * Whether this stop is the real termination, not a mid-run turn-end. The in-container agent hook
   * fires {@code Stop} when a turn ends — before the process exits and with no exit code — so the
   * controller waits for the watcher's poll-derived stop (which carries a {@code source}) rather
   * than reviewing on a turn boundary, or on a crash the hook can't report an exit code for. A
   * sync-derived stop is narration, not execution: it describes an agent that ran on another box,
   * whose own controller drives the review there — kicking a pipeline here would review the wrong
   * box's checkout.
   */
  private static boolean isAuthoritative(Event event) {
    var source = event.data().get(Event.WellKnownData.SOURCE);
    return source != null && !Event.WellKnownData.SOURCE_SYNC.equals(source);
  }

  private static Integer exitCodeOf(Event event) {
    return event.data().get(Event.WellKnownData.EXIT_CODE) instanceof Number n
        ? n.intValue()
        : null;
  }

  private static String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "unknown";
    }
  }
}
