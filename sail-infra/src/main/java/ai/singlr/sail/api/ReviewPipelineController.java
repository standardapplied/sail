/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.ReviewPipelineConfig.StageConfig;
import ai.singlr.sail.config.ReviewPipelineConfig.StageType;
import ai.singlr.sail.engine.FindingParser;
import ai.singlr.sail.engine.FixTaskBuilder;
import ai.singlr.sail.engine.ReviewPromptBuilder;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Orchestrates the review pipeline when an agent completes a spec. Subscribes to the event bus,
 * creates review records with stages, executes agent review stages sequentially, evaluates gates,
 * and triggers fix iterations or escalation.
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

  private final SpecStore specStore;
  private final ReviewStore reviewStore;
  private final Function<String, ReviewPipelineConfig> configResolver;
  private final ReviewAgentRunner agentRunner;
  private final EventBus eventBus;
  private final java.util.concurrent.ConcurrentHashMap<
          String, java.util.concurrent.CompletableFuture<Void>>
      inFlight = new java.util.concurrent.ConcurrentHashMap<>();

  public ReviewPipelineController(
      SpecStore specStore,
      ReviewStore reviewStore,
      Function<String, ReviewPipelineConfig> configResolver,
      ReviewAgentRunner agentRunner,
      EventBus eventBus) {
    this.specStore = specStore;
    this.reviewStore = reviewStore;
    this.configResolver = configResolver;
    this.agentRunner = agentRunner;
    this.eventBus = eventBus;
  }

  /**
   * Waits for all in-flight pipeline executions to complete. Call before closing the database or
   * shutting down the server.
   */
  public void awaitCompletion(long timeoutMillis) throws InterruptedException {
    var futures = List.copyOf(inFlight.values());
    if (futures.isEmpty()) return;
    try {
      java.util.concurrent.CompletableFuture.allOf(
              futures.toArray(new java.util.concurrent.CompletableFuture[0]))
          .get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() {
    try {
      awaitCompletion(5000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
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
    }
  }

  private void handleAgentStopped(Event event) {
    var specId = event.spec();
    var spec = specStore.findById(specId);
    if (spec.isEmpty()) return;

    if (!"in_progress".equals(spec.get().status())) return;

    specStore.updateStatus(specId, "review");

    var config = configResolver.apply(event.project());
    if (config == null || config.stages().isEmpty()) return;

    var existing = reviewStore.latestReviewForSpec(specId);
    if (existing.isPresent() && "running".equals(existing.get().status())) return;

    var iteration = existing.map(r -> r.iteration() + 1).orElse(1);
    if (iteration > config.maxIterations()) {
      escalate(specId, existing.get().id());
      return;
    }

    var reviewId = reviewStore.createReview(specId, iteration);
    reviewStore.updateReviewStatus(reviewId, "running");

    for (var stageConfig : config.stages()) {
      reviewStore.createStage(
          reviewId, stageConfig.name(), stageConfig.type().name().toLowerCase());
    }

    var future =
        java.util.concurrent.CompletableFuture.runAsync(
            () -> executePipeline(reviewId, config, event.project(), specId),
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    inFlight.put(reviewId, future);
    future.whenComplete((v, ex) -> inFlight.remove(reviewId));
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

        var passed = executeAgentStage(stage, stageConfig, project, specId);
        if (!passed) {
          handleStageFailure(reviewId, config, project, specId);
          return;
        }
      }

      reviewStore.updateReviewStatus(reviewId, "passed");
      specStore.updateStatus(specId, "done");
      publishEvent(project, specId, "review_completed", null);

    } catch (Exception e) {
      System.err.println(
          "review-pipeline: pipeline execution failed for review "
              + reviewId
              + ": "
              + e.getMessage());
      reviewStore.updateReviewStatus(reviewId, "failed");
    }
  }

  private boolean executeAgentStage(
      ReviewStore.StageRow stage, StageConfig stageConfig, String project, String specId) {
    var agent = stageConfig.agent() != null ? stageConfig.agent() : "codex";
    reviewStore.startStage(stage.id(), agent);
    publishEvent(project, specId, "review_stage_started", stage.name());

    try {
      var spec = specStore.findById(specId);
      var branch = spec.map(SpecStore.SpecRow::branch).orElse("main");
      var prompt = ReviewPromptBuilder.build(branch, project, stageConfig.categories());

      var output = agentRunner.run(project, agent, prompt);
      var parseResult = FindingParser.parse(output);

      for (var finding : parseResult.findings()) {
        reviewStore.addFinding(stage.id(), finding);
      }

      var findings = reviewStore.findingsForStage(stage.id());
      var passed = stageConfig.gate().passes(findings);

      reviewStore.completeStage(stage.id(), passed ? "passed" : "failed");
      publishEvent(
          project, specId, passed ? "review_stage_passed" : "review_stage_failed", stage.name());

      return passed;

    } catch (Exception e) {
      System.err.println(
          "review-pipeline: agent stage '" + stage.name() + "' failed: " + e.getMessage());
      reviewStore.completeStage(stage.id(), "failed");
      return false;
    }
  }

  private void handleStageFailure(
      String reviewId, ReviewPipelineConfig config, String project, String specId) {
    reviewStore.updateReviewStatus(reviewId, "failed");

    var review = reviewStore.findReview(reviewId);
    if (review.isEmpty()) return;

    if (review.get().iteration() >= config.maxIterations()) {
      escalate(specId, reviewId);
      return;
    }

    var openFindings = reviewStore.openFindingsForReview(reviewId);
    if (openFindings.isEmpty()) return;

    triggerFixIteration(specId, openFindings, project);
  }

  private void triggerFixIteration(String specId, List<Finding> findings, String project) {
    var spec = specStore.findById(specId);
    if (spec.isEmpty()) return;

    var fixTask = FixTaskBuilder.build(spec.get().title(), findings);
    specStore.updateStatus(specId, "in_progress");
    publishEvent(project, specId, "review_iteration_started", null);

    try {
      var agent = spec.get().agent() != null ? spec.get().agent() : "claude-code";
      agentRunner.run(project, agent, fixTask);
    } catch (Exception e) {
      System.err.println(
          "review-pipeline: fix iteration failed for spec " + specId + ": " + e.getMessage());
    }
  }

  private void escalate(String specId, String reviewId) {
    reviewStore.updateReviewStatus(reviewId, "escalated");
    specStore.updateStatus(specId, "review");
    publishEvent(null, specId, "review_escalated", null);
  }

  private void publishEvent(String project, String specId, String type, String detail) {
    if (eventBus == null) return;
    var data =
        detail != null
            ? java.util.Map.<String, Object>of("detail", detail)
            : java.util.Map.<String, Object>of();
    eventBus.publish(Event.of(project, specId, type, Event.SAIL_AGENT, hostname(), data));
  }

  private static String hostname() {
    try {
      return java.net.InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "unknown";
    }
  }
}
