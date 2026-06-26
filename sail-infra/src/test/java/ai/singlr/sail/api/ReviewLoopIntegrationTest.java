/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the dispatch→stop→review loop through the real wiring: an {@link EventBus} with a
 * subscribed {@link ReviewPipelineController} over real stores. The controller's pipeline runs on a
 * same-thread executor, and the test awaits delivery via a latch ({@link BusTesting#latching}), so
 * a published {@code agent_session_stopped} drives the spec to its final state deterministically —
 * no sleep, no race. This is the integration the per-unit tests do not cover: that an event
 * actually reaches the subscribed controller and advances the spec through the database.
 */
class ReviewLoopIntegrationTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SpecStore specStore;
  private ReviewStore reviewStore;
  private EventBus bus;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("loop.db"));
    new SchemaManager(db).migrate();
    specStore = new SpecStore(db);
    reviewStore = new ReviewStore(db);
    bus = new EventBus();
  }

  @AfterEach
  void tearDown() {
    bus.close();
    if (db != null) db.close();
  }

  private void createSpec(String id) {
    specStore.create(
        new SpecStore.SpecRow(
            id,
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
  }

  private ReviewPipelineConfig singleStage(String gate) {
    return singleStage(gate, 3);
  }

  private ReviewPipelineConfig singleStage(String gate, int maxIterations) {
    return ReviewPipelineConfig.fromMap(
        Map.of(
            "max_iterations",
            maxIterations,
            "stages",
            List.of(Map.of("name", "security", "type", "agent", "agent", "codex", "gate", gate))));
  }

  private static final String CRITICAL_FINDING =
      """
      ```json
      [{"severity": "CRITICAL", "category": "SECURITY", "file": "a.java",
        "line_start": 1, "line_end": 1, "title": "Bad",
        "description": "Very bad", "confidence": 0.95}]
      ```
      """;

  /**
   * A runner that plays a real review cycle: it tells a review prompt from a fix prompt by content,
   * so review calls return findings (scripted) and fix calls just acknowledge — letting a test
   * drive the review→fix→re-review loop.
   */
  private static ReviewAgentRunner cyclingRunner(java.util.List<String> reviewOutputs) {
    var reviewCall = new AtomicInteger();
    return (project, agent, prompt) -> {
      if (prompt.contains("Output your findings")) {
        var i = reviewCall.getAndIncrement();
        return i < reviewOutputs.size() ? reviewOutputs.get(i) : "[]";
      }
      return "fix applied";
    };
  }

  private void subscribe(
      ReviewPipelineConfig config, ReviewAgentRunner runner, CountDownLatch latch) {
    var controller =
        new ReviewPipelineController(
            specStore,
            reviewStore,
            p -> config,
            p -> "codex",
            runner,
            bus,
            new DirectExecutorService());
    bus.subscribe(BusTesting.latching(controller, latch));
  }

  private Event stop(String specId) {
    return Event.of(
        "test-project",
        specId,
        Event.WellKnownTypes.AGENT_SESSION_STOPPED,
        "claude-code",
        "host",
        Map.of(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER));
  }

  private Event stop(String specId, int exitCode) {
    return Event.of(
        "test-project",
        specId,
        Event.WellKnownTypes.AGENT_SESSION_STOPPED,
        "claude-code",
        "host",
        Map.of(
            Event.WellKnownData.EXIT_CODE,
            exitCode,
            Event.WellKnownData.SOURCE,
            Event.WellKnownData.SOURCE_WATCHER));
  }

  private Event hookTurnEndStop(String specId) {
    return Event.of(
        "test-project", specId, Event.WellKnownTypes.AGENT_SESSION_STOPPED, "claude-code", "host");
  }

  @Test
  void aCleanStopPublishedToTheBusAdvancesTheSpecToDone() throws Exception {
    createSpec("auth");
    var latch = new CountDownLatch(1);
    subscribe(singleStage("no_critical"), (p, a, pr) -> "[]", latch);

    bus.publish(stop("auth"));

    BusTesting.awaitDelivery(latch);
    assertEquals(SpecStatus.DONE, specStore.findById("auth").orElseThrow().status());
    assertEquals("passed", reviewStore.latestReviewForSpec("auth").orElseThrow().status());
  }

  @Test
  void aHookTurnEndStopThroughTheBusDoesNotTriggerReview() throws Exception {
    createSpec("auth");
    var latch = new CountDownLatch(1);
    subscribe(singleStage("no_critical"), (p, a, pr) -> "[]", latch);

    bus.publish(hookTurnEndStop("auth"));

    BusTesting.awaitDelivery(latch);
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertTrue(reviewStore.reviewsForSpec("auth").isEmpty());
  }

  @Test
  void theReviewFixReReviewLoopReachesDoneWhenAFixResolvesTheFindings() throws Exception {
    createSpec("auth");
    var latch = new CountDownLatch(1);
    subscribe(singleStage("no_critical"), cyclingRunner(List.of(CRITICAL_FINDING)), latch);

    bus.publish(stop("auth"));

    BusTesting.awaitDelivery(latch);
    assertEquals(SpecStatus.DONE, specStore.findById("auth").orElseThrow().status());
    assertEquals(
        2,
        reviewStore.reviewsForSpec("auth").size(),
        "one failed review, then a passing re-review after the fix");
  }

  @Test
  void unresolvedFindingsEscalateAfterTheIterationBudget() throws Exception {
    createSpec("auth");
    var latch = new CountDownLatch(1);
    subscribe(
        singleStage("no_critical", 2),
        cyclingRunner(List.of(CRITICAL_FINDING, CRITICAL_FINDING)),
        latch);

    bus.publish(stop("auth"));

    BusTesting.awaitDelivery(latch);
    assertEquals("escalated", reviewStore.latestReviewForSpec("auth").orElseThrow().status());
    assertEquals(SpecStatus.REVIEW, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aNonZeroExitPublishedToTheBusSkipsReview() throws Exception {
    createSpec("auth");
    var latch = new CountDownLatch(1);
    subscribe(singleStage("no_critical"), (p, a, pr) -> "[]", latch);

    bus.publish(stop("auth", 137));

    BusTesting.awaitDelivery(latch);
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertTrue(reviewStore.reviewsForSpec("auth").isEmpty());
  }
}
