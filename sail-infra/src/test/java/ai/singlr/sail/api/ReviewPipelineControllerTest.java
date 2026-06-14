/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

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
import java.util.concurrent.atomic.AtomicReference;
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

  private ReviewPipelineController controller(
      ReviewPipelineConfig config, ReviewAgentRunner runner) {
    return new ReviewPipelineController(specStore, reviewStore, p -> config, runner, null);
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
  void transitionsSpecToReview() throws Exception {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> "[]");

    ctrl.onEvent(agentStoppedEvent("auth"));
    ctrl.awaitCompletion(5000);

    var spec = specStore.findById("auth").orElseThrow();
    assertTrue(spec.status() == SpecStatus.REVIEW || spec.status() == SpecStatus.DONE);
  }

  @Test
  void cleanReviewPassesAndTransitionsSpecToDone() throws Exception {
    createSpec("auth", "in_progress");
    var latch = new CountDownLatch(1);
    var runner = new LatchedRunner("[]", latch);
    var ctrl = controller(singleAgentStage("no_critical"), runner);

    ctrl.onEvent(agentStoppedEvent("auth"));
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    ctrl.awaitCompletion(5000);

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("passed", review.status());
    assertEquals(1, review.iteration());
    assertEquals(SpecStatus.DONE, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void findingsStoredInDatabase() throws Exception {
    createSpec("auth", "in_progress");
    var agentOutput =
        """
        ```json
        [{"severity": "HIGH", "category": "SECURITY", "file": "Auth.java",
          "line_start": 42, "line_end": 42, "title": "SQL injection",
          "description": "User input in query", "confidence": 0.9}]
        ```
        """;
    var latch = new CountDownLatch(1);
    var ctrl = controller(singleAgentStage("no_critical"), new LatchedRunner(agentOutput, latch));

    ctrl.onEvent(agentStoppedEvent("auth"));
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    ctrl.awaitCompletion(5000);

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    var findings = reviewStore.findingsForReview(review.id());
    assertEquals(1, findings.size());
    assertEquals(Finding.Severity.HIGH, findings.getFirst().severity());
    assertEquals("SQL injection", findings.getFirst().title());
  }

  @Test
  void criticalFindingFailsNoCriticalGate() throws Exception {
    createSpec("auth", "in_progress");
    var agentOutput =
        """
        ```json
        [{"severity": "CRITICAL", "category": "SECURITY", "file": "Auth.java",
          "line_start": 1, "line_end": 1, "title": "Critical issue",
          "description": "Very bad", "confidence": 0.95}]
        ```
        """;
    var latch = new CountDownLatch(1);
    var ctrl = controller(singleAgentStage("no_critical"), new LatchedRunner(agentOutput, latch));

    ctrl.onEvent(agentStoppedEvent("auth"));
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    ctrl.awaitCompletion(5000);

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("failed", review.status());
  }

  @Test
  void mediumFindingPassesNoCriticalGate() throws Exception {
    createSpec("auth", "in_progress");
    var agentOutput =
        """
        ```json
        [{"severity": "MEDIUM", "category": "LOGIC", "file": "a.java",
          "line_start": 1, "line_end": 1, "title": "Minor issue",
          "description": "Not great", "confidence": 0.5}]
        ```
        """;
    var latch = new CountDownLatch(1);
    var ctrl = controller(singleAgentStage("no_critical"), new LatchedRunner(agentOutput, latch));

    ctrl.onEvent(agentStoppedEvent("auth"));
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    ctrl.awaitCompletion(5000);

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("passed", review.status());
  }

  @Test
  void twoStagesPipelineBothPass() throws Exception {
    createSpec("auth", "in_progress");
    var latch = new CountDownLatch(1);
    var ctrl =
        new ReviewPipelineController(
            specStore, reviewStore, p -> twoAgentStages(), new LatchedRunner("[]", latch), null);

    ctrl.onEvent(agentStoppedEvent("auth"));
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    ctrl.awaitCompletion(5000);

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("passed", review.status());

    var stages = reviewStore.stagesForReview(review.id());
    assertEquals(2, stages.size());
    assertEquals("passed", stages.get(0).status());
    assertEquals("passed", stages.get(1).status());
  }

  @Test
  void humanStageStopsAndWaits() throws Exception {
    createSpec("auth", "in_progress");
    var latch = new CountDownLatch(1);
    var ctrl =
        new ReviewPipelineController(
            specStore, reviewStore, p -> agentThenHuman(), new LatchedRunner("[]", latch), null);

    ctrl.onEvent(agentStoppedEvent("auth"));
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    ctrl.awaitCompletion(5000);

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
    var ctrl =
        new ReviewPipelineController(specStore, reviewStore, p -> null, (p, a, pr) -> "[]", null);

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
  void agentRunnerExceptionFailsStage() throws Exception {
    createSpec("auth", "in_progress");
    var latch = new CountDownLatch(1);
    ReviewAgentRunner failing =
        (p, a, pr) -> {
          latch.countDown();
          throw new RuntimeException("Agent crashed");
        };
    var ctrl = controller(singleAgentStage("no_critical"), failing);

    ctrl.onEvent(agentStoppedEvent("auth"));
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    ctrl.awaitCompletion(5000);

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("failed", review.status());
  }

  @Test
  void subscriberNameIsReviewPipeline() {
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> "[]");
    assertEquals("review-pipeline", ctrl.name());
  }

  @Test
  void reviewPromptIncludesCategories() throws Exception {
    createSpec("auth", "in_progress");
    var capturedPrompt = new AtomicReference<String>();
    var latch = new CountDownLatch(1);
    ReviewAgentRunner capturing =
        (p, a, prompt) -> {
          capturedPrompt.set(prompt);
          latch.countDown();
          return "[]";
        };
    var ctrl = controller(singleAgentStage("no_critical"), capturing);

    ctrl.onEvent(agentStoppedEvent("auth"));
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    ctrl.awaitCompletion(5000);

    assertNotNull(capturedPrompt.get());
    assertTrue(capturedPrompt.get().contains("security"));
  }

  @Test
  void failedReviewTriggersFixIteration() throws Exception {
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
    var callCount = new java.util.concurrent.atomic.AtomicInteger(0);
    var latch = new CountDownLatch(2);
    ReviewAgentRunner runner =
        (p, a, prompt) -> {
          var call = callCount.incrementAndGet();
          latch.countDown();
          return call == 1 ? criticalOutput : "[]";
        };
    var config =
        ReviewPipelineConfig.fromMap(
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
                        "no_critical"))));
    var ctrl = new ReviewPipelineController(specStore, reviewStore, p -> config, runner, null);

    ctrl.onEvent(agentStoppedEvent("auth"));
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    ctrl.awaitCompletion(5000);

    assertTrue(callCount.get() >= 2);
    var reviews = reviewStore.reviewsForSpec("auth");
    assertFalse(reviews.isEmpty());
  }

  @Test
  void maxIterationsEscalates() throws Exception {
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
    var latch = new CountDownLatch(1);
    var ctrl =
        new ReviewPipelineController(
            specStore, reviewStore, p -> config, new LatchedRunner(criticalOutput, latch), null);

    ctrl.onEvent(agentStoppedEvent("auth"));
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    ctrl.awaitCompletion(5000);

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
          new ReviewPipelineController(
              specStore,
              reviewStore,
              p -> singleAgentStage("no_critical"),
              (p, a, pr) -> "[]",
              bus);

      ctrl.executePipeline(reviewId, singleAgentStage("no_critical"), "test-project", "auth");

      assertTrue(bus.publishedCount() > 0);
    }
  }

  @Test
  void duplicateEventForRunningReviewIsIgnored() throws Exception {
    createSpec("auth", "in_progress");
    var latch = new CountDownLatch(1);
    var ctrl = controller(singleAgentStage("no_critical"), new LatchedRunner("[]", latch));

    ctrl.onEvent(agentStoppedEvent("auth"));
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    ctrl.awaitCompletion(5000);

    specStore.updateStatus("auth", SpecStatus.IN_PROGRESS);
    var reviewBefore = reviewStore.reviewsForSpec("auth").size();
    ctrl.onEvent(agentStoppedEvent("auth"));
    ctrl.awaitCompletion(5000);

    var reviewAfter = reviewStore.reviewsForSpec("auth").size();
    assertTrue(reviewAfter >= reviewBefore);
  }

  @Test
  void closeAwaitsInFlightPipelines() throws Exception {
    createSpec("auth", "in_progress");
    var latch = new CountDownLatch(1);
    var ctrl = controller(singleAgentStage("no_critical"), new LatchedRunner("[]", latch));

    ctrl.onEvent(agentStoppedEvent("auth"));
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    ctrl.close();

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("passed", review.status());
  }

  @Test
  void awaitCompletionWithNoInFlightReturnsImmediately() throws Exception {
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr) -> "[]");
    ctrl.awaitCompletion(1000);
  }

  private static class LatchedRunner implements ReviewAgentRunner {
    private final String output;
    private final CountDownLatch latch;

    LatchedRunner(String output, CountDownLatch latch) {
      this.output = output;
      this.latch = latch;
    }

    @Override
    public String run(String project, String agent, String prompt) {
      latch.countDown();
      return output;
    }
  }
}
