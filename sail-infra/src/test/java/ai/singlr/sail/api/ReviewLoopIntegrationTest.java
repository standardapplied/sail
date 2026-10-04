/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static ai.singlr.sail.api.ReviewScripts.CLEAN_REVIEW;
import static ai.singlr.sail.api.ReviewScripts.CRITICAL_FINDING;
import static ai.singlr.sail.api.ReviewScripts.fixAllCarried;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the dispatch→stop→review loop through the real wiring ({@link ReviewLoop}): a published
 * {@code agent_session_stopped} reaches the subscribed {@link ReviewPipelineController}, which
 * launches each reviewer and fix agent as its own run, and each of those runs ends with its own
 * stop on the same bus. The loop settles on handled events, so a spec is driven to its final state
 * deterministically — no sleep, no race.
 */
class ReviewLoopIntegrationTest {

  @TempDir Path tempDir;
  private ReviewLoop loop;

  @AfterEach
  void tearDown() {
    loop.close();
  }

  private void createSpec(String id) {
    Acting.as(
        null,
        () ->
            loop.specs.create(
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
                    List.of())));
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

  /**
   * Agents that play a real review cycle: a reviewer returns the scripted findings in order, then
   * rules every carried finding fixed; a fix agent just acknowledges — letting a test drive the
   * review→fix→re-review loop.
   */
  private static ScriptedAgent cycling(List<String> reviewOutputs) {
    var reviewCall = new AtomicInteger();
    return (project, agent, prompt, reviewId, credential) -> {
      if (prompt.contains("Review the changes on branch")) {
        var i = reviewCall.getAndIncrement();
        return i < reviewOutputs.size() ? reviewOutputs.get(i) : fixAllCarried(prompt);
      }
      return "fix applied";
    };
  }

  private void start(ReviewPipelineConfig config, ScriptedAgent agents) {
    loop = ReviewLoop.of(tempDir, config).scripted(agents);
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
  void aCleanStopPublishedToTheBusAdvancesTheSpecToAwaitingMerge() {
    start(singleStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);
    createSpec("auth");

    loop.onEvent(stop("auth"));

    assertEquals(SpecStatus.AWAITING_MERGE, loop.specs.findById("auth").orElseThrow().status());
    assertEquals("passed", loop.reviews.latestReviewForSpec("auth").orElseThrow().status());
  }

  @Test
  void aHookTurnEndStopThroughTheBusDoesNotTriggerReview() {
    start(singleStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);
    createSpec("auth");

    loop.onEvent(hookTurnEndStop("auth"));

    assertEquals(SpecStatus.IN_PROGRESS, loop.specs.findById("auth").orElseThrow().status());
    assertTrue(loop.reviews.reviewsForSpec("auth").isEmpty());
  }

  @Test
  void theReviewFixReReviewLoopReachesAwaitingMergeWhenAFixResolvesTheFindings() {
    start(singleStage("no_critical"), cycling(List.of(CRITICAL_FINDING)));
    createSpec("auth");

    loop.onEvent(stop("auth"));

    assertEquals(SpecStatus.AWAITING_MERGE, loop.specs.findById("auth").orElseThrow().status());
    assertEquals(
        2,
        loop.reviews.reviewsForSpec("auth").size(),
        "one failed review, then a passing re-review after the fix");
    assertEquals(
        List.of("review", "fix", "review"),
        loop.runs.listForSpec("auth").reversed().stream().map(RunStore.RunRow::role).toList(),
        "each reviewer and the fix agent between them ran as its own run");
  }

  @Test
  void unresolvedFindingsEscalateAfterTheIterationBudget() {
    start(singleStage("no_critical", 2), cycling(List.of(CRITICAL_FINDING, CRITICAL_FINDING)));
    createSpec("auth");

    loop.onEvent(stop("auth"));

    assertEquals("escalated", loop.reviews.latestReviewForSpec("auth").orElseThrow().status());
    assertEquals(SpecStatus.REVIEW, loop.specs.findById("auth").orElseThrow().status());
  }

  @Test
  void aNonZeroExitPublishedToTheBusSkipsReview() {
    start(singleStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);
    createSpec("auth");

    loop.onEvent(stop("auth", 137));

    assertEquals(SpecStatus.IN_PROGRESS, loop.specs.findById("auth").orElseThrow().status());
    assertTrue(loop.reviews.reviewsForSpec("auth").isEmpty());
  }
}
