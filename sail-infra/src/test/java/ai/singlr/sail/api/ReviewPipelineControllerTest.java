/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewPipelineControllerTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SpecStore specStore;
  private ReviewStore reviewStore;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    specStore = new SpecStore(db);
    reviewStore = new ReviewStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private void createSpec(String id, String status) {
    specStore.create(
        new SpecStore.SpecRow(
            id,
            "test-project",
            "Test spec",
            SpecStatus.fromWire(status),
            null,
            null,
            null,
            null,
            "feat/test",
            0,
            null,
            "",
            "",
            null,
            List.of(),
            List.of()));
  }

  private Event agentStoppedEvent(String specId) {
    return Event.of(
        "test-project", specId, Event.WellKnownTypes.AGENT_SESSION_STOPPED, "claude-code", "host");
  }

  private Event agentStoppedEvent(String specId, int exitCode) {
    return Event.of(
        "test-project",
        specId,
        Event.WellKnownTypes.AGENT_SESSION_STOPPED,
        "claude-code",
        "host",
        Map.of("exit_code", exitCode, "source", "watcher"));
  }

  private ReviewPipelineConfig singleAgentStage(String gate) {
    return ReviewPipelineConfig.fromMap(
        Map.of(
            "max_iterations",
            3,
            "stages",
            List.of(
                Map.of(
                    "name",
                    "security",
                    "type",
                    "agent",
                    "agent",
                    "codex",
                    "categories",
                    List.of("security"),
                    "gate",
                    gate))));
  }

  private ReviewPipelineConfig twoAgentStages() {
    return ReviewPipelineConfig.fromMap(
        Map.of(
            "max_iterations",
            3,
            "stages",
            List.of(
                Map.of(
                    "name",
                    "security",
                    "type",
                    "agent",
                    "agent",
                    "codex",
                    "categories",
                    List.of("security"),
                    "gate",
                    "no_critical"),
                Map.of(
                    "name",
                    "correctness",
                    "type",
                    "agent",
                    "agent",
                    "codex",
                    "categories",
                    List.of("logic"),
                    "gate",
                    "no_critical"))));
  }

  private ReviewPipelineConfig agentThenHuman() {
    return ReviewPipelineConfig.fromMap(
        Map.of(
            "stages",
            List.of(
                Map.of(
                    "name", "security", "type", "agent", "agent", "codex", "gate", "no_critical"),
                Map.of("name", "human", "type", "human"))));
  }

  private ReviewPipelineConfig singleStageNoAgent(String gate) {
    return ReviewPipelineConfig.fromMap(
        Map.of(
            "stages",
            List.of(
                Map.of(
                    "name",
                    "security",
                    "type",
                    "agent",
                    "categories",
                    List.of("security"),
                    "gate",
                    gate))));
  }

  private ReviewPipelineController controller(
      ReviewPipelineConfig config, ReviewAgentRunner runner) {
    return controller(p -> config, p -> "codex", runner, null);
  }

  private ReviewPipelineController controller(
      Function<String, ReviewPipelineConfig> config,
      Function<String, String> reviewer,
      ReviewAgentRunner runner,
      EventBus bus) {
    return new ReviewPipelineController(
        specStore, reviewStore, config, reviewer, runner, bus, new DirectExecutorService());
  }

  @Test
  void skipsAnUnknownSpec() {
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> "[]");

    ctrl.onEvent(agentStoppedEvent("ghost"));

    assertTrue(reviewStore.latestReviewForSpec("ghost").isEmpty());
  }

  @Test
  void stageWithoutAnAgentUsesTheRosterReviewer() {
    createSpec("auth", "in_progress");
    var capturedAgent = new AtomicReference<String>();
    ReviewAgentRunner capturing =
        (p, a, prompt) -> {
          capturedAgent.set(a);
          return "[]";
        };
    var ctrl =
        controller(p -> singleStageNoAgent("no_critical"), p -> "claude-code", capturing, null);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals("claude-code", capturedAgent.get());
  }

  @Test
  void stageFailsWhenNoReviewerIsAvailable() {
    createSpec("auth", "in_progress");
    var ctrl =
        controller(p -> singleStageNoAgent("no_critical"), p -> null, (p, a, pr) -> "[]", null);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("failed", reviewStore.stagesForReview(review.id()).getFirst().status());
  }

  @Test
  void reusesOneExecutorAcrossEventsAndShutsItDownOnClose() {
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> "[]");
    createSpec("auth", "in_progress");
    createSpec("billing", "in_progress");

    ctrl.onEvent(agentStoppedEvent("auth"));
    ctrl.onEvent(agentStoppedEvent("billing"));
    var executor = ctrl.pipelineExecutor();
    assertFalse(executor.isShutdown(), "shared executor should stay open while running");

    ctrl.close();
    assertTrue(executor.isShutdown(), "close() must shut the shared executor down");
  }

  @Test
  void filterAcceptsAgentSessionStoppedWithSpec() {
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> "[]");
    var event = agentStoppedEvent("auth");
    assertTrue(ctrl.filter().test(event));
  }

  @Test
  void filterRejectsEventsWithoutSpec() {
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> "[]");
    var event = Event.of("proj", null, Event.WellKnownTypes.AGENT_SESSION_STOPPED, "sail", "h");
    assertFalse(ctrl.filter().test(event));
  }

  @Test
  void filterRejectsUnrelatedEventTypes() {
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> "[]");
    var event = Event.of("proj", "spec", "spec_dispatched", "sail", "h");
    assertFalse(ctrl.filter().test(event));
  }

  @Test
  void skipsSpecNotInProgress() {
    createSpec("auth", "pending");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> "[]");

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(SpecStatus.PENDING, specStore.findById("auth").orElseThrow().status());
    assertTrue(reviewStore.reviewsForSpec("auth").isEmpty());
  }

  @Test
  void skipsUnknownSpec() {
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> "[]");
    ctrl.onEvent(agentStoppedEvent("nonexistent"));

    assertTrue(reviewStore.reviewsForSpec("nonexistent").isEmpty());
  }

  @Test
  void transitionsSpecToReview() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> "[]");

    ctrl.onEvent(agentStoppedEvent("auth"));

    var spec = specStore.findById("auth").orElseThrow();
    assertEquals(SpecStatus.DONE, spec.status());
  }

  @Test
  void cleanReviewPassesAndTransitionsSpecToDone() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> "[]");

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("passed", review.status());
    assertEquals(1, review.iteration());
    assertEquals(SpecStatus.DONE, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void findingsStoredInDatabase() {
    createSpec("auth", "in_progress");
    var agentOutput =
        """
        ```json
        [{"severity": "HIGH", "category": "SECURITY", "file": "Auth.java",
          "line_start": 42, "line_end": 42, "title": "SQL injection",
          "description": "User input in query", "confidence": 0.9}]
        ```
        """;
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> agentOutput);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    var findings = reviewStore.findingsForReview(review.id());
    assertEquals(1, findings.size());
    assertEquals(Finding.Severity.HIGH, findings.getFirst().severity());
    assertEquals("SQL injection", findings.getFirst().title());
  }

  @Test
  void criticalFindingFailsNoCriticalGate() {
    createSpec("auth", "in_progress");
    var agentOutput =
        """
        ```json
        [{"severity": "CRITICAL", "category": "SECURITY", "file": "Auth.java",
          "line_start": 1, "line_end": 1, "title": "Critical issue",
          "description": "Very bad", "confidence": 0.95}]
        ```
        """;
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> agentOutput);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("failed", review.status());
  }

  @Test
  void mediumFindingPassesNoCriticalGate() {
    createSpec("auth", "in_progress");
    var agentOutput =
        """
        ```json
        [{"severity": "MEDIUM", "category": "LOGIC", "file": "a.java",
          "line_start": 1, "line_end": 1, "title": "Minor issue",
          "description": "Not great", "confidence": 0.5}]
        ```
        """;
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> agentOutput);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("passed", review.status());
  }

  @Test
  void twoStagesPipelineBothPass() {
    createSpec("auth", "in_progress");
    var ctrl = controller(p -> twoAgentStages(), p -> "codex", (p, a, pr) -> "[]", null);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("passed", review.status());

    var stages = reviewStore.stagesForReview(review.id());
    assertEquals(2, stages.size());
    assertEquals("passed", stages.get(0).status());
    assertEquals("passed", stages.get(1).status());
  }

  @Test
  void humanStageStopsAndWaits() {
    createSpec("auth", "in_progress");
    var ctrl = controller(p -> agentThenHuman(), p -> "codex", (p, a, pr) -> "[]", null);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("running", review.status());

    var stages = reviewStore.stagesForReview(review.id());
    assertEquals(2, stages.size());
    assertEquals("passed", stages.get(0).status());
    assertEquals("running", stages.get(1).status());
    assertEquals("human", stages.get(1).reviewer());
  }

  @Test
  void noPipelineConfigSkipsReview() {
    createSpec("auth", "in_progress");
    var ctrl = controller(p -> null, p -> "codex", (p, a, pr) -> "[]", null);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(SpecStatus.REVIEW, specStore.findById("auth").orElseThrow().status());
    assertTrue(reviewStore.reviewsForSpec("auth").isEmpty());
  }

  @Test
  void emptyPipelineConfigSkipsReview() {
    createSpec("auth", "in_progress");
    var emptyConfig = ReviewPipelineConfig.fromMap(Map.of());
    var ctrl = controller(emptyConfig, (p, a, pr) -> "[]");

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(SpecStatus.REVIEW, specStore.findById("auth").orElseThrow().status());
    assertTrue(reviewStore.reviewsForSpec("auth").isEmpty());
  }

  @Test
  void agentRunnerExceptionFailsStage() {
    createSpec("auth", "in_progress");
    ReviewAgentRunner failing =
        (p, a, pr) -> {
          throw new RuntimeException("Agent crashed");
        };
    var ctrl = controller(singleAgentStage("no_critical"), failing);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("failed", review.status());
  }

  @Test
  void subscriberNameIsReviewPipeline() {
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> "[]");
    assertEquals("review-pipeline", ctrl.name());
  }

  @Test
  void reviewPromptIncludesCategories() {
    createSpec("auth", "in_progress");
    var capturedPrompt = new AtomicReference<String>();
    ReviewAgentRunner capturing =
        (p, a, prompt) -> {
          capturedPrompt.set(prompt);
          return "[]";
        };
    var ctrl = controller(singleAgentStage("no_critical"), capturing);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertNotNull(capturedPrompt.get());
    assertTrue(capturedPrompt.get().contains("security"));
  }

  @Test
  void failedReviewTriggersFixIteration() {
    createSpec("auth", "in_progress");
    var criticalOutput =
        """
        ```json
        [{"severity": "CRITICAL", "category": "SECURITY", "file": "a.java",
          "line_start": 1, "line_end": 1, "title": "Bad",
          "description": "Very bad", "confidence": 0.9,
          "suggestion": {"before": "old", "after": "new", "rationale": "fix it"}}]
        ```
        """;
    var callCount = new AtomicInteger(0);
    ReviewAgentRunner runner =
        (p, a, prompt) -> {
          var call = callCount.incrementAndGet();
          return call == 1 ? criticalOutput : "[]";
        };
    var ctrl = controller(singleAgentStage("no_critical"), runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertTrue(callCount.get() >= 2);
    var reviews = reviewStore.reviewsForSpec("auth");
    assertFalse(reviews.isEmpty());
  }

  @Test
  void maxIterationsEscalates() {
    createSpec("auth", "in_progress");
    var criticalOutput =
        """
        ```json
        [{"severity": "CRITICAL", "category": "SECURITY", "file": "a.java",
          "line_start": 1, "line_end": 1, "title": "Persistent issue",
          "description": "Cannot fix", "confidence": 0.95}]
        ```
        """;
    var config =
        ReviewPipelineConfig.fromMap(
            Map.of(
                "max_iterations",
                1,
                "stages",
                List.of(
                    Map.of(
                        "name",
                        "security",
                        "type",
                        "agent",
                        "agent",
                        "codex",
                        "gate",
                        "no_critical"))));
    var ctrl = controller(p -> config, p -> "codex", (p, a, pr) -> criticalOutput, null);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("escalated", review.status());
  }

  @Test
  void executePipelinePublishesEventsWhenBusProvided() {
    createSpec("auth", "in_progress");
    specStore.updateStatus("auth", SpecStatus.REVIEW);
    var reviewId = reviewStore.createReview("auth", 1);
    reviewStore.updateReviewStatus(reviewId, "running");
    reviewStore.createStage(reviewId, "security", "agent");

    try (var bus = new EventBus()) {
      var ctrl =
          controller(p -> singleAgentStage("no_critical"), p -> "codex", (p, a, pr) -> "[]", bus);

      ctrl.executePipeline(reviewId, singleAgentStage("no_critical"), "test-project", "auth");

      assertTrue(bus.publishedCount() > 0);
    }
  }

  @Test
  void aRunningReviewIsNotRestartedByADuplicateEvent() {
    createSpec("auth", "in_progress");
    var reviewId = reviewStore.createReview("auth", 1);
    reviewStore.updateReviewStatus(reviewId, "running");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> "[]");

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(1, reviewStore.reviewsForSpec("auth").size());
  }

  @Test
  void awaitCompletionBlocksUntilAnInFlightPipelineFinishes() throws Exception {
    createSpec("auth", "in_progress");
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    ReviewAgentRunner gated =
        (p, a, pr) -> {
          started.countDown();
          await(release);
          return "[]";
        };
    var ctrl =
        new ReviewPipelineController(
            specStore,
            reviewStore,
            p -> singleAgentStage("no_critical"),
            p -> "codex",
            gated,
            null);

    ctrl.onEvent(agentStoppedEvent("auth"));
    assertTrue(started.await(5, TimeUnit.SECONDS), "pipeline should reach the agent runner");
    release.countDown();
    ctrl.awaitCompletion(5000);

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("passed", review.status());
  }

  @Test
  void closeAwaitsInFlightPipelines() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> "[]");

    ctrl.onEvent(agentStoppedEvent("auth"));
    ctrl.close();

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("passed", review.status());
  }

  @Test
  void awaitCompletionWithNoInFlightReturnsImmediately() throws Exception {
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> "[]");
    ctrl.awaitCompletion(1000);
  }

  @Test
  void onEventSwallowsHandlerExceptions() {
    var errDb = Sqlite.open(tempDir.resolve("err.db"));
    new SchemaManager(errDb).migrate();
    var errSpecStore = new SpecStore(errDb);
    errSpecStore.create(
        new SpecStore.SpecRow(
            "auth",
            "test-project",
            "Test spec",
            SpecStatus.IN_PROGRESS,
            null,
            null,
            null,
            null,
            "feat/test",
            0,
            null,
            "",
            "",
            null,
            List.of(),
            List.of()));
    var ctrl =
        new ReviewPipelineController(
            errSpecStore,
            new ReviewStore(errDb),
            p -> singleAgentStage("no_critical"),
            p -> "codex",
            (p, a, pr) -> "[]",
            null,
            new DirectExecutorService());
    errDb.close();

    assertDoesNotThrow(() -> ctrl.onEvent(agentStoppedEvent("auth")));
  }

  @Test
  void nonZeroExitSkipsReviewAndLeavesSpecInProgress() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> "[]");

    ctrl.onEvent(agentStoppedEvent("auth", 137));

    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertTrue(reviewStore.reviewsForSpec("auth").isEmpty());
  }

  @Test
  void nonZeroExitPublishesAgentFailed() {
    createSpec("auth", "in_progress");
    try (var bus = new EventBus()) {
      var ctrl =
          controller(p -> singleAgentStage("no_critical"), p -> "codex", (p, a, pr) -> "[]", bus);

      ctrl.onEvent(agentStoppedEvent("auth", 1));

      assertTrue(bus.publishedCount() > 0);
    }
  }

  @Test
  void zeroExitStillRunsReview() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> "[]");

    ctrl.onEvent(agentStoppedEvent("auth", 0));

    assertEquals("passed", reviewStore.latestReviewForSpec("auth").orElseThrow().status());
  }

  @Test
  void reentryAfterMaxIterationsEscalates() {
    createSpec("auth", "in_progress");
    var reviewId = reviewStore.createReview("auth", 3);
    reviewStore.updateReviewStatus(reviewId, "failed");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> "[]");

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals("escalated", reviewStore.findReview(reviewId).orElseThrow().status());
  }

  @Test
  void fixIterationAgentExceptionIsSwallowed() {
    createSpec("auth", "in_progress");
    var criticalOutput =
        """
        ```json
        [{"severity": "CRITICAL", "category": "SECURITY", "file": "a.java",
          "line_start": 1, "line_end": 1, "title": "Bad",
          "description": "Very bad", "confidence": 0.9}]
        ```
        """;
    var calls = new AtomicInteger(0);
    ReviewAgentRunner runner =
        (p, a, pr) -> {
          if (calls.incrementAndGet() == 1) return criticalOutput;
          throw new RuntimeException("fix agent crashed");
        };
    var ctrl = controller(singleAgentStage("no_critical"), runner);

    assertDoesNotThrow(() -> ctrl.onEvent(agentStoppedEvent("auth")));
    assertEquals(2, calls.get());
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
