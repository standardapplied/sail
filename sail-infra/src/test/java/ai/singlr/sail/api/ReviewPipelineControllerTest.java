/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static ai.singlr.sail.api.ReviewScripts.CLEAN_REVIEW;
import static ai.singlr.sail.api.ReviewScripts.fixAllCarried;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.PromptConversation;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewPipelineControllerTest {

  @TempDir Path tempDir;
  private ReviewLoop loop;
  private Sqlite db;
  private SpecStore specStore;
  private ReviewStore reviewStore;
  private RunStore runStore;
  private Function<String, ReviewPipelineConfig> configs = project -> null;
  private Function<String, String> reviewers = project -> "codex";

  @BeforeEach
  void setUp() {
    loop =
        new ReviewLoop(
            tempDir,
            ReviewLoop.YAML,
            project -> configs.apply(project),
            project -> reviewers.apply(project));
    db = loop.db;
    specStore = loop.specs;
    reviewStore = loop.reviews;
    runStore = loop.runs;
  }

  @AfterEach
  void tearDown() {
    loop.close();
  }

  private void createSpec(String id, String status) {
    createSpec(id, status, List.of());
  }

  private void createSpec(String id, String status, List<String> repos) {
    createSpec(id, status, repos, null, null, null);
  }

  private void createSpec(
      String id,
      String status,
      List<String> repos,
      String agent,
      String model,
      String reasoningEffort) {
    Acting.as(
        null,
        () ->
            specStore.create(
                new SpecStore.SpecRow(
                    id,
                    "test-project",
                    "Test spec",
                    SpecStatus.fromWire(status),
                    null,
                    agent,
                    model,
                    reasoningEffort,
                    "feat/test",
                    0,
                    null,
                    "",
                    "",
                    null,
                    List.of(),
                    repos)));
  }

  private Event agentStoppedEvent(String specId) {
    return Event.of(
        "test-project",
        specId,
        Event.WellKnownTypes.AGENT_SESSION_STOPPED,
        "claude-code",
        "host",
        Map.of(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER));
  }

  private Event agentStoppedEvent(String specId, int exitCode) {
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

  private Event hookTurnEndEvent(String specId) {
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

  private ReviewLoop controller(ReviewPipelineConfig config, ScriptedAgent agents) {
    return controller(p -> config, p -> "codex", agents);
  }

  private ReviewLoop controller(
      Function<String, ReviewPipelineConfig> config,
      Function<String, String> reviewer,
      ScriptedAgent agents) {
    configs = config;
    reviewers = reviewer;
    return loop.scripted(agents);
  }

  @Test
  void advancingASpecTriggersSyncSoTheTransitionReachesMain() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
    assertTrue(loop.syncs.get() > 0, "a spec status transition must trigger sync-on-write to main");
  }

  private Event roomStopEvent(String specId, String runId) {
    var data = new LinkedHashMap<String, Object>();
    data.put(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER);
    data.put(Event.WellKnownData.EXIT_CODE, 0);
    data.put(Event.WellKnownData.RUN_ROLE, Event.WellKnownData.RUN_ROLE_ROOM);
    if (runId != null) {
      data.put(Event.WellKnownData.RUN_ID, runId);
    }
    return Event.of(
        "test-project",
        specId,
        Event.WellKnownTypes.AGENT_SESSION_STOPPED,
        "claude-code",
        "host",
        data);
  }

  @Test
  void aRoomStopOnASpecParkedInReviewTriggersNothing() {
    createSpec("auth", "review");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(roomStopEvent("auth", null));

    assertTrue(
        reviewStore.reviewsForSpec("auth").isEmpty(),
        "an escalated spec is exactly where a human asks questions; the chat must never re-enter"
            + " the loop");
    assertEquals(SpecStatus.REVIEW, specStore.findById("auth").orElseThrow().status());
  }

  private Event laneStopEvent(String specId, String role, String runId) {
    var data = new LinkedHashMap<String, Object>();
    data.put(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER);
    data.put(Event.WellKnownData.EXIT_CODE, 0);
    if (role != null) {
      data.put(Event.WellKnownData.RUN_ROLE, role);
    }
    if (runId != null) {
      data.put(Event.WellKnownData.RUN_ID, runId);
    }
    return Event.of(
        "test-project",
        specId,
        Event.WellKnownTypes.AGENT_SESSION_STOPPED,
        "claude-code",
        "host",
        data);
  }

  @Test
  void aReviewLaneStopNeverTriggersAReview() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(laneStopEvent("auth", Event.WellKnownData.RUN_ROLE_REVIEW, null));

    assertTrue(
        reviewStore.reviewsForSpec("auth").isEmpty(),
        "a reviewer's stop that names no run this box holds — even carrying the spec id — must"
            + " never start a review");
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aFixLaneStopNeverTriggersAReview() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(laneStopEvent("auth", Event.WellKnownData.RUN_ROLE_FIX, null));

    assertTrue(
        reviewStore.reviewsForSpec("auth").isEmpty(),
        "a fix agent's stop that names no run this box holds must never start a review");
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aRoomStopOnADoneSpecTriggersNothing() {
    createSpec("auth", "done");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(roomStopEvent("auth", null));

    assertTrue(reviewStore.reviewsForSpec("auth").isEmpty());
    assertEquals(SpecStatus.DONE, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aRoomStopThatLostItsRoleMarkerIsStillCaughtByTheRunRow() {
    createSpec("auth", "review");
    var runId = DateTimeUtils.newId().toString();
    Acting.system(
        () ->
            runStore.create(
                runId,
                "test-project",
                "auth",
                "node-a",
                "room",
                "claude-code",
                null,
                "t",
                null,
                null,
                null,
                "sail-agent-" + runId));
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);
    var data = new LinkedHashMap<String, Object>();
    data.put(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER);
    data.put(Event.WellKnownData.RUN_ID, runId);

    ctrl.onEvent(
        Event.of(
            "test-project",
            "auth",
            Event.WellKnownTypes.AGENT_SESSION_STOPPED,
            "claude-code",
            "host",
            data));

    assertTrue(
        reviewStore.reviewsForSpec("auth").isEmpty(),
        "the run row is the fallback when a signal lost its role");
  }

  @Test
  void aContainerLeaseHeldByARestoreErrorsTheReviewInsteadOfLaunchingIntoTheContainer() {
    createSpec("auth", "in_progress");
    Acting.system(() -> runStore.acquireContainerLease("test-project", "node-a", "restore"));
    var launched = new AtomicBoolean();
    var ctrl =
        controller(
            singleAgentStage("no_critical"),
            (p, a, pr, rid, cred) -> {
              launched.set(true);
              return CLEAN_REVIEW;
            });

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertFalse(launched.get(), "a review agent must never launch into a container mid-restore");
    assertTrue(
        loop.container.launched().isEmpty(),
        "nothing is started in a container a restore is rolling back");
    var errored = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("failed", errored.status());
    assertTrue(errored.errored());
    assertTrue(errored.error().contains("restore"), errored.error());
    assertTrue(
        runStore.listForSpec("auth").isEmpty(), "a refused review must not insert a run row");

    Acting.system(() -> runStore.releaseContainerLease("test-project", "node-a"));
    ctrl.onEvent(agentStoppedEvent("auth"));

    assertTrue(launched.get(), "the replayed stop must review normally once the lease is gone");
    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aBuildStopStillTriggersTheReviewAfterTheRoomExclusion() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    var data = new LinkedHashMap<String, Object>();
    data.put(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER);
    data.put(Event.WellKnownData.RUN_ROLE, "build");
    ctrl.onEvent(
        Event.of(
            "test-project",
            "auth",
            Event.WellKnownTypes.AGENT_SESSION_STOPPED,
            "claude-code",
            "host",
            data));

    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aSyncDerivedStopNeverStartsAPipelineTheWorkLivesOnAnotherBox() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(
        Event.of(
            "test-project",
            "auth",
            Event.WellKnownTypes.AGENT_SESSION_STOPPED,
            "claude-code",
            "host",
            Map.of(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_SYNC)));

    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertTrue(reviewStore.latestReviewForSpec("auth").isEmpty());
  }

  @Test
  void anAuthoritativeStopStillKicksOffReviewWhenStatusWasClobberedToReview() {
    createSpec("auth", "review");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth");
    assertTrue(
        review.isPresent(), "a spec clobbered to review out of band must still get its review");
    assertEquals("passed", review.get().status());
    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void messageStoreWiringIsFluent() {
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    assertEquals(ctrl.controller, ctrl.controller.useMessages(new MessageStore(db)));
  }

  @Test
  void aReviewAlreadyRunningIsNotRestartedWhenStatusIsReview() {
    createSpec("auth", "review");
    var ctrl = controller(singleAgentStage("no_critical"), null);
    ctrl.onEvent(agentStoppedEvent("auth"));
    var reviewer = loop.live().getFirst();

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(1, reviewStore.reviewsForSpec("auth").size());
    assertEquals(
        List.of(reviewer.id()),
        loop.container.launched(),
        "a review its reviewer still serves is left to that reviewer's own stop");
    assertEquals("running", reviewStore.latestReviewForSpec("auth").orElseThrow().status());
  }

  @Test
  void aTerminalSpecStatusIgnoresTheStop() {
    createSpec("auth", "awaiting_merge");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertTrue(reviewStore.latestReviewForSpec("auth").isEmpty());
    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void skipsAnUnknownSpec() {
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("ghost"));

    assertTrue(reviewStore.latestReviewForSpec("ghost").isEmpty());
  }

  @Test
  void stageWithoutAnAgentUsesTheRosterReviewer() {
    createSpec("auth", "in_progress");
    var capturedAgent = new AtomicReference<String>();
    ScriptedAgent capturing =
        (p, a, prompt, rid, cred) -> {
          capturedAgent.set(a);
          return CLEAN_REVIEW;
        };
    var ctrl = controller(p -> singleStageNoAgent("no_critical"), p -> "claude-code", capturing);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals("claude-code", capturedAgent.get());
  }

  @Test
  void stageFailsWhenNoReviewerIsAvailable() {
    createSpec("auth", "in_progress");
    var ctrl =
        controller(
            p -> singleStageNoAgent("no_critical"),
            p -> null,
            (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("failed", reviewStore.stagesForReview(review.id()).getFirst().status());
  }

  @Test
  void filterAcceptsAgentSessionStoppedWithSpec() {
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);
    var event = agentStoppedEvent("auth");
    assertTrue(ctrl.controller.filter().test(event));
  }

  @Test
  void aStopThatNamesNeitherASpecNorARunStartsNothing() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent(null));

    assertTrue(reviewStore.reviewsForSpec("auth").isEmpty());
    assertTrue(loop.container.launched().isEmpty());
    assertTrue(
        loop.events("review_pipeline_error").isEmpty(),
        "a stop the loop has nothing to route is dropped quietly, not reported as a failure");
  }

  @Test
  void filterRejectsUnrelatedEventTypes() {
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);
    var event = Event.of("proj", "spec", "spec_dispatched", "sail", "h");
    assertFalse(ctrl.controller.filter().test(event));
  }

  @Test
  void skipsSpecNotInProgress() {
    createSpec("auth", "pending");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(SpecStatus.PENDING, specStore.findById("auth").orElseThrow().status());
    assertTrue(reviewStore.reviewsForSpec("auth").isEmpty());
  }

  @Test
  void skipsUnknownSpec() {
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);
    ctrl.onEvent(agentStoppedEvent("nonexistent"));

    assertTrue(reviewStore.reviewsForSpec("nonexistent").isEmpty());
  }

  @Test
  void aStopArrivingAfterAnOperatorCancelNeverKicksAReview() {
    createSpec("auth", "cancelled");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertTrue(reviewStore.reviewsForSpec("auth").isEmpty());
    assertEquals(SpecStatus.CANCELLED, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void ignoresAStopForASpecAlreadyAwaitingMerge() {
    createSpec("auth", "awaiting_merge");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertTrue(reviewStore.reviewsForSpec("auth").isEmpty());
    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void transitionsSpecToReview() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var spec = specStore.findById("auth").orElseThrow();
    assertEquals(SpecStatus.AWAITING_MERGE, spec.status());
  }

  @Test
  void cleanReviewPassesAndParksSpecAwaitingMerge() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("passed", review.status());
    assertEquals(1, review.iteration());
    assertEquals(
        SpecStatus.AWAITING_MERGE,
        specStore.findById("auth").orElseThrow().status(),
        "a gate pass leaves the PR unmerged — done is the human's call after merging");
  }

  @Test
  void anOperatorCancelLandingMidPipelineIsNeverOverwrittenByThePass() {
    createSpec("auth", "in_progress");
    var ctrl =
        controller(
            singleAgentStage("no_critical"),
            (p, a, pr, rid, cred) -> {
              Acting.system(() -> specStore.updateStatus("auth", SpecStatus.CANCELLED));
              return CLEAN_REVIEW;
            });

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(
        SpecStatus.CANCELLED,
        specStore.findById("auth").orElseThrow().status(),
        "cancelled is terminal; the pipeline's stale advance must lose, not resurrect the spec");
  }

  @Test
  void aWatcherStopAndAReconcilerReplayBackToBackProduceExactlyOneReview() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));
    ctrl.onEvent(reconcilerStoppedEvent("auth"));

    assertEquals(1, reviewStore.reviewsForSpec("auth").size());
    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aReconcilerReplayLandingMidReviewIsShedWhileTheReviewerStillRuns() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), null);
    ctrl.onEvent(agentStoppedEvent("auth"));
    var reviewer = loop.live().getFirst();

    ctrl.onEvent(reconcilerStoppedEvent("auth"));

    assertEquals(1, reviewStore.reviewsForSpec("auth").size());
    assertEquals(List.of(reviewer.id()), loop.container.launched());

    ctrl.finish(reviewer.id(), CLEAN_REVIEW);

    assertEquals(1, reviewStore.reviewsForSpec("auth").size());
    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
  }

  private Event reconcilerStoppedEvent(String specId) {
    return Event.of(
        "test-project",
        specId,
        Event.WellKnownTypes.AGENT_SESSION_STOPPED,
        "claude-code",
        "host",
        Map.of(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_RECONCILE));
  }

  @Test
  void findingsStoredInDatabase() {
    createSpec("auth", "in_progress");
    var agentOutput =
        """
        ```json
        {"verdicts": [], "findings": [{"severity": "HIGH", "category": "SECURITY", "file": "Auth.java",
          "line_start": 42, "line_end": 42, "title": "SQL injection",
          "description": "User input in query", "confidence": 0.9}]}
        ```
        """;
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> agentOutput);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    var findings = reviewStore.findingsForReview(review.id());
    assertEquals(1, findings.size());
    assertEquals(Finding.Severity.HIGH, findings.getFirst().severity());
    assertEquals("SQL injection", findings.getFirst().title());
  }

  @Test
  void anUnparseableReviewIsAnErrorNeverACleanPass() {
    createSpec("auth", "in_progress");
    var promptEchoOnly = "Begin your response with ```json and end with ```.";
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> promptEchoOnly);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("failed", review.status());
    assertTrue(
        review.errored(),
        "a review whose output cannot be parsed must never gate-pass as zero findings");
    assertEquals(
        SpecStatus.REVIEW,
        specStore.findById("auth").orElseThrow().status(),
        "the spec must not advance to done on an unreadable review");
  }

  @Test
  void aRunnerErrorIsRecordedOnTheReviewAndNeverMistakenForAVerdict() {
    createSpec("auth", "in_progress");
    var ctrl =
        controller(
            singleAgentStage("no_critical"),
            (p, a, pr, rid, cred) -> {
              throw new IllegalStateException("Quota exceeded");
            });

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("failed", review.status());
    assertEquals(
        "reviewer failed: exit 1", review.error(), "why it failed is durable, not journal-only");
    assertTrue(review.errored(), "a reviewer that crashed gave no verdict");
    var stage = reviewStore.stagesForReview(review.id()).getFirst();
    assertEquals("reviewer failed: exit 1", stage.error());
  }

  @Test
  void erroredIterationsAreRetriedNotBurnedAgainstMaxIterations() {
    createSpec("auth", "in_progress");
    var broken =
        controller(
            singleAgentStage("no_critical"),
            (p, a, pr, rid, cred) -> {
              throw new IllegalStateException("Quota exceeded");
            });
    broken.onEvent(agentStoppedEvent("auth"));
    assertEquals(1, reviewStore.latestReviewForSpec("auth").orElseThrow().iteration());

    Acting.system(() -> specStore.updateStatus("auth", SpecStatus.IN_PROGRESS));
    var healthy =
        controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);
    healthy.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals(
        1,
        review.iteration(),
        "an infrastructure error must not consume a review iteration — the retry runs as the"
            + " same iteration, so quota outages can never exhaust max_iterations");
    assertEquals("passed", review.status());
    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aCriticalFindingThatIsNeverFixedEscalates() {
    createSpec("auth", "in_progress");
    var agentOutput =
        """
        ```json
        {"verdicts": [], "findings": [{"severity": "CRITICAL", "category": "SECURITY", "file": "Auth.java",
          "line_start": 1, "line_end": 1, "title": "Critical issue",
          "description": "Very bad", "confidence": 0.95}]}
        ```
        """;
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> agentOutput);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("escalated", review.status());
  }

  @Test
  void aSupersededHistoryStartsAFreshAttemptAtIterationOneInsteadOfEscalating() {
    createSpec("auth", "in_progress");
    var exhausted = Acting.system(() -> reviewStore.createReview("auth", 3));
    Acting.system(() -> reviewStore.updateReviewStatus(exhausted, "escalated"));
    Acting.system(() -> reviewStore.supersedeForSpec("auth"));
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals(1, review.iteration(), "a re-dispatch is a fresh attempt, not iteration 4");
    assertEquals("passed", review.status());
    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aWedgedRunningReviewFromAPriorAttemptDoesNotBlockAFreshOne() {
    createSpec("auth", "in_progress");
    var interrupted = Acting.system(() -> reviewStore.createReview("auth", 1));
    Acting.system(() -> reviewStore.updateReviewStatus(interrupted, "running"));
    Acting.system(() -> reviewStore.supersedeForSpec("auth"));
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(
        "passed",
        reviewStore.latestReviewForSpec("auth").orElseThrow().status(),
        "superseded rows are a closed attempt; even a running one must not skip the review");
  }

  @Test
  void mediumFindingPassesNoCriticalGate() {
    createSpec("auth", "in_progress");
    var agentOutput =
        """
        ```json
        {"verdicts": [], "findings": [{"severity": "MEDIUM", "category": "LOGIC", "file": "a.java",
          "line_start": 1, "line_end": 1, "title": "Minor issue",
          "description": "Not great", "confidence": 0.5}]}
        ```
        """;
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> agentOutput);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("passed", review.status());
  }

  @Test
  void twoStagesPipelineBothPass() {
    createSpec("auth", "in_progress");
    var ctrl =
        controller(p -> twoAgentStages(), p -> "codex", (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("passed", review.status());

    var stages = reviewStore.stagesForReview(review.id());
    assertEquals(2, stages.size());
    assertEquals("passed", stages.get(0).status());
    assertEquals("passed", stages.get(1).status());
  }

  @Test
  void aCarriedFindingReturnsToItsOwnStageAndFacesItsOwnGate() {
    createSpec("auth", "in_progress");
    var config =
        ReviewPipelineConfig.fromMap(
            Map.of(
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
                        "no_critical"),
                    Map.of(
                        "name",
                        "correctness",
                        "type",
                        "agent",
                        "agent",
                        "claude-code",
                        "gate",
                        "no_critical_or_high"))));
    var highOutput =
        """
        ```json
        {"verdicts": [], "findings": [{"severity": "HIGH", "category": "LOGIC", "file": "Pager.java",
          "line_start": 3, "line_end": 3, "title": "Persistent high",
          "description": "d", "evidence": "e", "confidence": 0.9,
          "suggestion": {"before": "old", "after": "new", "rationale": "r"}}]}
        ```
        """;
    var promptsByAgent = new HashMap<String, List<String>>();
    ScriptedAgent runner =
        (p, agent, prompt, rid, cred) -> {
          if (!prompt.contains("Review the changes on branch")) {
            return "fix applied";
          }
          var prompts = promptsByAgent.computeIfAbsent(agent, k -> new ArrayList<>());
          prompts.add(prompt);
          if (agent.equals("claude-code")) {
            return prompts.size() == 1 ? highOutput : fixAllCarried(prompt);
          }
          return CLEAN_REVIEW;
        };
    var ctrl = controller(config, runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
    var codexPrompts = promptsByAgent.get("codex");
    assertEquals(2, codexPrompts.size());
    assertTrue(
        codexPrompts.stream().allMatch(prompt -> ReviewScripts.carriedFromPrompt(prompt).isEmpty()),
        "a HIGH from the strict later stage must never be re-judged under the first stage's"
            + " looser gate");
    var claudePrompts = promptsByAgent.get("claude-code");
    assertEquals(2, claudePrompts.size());
    assertTrue(
        claudePrompts.get(1).contains("Persistent high"),
        "the finding returns to the stage that emitted it");
  }

  @Test
  void aMisaddressedVerdictIsFlaggedInTheRoomNotSilentlyLeftOpen() {
    createSpec("auth", "in_progress");
    var messages = loop.messages;
    var highOutput =
        """
        ```json
        {"verdicts": [], "findings": [{"severity": "HIGH", "category": "LOGIC", "file": "a.java",
          "line_start": 1, "line_end": 1, "title": "Stubborn high",
          "description": "d", "evidence": "e", "confidence": 0.9,
          "suggestion": {"before": "old", "after": "new", "rationale": "r"}}]}
        ```
        """;
    var ghostVerdict =
        """
        ```json
        {"verdicts": [{"finding_id": "ghost-not-a-real-id", "verdict": "fixed",
          "evidence": "commit abc"}], "findings": []}
        ```
        """;
    var calls = new AtomicInteger();
    ScriptedAgent runner =
        (p, a, prompt, rid, cred) ->
            switch (calls.incrementAndGet()) {
              case 1 -> highOutput;
              case 3 -> ghostVerdict;
              case 2, 4 -> "fix applied";
              default -> fixAllCarried(prompt);
            };
    var ctrl = controller(p -> singleAgentStage("no_critical_or_high"), p -> "codex", runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertTrue(
        messages.list("auth", null, 50).stream()
            .anyMatch(m -> m.body().contains("match no open finding")),
        "a verdict naming an id no carried finding holds must be flagged in the room — the finding"
            + " reads still-open but the reviewer meant to rule it, so a human is told to check the"
            + " log rather than trust the open count");
  }

  @Test
  void aRulingOnAFindingAHumanResolvedDuringTheStageIsSetAsideAndFlaggedInTheRoom() {
    createSpec("auth", "in_progress");
    var messages = loop.messages;
    var highOutput =
        """
        ```json
        {"verdicts": [], "findings": [{"severity": "HIGH", "category": "LOGIC", "file": "a.java",
          "line_start": 1, "line_end": 1, "title": "Stubborn high",
          "description": "d", "evidence": "e", "confidence": 0.9,
          "suggestion": {"before": "old", "after": "new", "rationale": "r"}}]}
        ```
        """;
    var calls = new AtomicInteger();
    ScriptedAgent runner =
        (p, a, prompt, rid, cred) ->
            switch (calls.incrementAndGet()) {
              case 1 -> highOutput;
              case 2 -> "fix applied";
              case 3 -> {
                var carried = ReviewScripts.carriedFromPrompt(prompt).keySet().iterator().next();
                Acting.as(
                    "uday",
                    () ->
                        reviewStore.resolveFinding(
                            carried, Finding.Resolution.DISMISSED, "by design"));
                yield fixAllCarried(prompt);
              }
              default -> fixAllCarried(prompt);
            };
    var ctrl = controller(p -> singleAgentStage("no_critical_or_high"), p -> "codex", runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
    assertTrue(
        messages.list("auth", null, 50).stream()
            .anyMatch(m -> m.body().contains("resolved while the stage ran")),
        "the human's dismissal stands and the room is told the reviewer's ruling was set aside");
    assertTrue(
        reviewStore
            .findingsForReview(reviewStore.latestReviewForSpec("auth").orElseThrow().id())
            .stream()
            .noneMatch(f -> f.carriedFrom() != null),
        "a dismissed finding is not carried into the next iteration");
  }

  @Test
  void aHumanStageOpensWithTheRoomVerdictListingDisputedFindings() {
    createSpec("auth", "in_progress");
    var messages = loop.messages;
    var config =
        ReviewPipelineConfig.fromMap(
            Map.of(
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
                        "no_critical_or_high"),
                    Map.of("name", "human", "type", "human"))));
    var highOutput =
        """
        ```json
        {"verdicts": [], "findings": [{"severity": "HIGH", "category": "SECURITY", "file": "Auth.java",
          "line_start": 7, "line_end": 7, "title": "Unvalidated input",
          "description": "d", "evidence": "e", "confidence": 0.9,
          "suggestion": {"before": "old", "after": "new", "rationale": "r"}}]}
        ```
        """;
    var calls = new AtomicInteger();
    ScriptedAgent runner =
        (p, a, prompt, rid, cred) ->
            switch (calls.incrementAndGet()) {
              case 1 -> highOutput;
              case 2 -> "fix applied";
              default -> ReviewScripts.disputeAllCarried(prompt);
            };
    var ctrl = controller(config, runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("running", review.status());
    assertEquals("running", reviewStore.stagesForReview(review.id()).get(1).status());
    var verdict = messages.list("auth", null, 50).getLast();
    assertTrue(verdict.body().contains("Awaiting human approval"), verdict.body());
    assertTrue(
        verdict.body().contains("Unvalidated input"),
        "the disputed finding the gate excluded must face the human before approval");
    assertTrue(verdict.body().contains("input is validated upstream"), verdict.body());
  }

  @Test
  void everyDisputedFindingReachesTheHumanVerdictUntruncated() {
    createSpec("auth", "in_progress");
    var messages = loop.messages;
    var config =
        ReviewPipelineConfig.fromMap(
            Map.of(
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
                        "no_critical_or_high"),
                    Map.of("name", "human", "type", "human"))));
    var elevenHighs =
        IntStream.rangeClosed(1, 11)
            .mapToObj(
                i ->
                    ("{\"severity\": \"HIGH\", \"category\": \"LOGIC\", \"file\": \"a.java\","
                            + " \"line_start\": %d, \"line_end\": %d, \"title\": \"Wrong claim"
                            + " %d\", \"description\": \"d\", \"confidence\": 0.8}")
                        .formatted(i, i, i))
            .collect(Collectors.joining(", "));
    var calls = new AtomicInteger();
    ScriptedAgent runner =
        (p, a, prompt, rid, cred) ->
            switch (calls.incrementAndGet()) {
              case 1 -> "{\"verdicts\": [], \"findings\": [" + elevenHighs + "]}";
              case 2 -> "fix applied";
              default -> ReviewScripts.disputeAllCarried(prompt);
            };
    var ctrl = controller(config, runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var verdict = messages.list("auth", null, 50).getLast().body();
    assertTrue(verdict.contains("Awaiting human approval"), verdict);
    IntStream.rangeClosed(1, 11)
        .forEach(
            i ->
                assertTrue(
                    verdict.contains("Wrong claim " + i),
                    "every gate-excluded dispute must face the human with its identity and"
                        + " argument, never as a count: missing 'Wrong claim "
                        + i
                        + "' in: "
                        + verdict));
    assertFalse(verdict.contains("more"), verdict);
  }

  @Test
  void humanStageStopsAndWaits() {
    createSpec("auth", "in_progress");
    var ctrl =
        controller(p -> agentThenHuman(), p -> "codex", (p, a, pr, rid, cred) -> CLEAN_REVIEW);

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
    var ctrl = controller(p -> null, p -> "codex", (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(SpecStatus.REVIEW, specStore.findById("auth").orElseThrow().status());
    assertTrue(reviewStore.reviewsForSpec("auth").isEmpty());
  }

  @Test
  void emptyPipelineConfigSkipsReview() {
    createSpec("auth", "in_progress");
    var emptyConfig = ReviewPipelineConfig.fromMap(Map.of());
    var ctrl = controller(emptyConfig, (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(SpecStatus.REVIEW, specStore.findById("auth").orElseThrow().status());
    assertTrue(reviewStore.reviewsForSpec("auth").isEmpty());
  }

  @Test
  void agentRunnerExceptionFailsStage() {
    createSpec("auth", "in_progress");
    ScriptedAgent failing =
        (p, a, pr, rid, cred) -> {
          throw new RuntimeException("Agent crashed");
        };
    var ctrl = controller(singleAgentStage("no_critical"), failing);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("failed", review.status());
  }

  @Test
  void subscriberNameIsReviewPipeline() {
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);
    assertEquals("review-pipeline", ctrl.controller.name());
  }

  @Test
  void reviewPromptIncludesCategories() {
    createSpec("auth", "in_progress");
    var capturedPrompt = new AtomicReference<String>();
    ScriptedAgent capturing =
        (p, a, prompt, rid, cred) -> {
          capturedPrompt.set(prompt);
          return CLEAN_REVIEW;
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
        {"verdicts": [], "findings": [{"severity": "CRITICAL", "category": "SECURITY", "file": "a.java",
          "line_start": 1, "line_end": 1, "title": "Bad",
          "description": "Very bad", "confidence": 0.9,
          "suggestion": {"before": "old", "after": "new", "rationale": "fix it"}}]}
        ```
        """;
    var callCount = new AtomicInteger(0);
    ScriptedAgent runner =
        (p, a, prompt, rid, cred) -> {
          var call = callCount.incrementAndGet();
          return call == 1 ? criticalOutput : fixAllCarried(prompt);
        };
    var ctrl = controller(singleAgentStage("no_critical"), runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertTrue(callCount.get() >= 2);
    var reviews = reviewStore.reviewsForSpec("auth");
    assertFalse(reviews.isEmpty());
  }

  @Test
  void everyAgentInvocationCarriesItsOwnReviewsId() {
    createSpec("auth", "in_progress");
    var criticalOutput =
        """
        ```json
        {"verdicts": [], "findings": [{"severity": "CRITICAL", "category": "SECURITY", "file": "a.java",
          "line_start": 1, "line_end": 1, "title": "Bad",
          "description": "Very bad", "confidence": 0.9,
          "suggestion": {"before": "old", "after": "new", "rationale": "fix it"}}]}
        ```
        """;
    var reviewIds = new ArrayList<String>();
    ScriptedAgent runner =
        (p, a, prompt, rid, cred) -> {
          reviewIds.add(rid);
          return reviewIds.size() == 1 ? criticalOutput : fixAllCarried(prompt);
        };
    var ctrl = controller(singleAgentStage("no_critical"), runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var reviews = reviewStore.reviewsForSpec("auth");
    assertEquals(2, reviews.size(), "the failed review and its re-review");
    assertEquals(3, reviewIds.size(), "review, fix, re-review");
    assertEquals(
        reviews.get(0).id(), reviewIds.get(0), "the reviewer runs under the review it reports to");
    assertEquals(
        reviews.get(0).id(),
        reviewIds.get(1),
        "the fix agent appends to the failed review's own log, not a shared one");
    assertEquals(
        reviews.get(1).id(),
        reviewIds.get(2),
        "the re-review owns a fresh identity, so it can never read another review's bytes");
  }

  @Test
  void maxIterationsEscalates() {
    createSpec("auth", "in_progress");
    var criticalOutput =
        """
        ```json
        {"verdicts": [], "findings": [{"severity": "CRITICAL", "category": "SECURITY", "file": "a.java",
          "line_start": 1, "line_end": 1, "title": "Persistent issue",
          "description": "Cannot fix", "confidence": 0.95}]}
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
    var ctrl = controller(p -> config, p -> "codex", (p, a, pr, rid, cred) -> criticalOutput);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("escalated", review.status());
  }

  @Test
  void erroredRetriesAreBoundedSoARescueLoopCanNeverBurnAgentsForever() {
    createSpec("auth", "in_progress");
    var ctrl =
        controller(
            singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> "prose with no fenced block");

    ctrl.onEvent(agentStoppedEvent("auth"));
    ctrl.onEvent(reconcilerStoppedEvent("auth"));
    ctrl.onEvent(reconcilerStoppedEvent("auth"));

    var reviews = reviewStore.reviewsForSpec("auth");
    assertEquals(3, reviews.size());
    assertTrue(
        reviews.stream().allMatch(r -> r.errored() && r.iteration() == 1),
        "errored attempts retry the same iteration");

    ctrl.onEvent(reconcilerStoppedEvent("auth"));

    assertEquals(
        3,
        reviewStore.reviewsForSpec("auth").size(),
        "the fourth stop must escalate instead of starting a fourth doomed review");
    assertEquals("escalated", reviewStore.latestReviewForSpec("auth").orElseThrow().status());
    var detail = detailOf(loop.events("review_escalated").getFirst());
    assertTrue(
        detail.contains("errored"),
        "escalation must say WHY — an error budget, not exhausted iterations: " + detail);
  }

  @Test
  void anUnparseableReviewNarratesAsErroredNotAsAFailedGate() {
    createSpec("auth", "in_progress");
    var ctrl =
        controller(
            singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> "no fenced block here");

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(
        List.of("review_errored"),
        loop.published.stream()
            .map(Event::type)
            .filter(Set.of("review_errored", "review_stage_failed")::contains)
            .toList(),
        "a parse failure is an infrastructure error, not a gate verdict — one message, not a"
            + " misleading 'stage failed (no findings)' followed by 'errored'");
  }

  @Test
  void aFixIterationCommitsWorkTheAgentLeftUncommitted() {
    createSpec("auth", "in_progress", List.of("api", "web"));
    loop.container.dirty("api", " M Api.java\n?? ApiTest.java\n");
    loop.container.dirty("web", " M App.tsx\n");
    var calls = new AtomicInteger();
    var ctrl =
        controller(
            singleAgentStage("no_critical"),
            (p, a, prompt, rid, cred) ->
                calls.incrementAndGet() == 1 ? CRITICAL_FINDING : fixAllCarried(prompt));

    ctrl.onEvent(agentStoppedEvent("auth"));

    var commits = loop.container.commits();
    assertEquals(
        List.of("api", "web"),
        commits.stream().map(commit -> commit.substring(0, commit.indexOf(':'))).toList(),
        "after the fix agent runs, what it left uncommitted on the spec branch is committed");
    assertTrue(
        commits.stream().allMatch(commit -> commit.contains("Bad")),
        "the rescue commit message names the findings the fix addressed, so the PR history"
            + " explains itself instead of reading 'left uncommitted by the agent': "
            + commits);
    var guardrails = loop.events(Event.WellKnownTypes.GUARDRAIL_TRIGGERED);
    assertEquals(1, guardrails.size());
    var reason = Objects.toString(guardrails.getFirst().data().get("reason"), "");
    assertTrue(
        reason.contains("api (2 files: Api.java, ApiTest.java)"),
        "the guardrail names the contaminated repo and the files it swept, so debris is visible"
            + " the moment it happens: "
            + reason);
    assertTrue(
        reason.contains("web (1 file: App.tsx)"),
        "every rescued repo is named, joined into one readable line: " + reason);
    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void theFixLaneCarriesTheSpecBranchReposAndTuningIntoTheGate() {
    createSpec("auth", "in_progress", List.of("api"), "codex", "gpt-5", "xhigh");
    var fixLaunch = new AtomicReference<List<Object>>();
    var reviewTuning = new ArrayList<String>();
    var calls = new AtomicInteger();
    var runner =
        new ScriptedAgent() {
          @Override
          public String run(String p, String a, String prompt, String rid, String cred) {
            return calls.incrementAndGet() == 1 ? CRITICAL_FINDING : fixAllCarried(prompt);
          }

          @Override
          public String run(
              String p,
              String a,
              String prompt,
              String rid,
              String cred,
              String model,
              String effort) {
            reviewTuning.add(model + "/" + effort);
            return run(p, a, prompt, rid, cred);
          }

          @Override
          public String runFix(
              String p,
              String a,
              String prompt,
              String rid,
              String cred,
              String branch,
              List<String> repos,
              String model,
              String effort) {
            fixLaunch.set(List.of(a, branch, repos, model, effort));
            return "done";
          }
        };
    var ctrl = controller(singleAgentStage("no_critical"), runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
    assertEquals(
        List.of("codex", "feat/test", List.of("api"), "gpt-5", "xhigh"),
        fixLaunch.get(),
        "the fix agent launches as the spec's own agent with the spec's branch, repo scope,"
            + " model, and reasoning effort — a spec dispatched at xhigh is fixed at xhigh");
    var fix = loop.runsIn("auth", "fix").getFirst();
    var session = YamlUtil.parseMap(loop.container.file(AgentUnit.forRun(fix.id()).sessionPath()));
    assertEquals("feat/test", session.get("branch"), "the stop gate asks for this branch pushed");
    assertEquals(
        List.of("api"), session.get("repos"), "the stop gate is scoped to the spec's repos");
    assertEquals("fix", session.get("role"));
    assertEquals(
        List.of("null/xhigh", "null/xhigh"),
        reviewTuning,
        "the reviewer judges at the spec's effort; the model stays out of the review lane"
            + " because model names are agent-specific and the reviewer is the other agent");
  }

  @Test
  void aFixCrashSurfacesAsIterationFailedAndEscalatesInsteadOfReReviewingUnfixedCode() {
    createSpec("auth", "in_progress", List.of("api"));
    loop.container.dirty("api", " M Half.java\n");
    var runner =
        new ScriptedAgent() {
          @Override
          public String run(String p, String a, String prompt, String rid, String cred) {
            return CRITICAL_FINDING;
          }

          @Override
          public String runFix(
              String p,
              String a,
              String prompt,
              String rid,
              String cred,
              String branch,
              List<String> repos,
              String model,
              String effort) {
            throw new IllegalStateException("fix agent quota exceeded");
          }
        };
    var ctrl = controller(singleAgentStage("no_critical"), runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(
        1,
        reviewStore.reviewsForSpec("auth").size(),
        "a crashed fix must not spawn a second review — the branch still holds the unfixed code"
            + " the reviewer just failed");
    assertEquals("escalated", reviewStore.latestReviewForSpec("auth").orElseThrow().status());
    assertEquals(
        List.of("fix agent failed: exit 1"),
        loop.events("review_iteration_failed").stream()
            .map(ReviewPipelineControllerTest::detailOf)
            .toList(),
        "the fix crash must surface as an event, never be swallowed to stderr");
    assertEquals(
        List.of("fix iteration failed — fix agent failed: exit 1; triage and re-dispatch"),
        loop.events("review_escalated").stream()
            .map(ReviewPipelineControllerTest::detailOf)
            .toList());
    assertTrue(
        loop.container.commits().isEmpty(),
        "nothing a failed fix agent left behind is committed to the spec's branch");
  }

  @Test
  void aReviewRunActsForTheBoxThatRunsItWhoeverTheSpecIsAssignedTo() {
    createSpec("auth", "in_progress");
    db.execute("UPDATE specs SET assignee = 'bob' WHERE id = 'auth'");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals("node-a", loop.runsIn("auth", "review").getFirst().owner());
  }

  @Test
  void theFixTaskCarriesTheRoomAndSeedsTheDeliveryWatermark() {
    createSpec("auth", "in_progress", List.of("api"));
    var messages = loop.messages;
    var oversized =
        Acting.system(
            () ->
                messages.append(
                    "auth", "uday", "x".repeat(PromptConversation.MAX_CODE_POINTS + 1_000), null));
    var guidance =
        Acting.system(
            () -> messages.append("auth", "uday", "the retry finding is intentional", null));
    var fixPrompt = new AtomicReference<String>();
    var calls = new AtomicInteger();
    var runner =
        new ScriptedAgent() {
          @Override
          public String run(String p, String a, String prompt, String rid, String cred) {
            return calls.incrementAndGet() == 1 ? CRITICAL_FINDING : fixAllCarried(prompt);
          }

          @Override
          public String runFix(
              String p,
              String a,
              String prompt,
              String rid,
              String cred,
              String branch,
              List<String> repos,
              String model,
              String effort) {
            fixPrompt.set(prompt);
            return "done";
          }
        };
    var ctrl = controller(singleAgentStage("no_critical"), runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertTrue(
        fixPrompt.get().contains("Conversation on this spec"),
        "the fix task renders the room: " + fixPrompt.get());
    assertTrue(
        fixPrompt.get().contains("uday: the retry finding is intentional"),
        "human guidance on disputed findings reaches the fix turn");
    var seeded = runStore.deliveredMessageIds(loop.runsIn("auth", "fix").getFirst().id());
    assertTrue(
        seeded.contains(guidance.id()),
        "rendered messages count as delivered: the fix run's own ledger seeds at its launch");
    assertFalse(
        seeded.contains(oversized.id()),
        "delivery derives from presentation: a message the prompt budget truncated was never"
            + " presented in full and stays owed a delivery through the relay or the stop"
            + " gate");
  }

  @Test
  void aReviewersLedgerHoldsOnlyTheMessagesItsPromptRenderedInFull() {
    createSpec("auth", "in_progress");
    var oversized =
        Acting.system(
            () ->
                loop.messages.append(
                    "auth", "uday", "x".repeat(PromptConversation.MAX_CODE_POINTS + 1_000), null));
    var guidance =
        Acting.system(
            () -> loop.messages.append("auth", "uday", "the retry finding is intentional", null));
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var reviewer = loop.runsIn("auth", "review").getFirst();
    assertTrue(
        reviewer.task().contains("uday: the retry finding is intentional"),
        "the review prompt renders the room");
    var seeded = runStore.deliveredMessageIds(reviewer.id());
    assertTrue(
        seeded.contains(guidance.id()),
        "rendered messages count as delivered: the reviewer's own ledger seeds at its launch");
    assertFalse(
        seeded.contains(oversized.id()),
        "delivery derives from presentation: a message the review prompt truncated was never"
            + " presented in full and stays owed a delivery through the relay or the stop"
            + " gate");
  }

  @Test
  void theLoopNarratesVerdictsIntoTheSpecRoom() {
    createSpec("auth", "in_progress", List.of("api"));
    var messages = loop.messages;
    var failedOutput =
        """
        ```json
        {"verdicts": [], "findings": [{"severity": "CRITICAL", "category": "SECURITY", "file": "Auth.java",
          "line_start": 7, "line_end": 7, "title": "Token logged in plaintext",
          "description": "d", "confidence": 0.9}]}
        ```
        """;
    var lowFinding =
        """
        [{"severity": "LOW", "category": "LOGIC", "file": "Pager.java",
          "line_start": 3, "line_end": 3, "title": "Off-by-one in pager",
          "description": "d", "confidence": 0.6}]""";
    var calls = new AtomicInteger();
    ScriptedAgent runner =
        (p, a, pr, rid, cred) ->
            calls.incrementAndGet() == 1
                ? failedOutput
                : ReviewScripts.fixAllCarried(pr, lowFinding);
    var ctrl = controller(singleAgentStage("no_critical"), runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var room = messages.list("auth", null, 50);
    assertEquals(
        2,
        room.size(),
        "one message per verdict and nothing else — lifecycle beats ride the event stream;"
            + " the room carries only what events cannot: the findings themselves");
    var failed = room.get(0);
    assertEquals("sail", failed.author());
    assertTrue(failed.body().contains("Review failed"), failed.body());
    assertTrue(
        failed.body().contains("Token logged in plaintext"),
        "the failed verdict names its findings — the reviewer's next pass reads the room, so"
            + " this is also the loop's cross-iteration memory");
    assertTrue(failed.body().contains("Auth.java:7"), failed.body());
    var passed = room.get(1);
    assertTrue(passed.body().contains("Review passed"), passed.body());
    assertTrue(
        passed.body().contains("Off-by-one in pager"),
        "sub-gate findings on a passed review deserve eyes before merge, not silence");
  }

  @Test
  void aRoomThatCannotBeWrittenLeavesThePassUnwrittenNotAVerdictNobodyWasTold() {
    createSpec("auth", "in_progress");
    var brokenDb = Sqlite.open(tempDir.resolve("broken.db"));
    new SchemaManager(brokenDb).migrate();
    var brokenMessages = new MessageStore(brokenDb);
    brokenDb.close();

    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);
    ctrl.controller.useMessages(brokenMessages);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(
        SpecStatus.REVIEW,
        specStore.findById("auth").orElseThrow().status(),
        "a review's end is one write: its status, its spec's and the room line land together");
    assertEquals(
        "running",
        reviewStore.latestReviewForSpec("auth").orElseThrow().status(),
        "the review is still owed its end, and the next replay gives it");
  }

  @Test
  void aRunningReviewNoRunServesGoesOnFromItsStageRowsAtTheNextBuildStop() {
    createSpec("auth", "review");
    var reviewId = Acting.system(() -> reviewStore.createReview("auth", 1));
    Acting.system(() -> reviewStore.updateReviewStatus(reviewId, "running"));
    Acting.system(() -> reviewStore.createStage(reviewId, "security", "agent"));
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(
        List.of(reviewId),
        reviewStore.reviewsForSpec("auth").stream().map(ReviewStore.ReviewRow::id).toList(),
        "the interrupted review goes on; no second one starts beside it");
    assertEquals("passed", reviewStore.findReview(reviewId).orElseThrow().status());
    assertEquals(reviewId, loop.runsIn("auth", "review").getFirst().reviewId());
    assertEquals(
        List.of("review_stage_started", "review_stage_passed", "review_completed"),
        loop.published.stream()
            .map(Event::type)
            .filter(type -> type.startsWith("review_"))
            .toList());
    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aRunningReviewMissingItsStageRowsIsNeverPassedUnreviewed() {
    createSpec("auth", "review");
    var reviewId = Acting.system(() -> reviewStore.createReview("auth", 1));
    Acting.system(() -> reviewStore.updateReviewStatus(reviewId, "running"));
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertTrue(
        specStore.findById("auth").orElseThrow().status() != SpecStatus.AWAITING_MERGE
            || !loop.container.launched().isEmpty(),
        "a review interrupted before its stage rows were written has judged nothing: with no"
            + " reviewer ever launched, the spec must not be parked as reviewed (review "
            + reviewStore.findReview(reviewId).orElseThrow().status()
            + ")");
  }

  @Test
  void stageEventsCarryFindingCountsBySeverity() {
    createSpec("auth", "in_progress");
    var agentOutput =
        """
        ```json
        {"verdicts": [], "findings": [{"severity": "HIGH", "category": "SECURITY", "file": "Auth.java",
          "line_start": 1, "line_end": 1, "title": "SQL injection",
          "description": "d", "confidence": 0.9},
         {"severity": "HIGH", "category": "SECURITY", "file": "Auth.java",
          "line_start": 2, "line_end": 2, "title": "XSS",
          "description": "d", "confidence": 0.9},
         {"severity": "LOW", "category": "LOGIC", "file": "Auth.java",
          "line_start": 3, "line_end": 3, "title": "Naming",
          "description": "d", "confidence": 0.9}]}
        ```
        """;
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> agentOutput);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var event = loop.events("review_stage_passed").getFirst();
    assertEquals("security", event.data().get("detail"));
    assertEquals(Map.of("high", 2, "low", 1), event.data().get("findings"));
  }

  @Test
  void cleanStageEventOmitsFindingCounts() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertFalse(loop.events("review_stage_passed").getFirst().data().containsKey("findings"));
  }

  private static String detailOf(Event event) {
    return Objects.toString(event.data().get("detail"), "");
  }

  @Test
  void aHandlerFailurePublishesALoudPipelineErrorEvent() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);
    db.close();

    ctrl.onEvent(agentStoppedEvent("auth"));

    var errors = loop.events("review_pipeline_error");
    assertEquals(1, errors.size());
    assertEquals("auth", errors.getFirst().spec());
    assertTrue(detailOf(errors.getFirst()).contains("closed"));
  }

  @Test
  void aRunningReviewIsNotRestartedByADuplicateEvent() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), null);
    ctrl.onEvent(agentStoppedEvent("auth"));
    var reviewer = loop.live().getFirst();

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(1, reviewStore.reviewsForSpec("auth").size());
    assertEquals(List.of(reviewer.id()), loop.container.launched());
  }

  @Test
  void reviewRunIsVisibleWhileTheAgentRunsAndStoppedOnExit() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), null);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals(
        "running",
        review.status(),
        "the build's stop is handled once the reviewer is launched; nothing waits on the agent");
    var running = loop.live().getFirst();
    assertEquals("review", running.role());
    assertEquals(review.id(), running.reviewId());
    assertEquals("running", running.status());
    assertEquals("node-a", running.node());
    assertEquals("codex", running.agent());
    assertEquals("feat/test", running.branch());
    assertEquals("/home/dev/.sail/runs/" + running.id() + "/agent.log", running.logPath());

    ctrl.finish(running.id(), CLEAN_REVIEW);

    var stopped = runStore.findById(running.id()).orElseThrow();
    assertEquals("stopped", stopped.status());
    assertEquals(0, stopped.exitCode());
    assertNotNull(stopped.completedAt());
    assertEquals("passed", reviewStore.findReview(review.id()).orElseThrow().status());
  }

  @Test
  void reviewRunRecordsTheAgentExitCodeOnFailure() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), null);
    ctrl.onEvent(agentStoppedEvent("auth"));
    var reviewer = loop.live().getFirst();

    ctrl.exit(reviewer.id(), "quota", 17);

    var failed = runStore.findById(reviewer.id()).orElseThrow();
    assertEquals("stopped", failed.status());
    assertEquals(17, failed.exitCode());
    assertNotNull(failed.completedAt());
    assertEquals(
        "reviewer failed: exit 17",
        reviewStore.latestReviewForSpec("auth").orElseThrow().error(),
        "the review says why its reviewer gave no verdict");
  }

  @Test
  void reviewerAndFixAgentsEachReceiveALiveCredentialForTheirOwnRun() {
    createSpec("auth", "in_progress");
    var calls = new AtomicInteger();
    var invocations = new ArrayList<RunStore.RunRow>();
    ScriptedAgent runner =
        (p, a, pr, rid, cred) -> {
          invocations.add(runStore.findByCredential(cred).orElseThrow());
          return calls.incrementAndGet() == 1 ? CRITICAL_FINDING : fixAllCarried(pr);
        };
    var ctrl = controller(singleAgentStage("no_critical"), runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(3, invocations.size(), "reviewer, fix agent, and re-reviewer all ran");
    assertTrue(
        invocations.stream().allMatch(run -> "running".equals(run.status())),
        "every invocation carries a credential that resolves to a live run");
    assertEquals(
        3,
        invocations.stream().map(RunStore.RunRow::id).distinct().count(),
        "each invocation is a run of its own");
    var reviews = reviewStore.reviewsForSpec("auth");
    var reviewer = invocations.get(0);
    var fix = invocations.get(1);
    var reReviewer = invocations.get(2);
    assertEquals("review", reviewer.role());
    assertEquals(
        "codex/review-" + reviewer.id(),
        reviewer.principal(),
        "the reviewer invocation acts as the review principal");
    assertEquals("fix", fix.role());
    assertEquals(
        "claude/fix-" + fix.id(),
        fix.principal(),
        "the fix invocation acts as its own fix principal, never the reviewer's");
    assertEquals(reviews.get(0).id(), fix.reviewId(), "the fix run serves the review that failed");
    assertEquals("codex/review-" + reReviewer.id(), reReviewer.principal());
    assertEquals(reviews.get(1).id(), reReviewer.reviewId());
  }

  @Test
  void eachInvocationStampsItsOwnIdentitySoRoomPostsCarryTheHonestAuthor() {
    createSpec("auth", "in_progress");
    var messages = loop.messages;
    var calls = new AtomicInteger();
    ScriptedAgent runner =
        (p, a, pr, rid, cred) -> {
          var principal = runStore.findByCredential(cred).orElseThrow().principal();
          Acting.system(() -> messages.append("auth", principal, "posted by " + principal, null));
          return calls.incrementAndGet() == 1 ? CRITICAL_FINDING : fixAllCarried(pr);
        };
    var ctrl = controller(singleAgentStage("no_critical"), runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var reviewers = loop.runsIn("auth", "review");
    var fix = loop.runsIn("auth", "fix").getFirst();
    assertEquals(
        "codex/review-" + reviewers.get(0).id(),
        reviewers.get(0).principal(),
        "the fix agent ran as a run of its own, so the reviewer's row still reads the reviewer");
    assertEquals("claude/fix-" + fix.id(), fix.principal());
    assertEquals(
        "codex/review-" + reviewers.get(1).id(),
        reviewers.get(1).principal(),
        "the next reviewer invocation carries its own reviewer identity");
    assertEquals(
        List.of(reviewers.get(0).principal(), fix.principal(), reviewers.get(1).principal()),
        messages.listAfter("auth", null, 10).stream()
            .map(MessageStore.MessageRow::author)
            .filter(author -> !MessageStore.SAIL_AUTHOR.equals(author))
            .toList(),
        "each room post is attributed to the lane that wrote it");
  }

  @Test
  void aStillOpenRulingsEvidenceReachesTheNextFixTaskAndTheReReview() {
    createSpec("auth", "in_progress");
    var criticalOutput =
        """
        ```json
        {"verdicts": [], "findings": [{"severity": "CRITICAL", "category": "CONCURRENCY", "file": "Dispatch.java",
          "line_start": 5, "line_end": 9, "title": "Seed-window race",
          "description": "Two dispatches can claim one seed.", "confidence": 0.9}]}
        ```
        """;
    var evidence = "the seed window in DispatchOperations still races between reserve and claim";
    var prompts = new ArrayList<String>();
    ScriptedAgent runner =
        (p, a, pr, rid, cred) -> {
          prompts.add(pr);
          return switch (prompts.size()) {
            case 1 -> criticalOutput;
            case 3 -> ReviewScripts.stillOpenAllCarried(pr, evidence);
            case 5 -> fixAllCarried(pr);
            default -> "fix applied";
          };
        };
    var ctrl = controller(singleAgentStage("no_critical"), runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
    assertEquals(5, prompts.size(), "three reviews with a fix iteration between each");
    var firstFixTask = prompts.get(1);
    assertFalse(
        firstFixTask.contains("Reviewer's evidence that this remains open"),
        "the first fix task predates any ruling and carries no reproduction claim");
    var secondFixTask = prompts.get(3);
    assertTrue(
        secondFixTask.contains("Reviewer's evidence that this remains open: " + evidence),
        secondFixTask);
    assertTrue(secondFixTask.contains("Treat this as a reproduction claim"), secondFixTask);
    var thirdReviewPrompt = prompts.get(4);
    assertTrue(
        thirdReviewPrompt.contains("Prior ruling's evidence that it remains open: " + evidence),
        "the re-review rules against its own prior scenario");
  }

  @Test
  void aFixLaneCredentialResolvesToItsOwnRunningFixRun() {
    createSpec("auth", "in_progress");
    var calls = new AtomicInteger();
    var fixResolved = new AtomicReference<RunStore.RunRow>();
    ScriptedAgent runner =
        (p, a, pr, rid, cred) -> {
          if (calls.incrementAndGet() == 2) {
            fixResolved.set(runStore.findByCredential(cred).orElse(null));
          }
          return calls.get() == 1 ? CRITICAL_FINDING : fixAllCarried(pr);
        };
    var ctrl = controller(singleAgentStage("no_critical"), runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertNotNull(fixResolved.get(), "the fix agent's credential resolves to a run");
    assertEquals("fix", fixResolved.get().role());
    assertEquals(
        "running",
        fixResolved.get().status(),
        "the fix agent holds a live run of its own while it answers the review");
    assertEquals(
        reviewStore.reviewsForSpec("auth").getFirst().id(),
        fixResolved.get().reviewId(),
        "and that run names the failed review it serves");
  }

  @Test
  void onEventSwallowsHandlerExceptions() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);
    db.close();

    assertDoesNotThrow(() -> ctrl.controller.onEvent(agentStoppedEvent("auth")));
  }

  @Test
  void aHookTurnEndStopIsIgnoredUntilTheAuthoritativeStopArrives() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(hookTurnEndEvent("auth"));

    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertTrue(reviewStore.reviewsForSpec("auth").isEmpty());
  }

  @Test
  void nonZeroExitSkipsReviewAndLeavesSpecInProgress() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth", 137));

    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertTrue(reviewStore.reviewsForSpec("auth").isEmpty());
  }

  @Test
  void nonZeroExitPublishesAgentFailed() {
    createSpec("auth", "in_progress");
    var ctrl =
        controller(
            p -> singleAgentStage("no_critical"),
            p -> "codex",
            (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth", 1));

    assertEquals(
        List.of("exit 1"),
        loop.events(Event.WellKnownTypes.AGENT_FAILED).stream()
            .map(ReviewPipelineControllerTest::detailOf)
            .toList());
  }

  @Test
  void zeroExitStillRunsReview() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth", 0));

    assertEquals("passed", reviewStore.latestReviewForSpec("auth").orElseThrow().status());
  }

  @Test
  void reentryAfterMaxIterationsEscalates() {
    createSpec("auth", "in_progress");
    var reviewId = Acting.system(() -> reviewStore.createReview("auth", 3));
    Acting.system(() -> reviewStore.updateReviewStatus(reviewId, "failed"));
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals("escalated", reviewStore.findReview(reviewId).orElseThrow().status());
  }

  @Test
  void fixIterationAgentExceptionIsSwallowed() {
    createSpec("auth", "in_progress");
    var criticalOutput =
        """
        ```json
        {"verdicts": [], "findings": [{"severity": "CRITICAL", "category": "SECURITY", "file": "a.java",
          "line_start": 1, "line_end": 1, "title": "Bad",
          "description": "Very bad", "confidence": 0.9}]}
        ```
        """;
    var calls = new AtomicInteger(0);
    ScriptedAgent runner =
        (p, a, pr, rid, cred) -> {
          if (calls.incrementAndGet() == 1) return criticalOutput;
          throw new RuntimeException("fix agent crashed");
        };
    var ctrl = controller(singleAgentStage("no_critical"), runner);

    assertDoesNotThrow(() -> ctrl.onEvent(agentStoppedEvent("auth")));
    assertTrue(calls.get() >= 2, "the review ran and a fix was attempted");
  }

  private ReviewPipelineConfig noHighGate(int maxIterations) {
    return ReviewPipelineConfig.fromMap(
        Map.of(
            "max_iterations",
            maxIterations,
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
                    "no_critical_or_high"))));
  }

  private static final String CRITICAL_FINDING =
      """
      ```json
      {"verdicts": [], "findings": [{"severity": "CRITICAL", "category": "SECURITY", "file": "a.java",
        "line_start": 1, "line_end": 1, "title": "Bad",
        "description": "Very bad", "confidence": 0.9}]}
      ```
      """;

  private static final String HIGH_FINDING =
      """
      ```json
      {"verdicts": [], "findings": [{"severity": "HIGH", "category": "CONCURRENCY", "file": "Worker.java",
        "line_start": 9, "line_end": 9, "title": "Sticky high",
        "description": "d", "confidence": 0.9}]}
      ```
      """;

  @Test
  void aBareFindingsArrayIsOffContractAndErrorsTheStage() {
    createSpec("auth", "in_progress");
    var legacyArray =
        """
        ```json
        [{"severity": "HIGH", "category": "SECURITY", "file": "a.java",
          "line_start": 1, "line_end": 1, "title": "Bad", "description": "d", "confidence": 0.9}]
        ```
        """;
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> legacyArray);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("failed", review.status());
    assertTrue(
        review.errored(),
        "a bare findings array is off-contract reviewer output — it rides the errored-retry"
            + " lane, never burning an iteration and never passing as a verdict");
    assertTrue(review.error().contains("unparseable"), review.error());
  }

  @Test
  void aCarriedOpenHighFailsTheGateEvenWhenTheNewFindingsListIsClean() {
    createSpec("auth", "in_progress");
    var calls = new AtomicInteger();
    ScriptedAgent runner =
        (p, a, pr, rid, cred) -> calls.incrementAndGet() == 1 ? HIGH_FINDING : CLEAN_REVIEW;
    var ctrl = controller(p -> noHighGate(2), p -> "codex", runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var reviews = reviewStore.reviewsForSpec("auth");
    assertEquals(2, reviews.size());
    var carried = reviewStore.findingsForReview(reviews.get(1).id());
    assertEquals(1, carried.size(), "the unruled high re-attaches to the re-review");
    assertEquals("Sticky high", carried.getFirst().title());
    assertEquals(
        reviewStore.findingsForReview(reviews.get(0).id()).getFirst().id(),
        carried.getFirst().carriedFrom(),
        "the carried row chains to its predecessor — one finding, one lineage");
    assertEquals(
        "escalated",
        reviews.get(1).status(),
        "a reviewer that stops mentioning last iteration's high launders nothing — the high"
            + " stays open and keeps failing the gate");
    assertEquals(
        Finding.Resolution.OPEN,
        reviewStore.findingsForReview(reviews.get(0).id()).getFirst().resolution(),
        "history is never rewritten; the predecessor row stays open where it was found");
  }

  @Test
  void aGateBlockingFindingStillOpenTwiceEscalatesWithTheFindingNamed() {
    createSpec("auth", "in_progress");
    var calls = new AtomicInteger();
    ScriptedAgent runner =
        (p, a, pr, rid, cred) -> calls.incrementAndGet() == 1 ? HIGH_FINDING : CLEAN_REVIEW;

    var ctrl = controller(p -> noHighGate(5), p -> "codex", runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(
        3,
        reviewStore.reviewsForSpec("auth").size(),
        "the stuck loop is caught after 2 fix iterations, not after max_iterations=5");
    var detail = detailOf(loop.events("review_escalated").getFirst());
    assertTrue(detail.contains("Sticky high"), "escalation names the stuck finding: " + detail);
    assertTrue(detail.contains("survived 2 fix iterations"), detail);
  }

  @Test
  void anotherStagesAgedSubGateFindingNeverTripsTheFailingStagesConvergenceCheck() {
    createSpec("auth", "in_progress");
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
                        "gate",
                        "no_critical"),
                    Map.of(
                        "name",
                        "correctness",
                        "type",
                        "agent",
                        "agent",
                        "claude-code",
                        "gate",
                        "all_clear"))));
    var toleratedHigh =
        """
        ```json
        {"verdicts": [], "findings": [{"severity": "HIGH", "category": "SECURITY", "file": "a.java",
          "line_start": 1, "line_end": 1, "title": "Tolerated high",
          "description": "d", "confidence": 0.9}]}
        ```
        """;
    var codexCalls = new AtomicInteger();
    var claudeCalls = new AtomicInteger();
    ScriptedAgent runner =
        (p, agent, prompt, rid, cred) -> {
          if (!prompt.contains("Review the changes on branch")) {
            return "fix applied";
          }
          return switch (agent) {
            case "codex" -> codexCalls.incrementAndGet() == 1 ? toleratedHigh : CLEAN_REVIEW;
            default ->
                ReviewScripts.fixAllCarried(
                    prompt,
                    ("[{\"severity\": \"LOW\", \"category\": \"LOGIC\", \"file\": \"b.java\","
                            + " \"line_start\": 1, \"line_end\": 1, \"title\": \"Fresh low %d\","
                            + " \"description\": \"d\", \"confidence\": 0.4}]")
                        .formatted(claudeCalls.incrementAndGet()));
          };
        };

    var ctrl = controller(p -> config, p -> "codex", runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(
        3,
        reviewStore.reviewsForSpec("auth").size(),
        "a converging loop — new blocker each round — runs to max_iterations");
    var detail = detailOf(loop.events("review_escalated").getFirst());
    assertTrue(
        detail.contains("review iterations exhausted"),
        "the loop exhausts its budget instead of escalating a foreign stage's finding: " + detail);
    assertFalse(
        detail.contains("Tolerated high"),
        "the security stage's aged HIGH passes its own gate; the correctness gate must never"
            + " judge it: "
            + detail);
  }

  @Test
  void subGateFindingsMayAgeFreelyWithoutTrippingTheConvergenceEscalation() {
    createSpec("auth", "in_progress");
    var lowOutput =
        """
        ```json
        {"verdicts": [], "findings": [{"severity": "LOW", "category": "LOGIC", "file": "a.java",
          "line_start": 1, "line_end": 1, "title": "Aging low",
          "description": "d", "confidence": 0.4}]}
        ```
        """;
    var calls = new AtomicInteger();
    ScriptedAgent runner =
        (p, a, pr, rid, cred) -> calls.incrementAndGet() == 1 ? lowOutput : CLEAN_REVIEW;
    var ctrl = controller(p -> noHighGate(3), p -> "codex", runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(
        "passed",
        reviewStore.latestReviewForSpec("auth").orElseThrow().status(),
        "a sub-gate low never fails the gate, so it ages without escalating anything");
    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void passWithExplicitFixedVerdictsResolvesExactlyThoseAndToleratesUnknownIds() {
    createSpec("auth", "in_progress");
    var firstOutput =
        """
        ```json
        {"verdicts": [], "findings": [
          {"severity": "HIGH", "category": "SECURITY", "file": "a.java",
           "line_start": 1, "line_end": 1, "title": "Real bug", "description": "d", "confidence": 0.9},
          {"severity": "LOW", "category": "LOGIC", "file": "b.java",
           "line_start": 2, "line_end": 2, "title": "Nit", "description": "d", "confidence": 0.4}]}
        ```
        """;
    var calls = new AtomicInteger();
    ScriptedAgent runner =
        (p, a, pr, rid, cred) -> {
          if (calls.incrementAndGet() == 1) {
            return firstOutput;
          }
          if (!pr.contains("Review the changes on branch")) {
            return "fix applied";
          }
          return """
              {"verdicts": [
                {"finding_id": "%s", "verdict": "fixed", "evidence": "commit abc removes the leak"},
                {"finding_id": "ghost", "verdict": "fixed", "evidence": "e"}
              ], "findings": []}"""
              .formatted(ReviewScripts.carriedId(pr, "Real bug"));
        };
    var ctrl = controller(p -> noHighGate(3), p -> "codex", runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var reviews = reviewStore.reviewsForSpec("auth");
    assertEquals("passed", reviews.get(1).status());
    var firstReviewFindings = reviewStore.findingsForReview(reviews.get(0).id());
    var realBug =
        firstReviewFindings.stream()
            .filter(f -> f.title().equals("Real bug"))
            .findFirst()
            .orElseThrow();
    var nit =
        firstReviewFindings.stream().filter(f -> f.title().equals("Nit")).findFirst().orElseThrow();
    assertEquals(Finding.Resolution.FIXED, realBug.resolution());
    assertEquals("commit abc removes the leak", realBug.resolutionEvidence());
    assertEquals(Finding.Resolution.OPEN, nit.resolution(), "only explicit fixed verdicts resolve");
    var openAfterPass = reviewStore.openFindingsAfterPass("auth");
    assertEquals(
        List.of("Nit"),
        openAfterPass.stream().map(Finding::title).toList(),
        "open-after-pass counts exactly the unresolved sub-gate findings, not history");
    assertEquals(
        nit.id(),
        openAfterPass.getFirst().carriedFrom(),
        "the surviving nit is the same finding aging across iterations, visible as a chain");
  }

  @Test
  void theDisputeNegotiationRetiresAFalsePositiveInTheOpenAndPassesTheGate() {
    createSpec("auth", "in_progress", List.of("api"));
    var messages = loop.messages;
    var argument = "Worker cap is enforced upstream in Dispatcher.acquire; the finding is wrong";
    var firstOutput =
        """
        ```json
        {"verdicts": [], "findings": [
          {"severity": "HIGH", "category": "CONCURRENCY", "file": "Worker.java",
           "line_start": 4, "line_end": 4, "title": "Worker cap ignored", "description": "d", "confidence": 0.8},
          {"severity": "HIGH", "category": "LOGIC", "file": "Snapshot.java",
           "line_start": 8, "line_end": 8, "title": "Snapshot race", "description": "d", "confidence": 0.9}]}
        ```
        """;
    var reReviewPrompt = new AtomicReference<String>();
    var calls = new AtomicInteger();
    var runner =
        new ScriptedAgent() {
          @Override
          public String run(String p, String a, String pr, String rid, String cred) {
            if (calls.incrementAndGet() == 1) {
              return firstOutput;
            }
            reReviewPrompt.set(pr);
            return """
                {"verdicts": [
                  {"finding_id": "%s", "verdict": "disputed", "evidence": "%s"},
                  {"finding_id": "%s", "verdict": "fixed", "evidence": "commit def serializes the snapshot"}
                ], "findings": []}"""
                .formatted(
                    ReviewScripts.carriedId(pr, "Worker cap ignored"),
                    argument,
                    ReviewScripts.carriedId(pr, "Snapshot race"));
          }

          @Override
          public String runFix(
              String p,
              String a,
              String prompt,
              String rid,
              String cred,
              String branch,
              List<String> repos,
              String model,
              String effort) {
            Acting.system(() -> messages.append("auth", "codex/fix", argument, null));
            return "fixed the race, disputed the cap";
          }
        };

    var ctrl = controller(p -> noHighGate(3), p -> "codex", runner);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var reviews = reviewStore.reviewsForSpec("auth");
    assertEquals("passed", reviews.get(1).status(), "the negotiation converges in one round");
    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());

    var disputed = Acting.system(() -> reviewStore.disputedFindings("auth"));
    assertEquals(List.of("Worker cap ignored"), disputed.stream().map(Finding::title).toList());
    assertEquals(argument, disputed.getFirst().resolutionEvidence());
    assertTrue(
        reReviewPrompt.get().contains(argument),
        "the re-review sees the fix agent's argument — the room rides the prompt");

    var room = messages.list("auth", null, 50);
    assertEquals(3, room.size(), "failed verdict, the dispute argument, passed verdict");
    assertTrue(room.get(0).body().contains("Review failed"), room.get(0).body());
    assertEquals(argument, room.get(1).body());
    var passedBody = room.get(2).body();
    assertTrue(passedBody.contains("Review passed"), passedBody);
    assertTrue(
        passedBody.contains("Disputed"),
        "disputed findings are listed in the pass verdict for the human to confirm: " + passedBody);
    assertTrue(passedBody.contains("Worker cap ignored"), passedBody);
    assertTrue(
        passedBody.contains(argument),
        "the ruled argument travels with the verdict so the human sees both sides");
    assertTrue(
        reviewStore.openFindingsAfterPass("auth").isEmpty(),
        "a false positive retired by argument leaves nothing open — without a human dismiss");
  }
}
