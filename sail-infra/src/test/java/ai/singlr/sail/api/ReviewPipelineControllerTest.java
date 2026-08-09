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

import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SpecStatus;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    createSpec(id, status, List.of());
  }

  private void createSpec(String id, String status, List<String> repos) {
    createSpec(id, status, repos, null, null);
  }

  private void createSpec(
      String id, String status, List<String> repos, String model, String reasoningEffort) {
    specStore.create(
        new SpecStore.SpecRow(
            id,
            "test-project",
            "Test spec",
            SpecStatus.fromWire(status),
            null,
            null,
            model,
            reasoningEffort,
            "feat/test",
            0,
            null,
            "",
            "",
            null,
            List.of(),
            repos));
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
        specStore,
        reviewStore,
        config,
        reviewer,
        runner,
        bus,
        () -> {},
        new DirectExecutorService());
  }

  @Test
  void advancingASpecTriggersSyncSoTheTransitionReachesMain() {
    createSpec("auth", "in_progress");
    var syncs = new java.util.concurrent.atomic.AtomicInteger();
    var ctrl =
        new ReviewPipelineController(
            specStore,
            reviewStore,
            p -> singleAgentStage("no_critical"),
            p -> "codex",
            (p, a, pr, rid, cred) -> CLEAN_REVIEW,
            null,
            syncs::incrementAndGet,
            new DirectExecutorService());

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
    assertTrue(syncs.get() > 0, "a spec status transition must trigger sync-on-write to main");
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

    assertEquals(ctrl, ctrl.useMessages(new MessageStore(db)));
  }

  @Test
  void aReviewAlreadyRunningIsNotRestartedWhenStatusIsReview() {
    createSpec("auth", "review");
    var reviewId = reviewStore.createReview("auth", 1);
    reviewStore.updateReviewStatus(reviewId, "running");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(1, reviewStore.reviewsForSpec("auth").size());
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
    ReviewAgentRunner capturing =
        (p, a, prompt, rid, cred) -> {
          capturedAgent.set(a);
          return CLEAN_REVIEW;
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
        controller(
            p -> singleStageNoAgent("no_critical"),
            p -> null,
            (p, a, pr, rid, cred) -> CLEAN_REVIEW,
            null);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("failed", reviewStore.stagesForReview(review.id()).getFirst().status());
  }

  @Test
  void reusesOneExecutorAcrossEventsAndShutsItDownOnClose() {
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);
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
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);
    var event = agentStoppedEvent("auth");
    assertTrue(ctrl.filter().test(event));
  }

  @Test
  void filterRejectsEventsWithoutSpec() {
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);
    var event = Event.of("proj", null, Event.WellKnownTypes.AGENT_SESSION_STOPPED, "sail", "h");
    assertFalse(ctrl.filter().test(event));
  }

  @Test
  void filterRejectsUnrelatedEventTypes() {
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);
    var event = Event.of("proj", "spec", "spec_dispatched", "sail", "h");
    assertFalse(ctrl.filter().test(event));
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
              specStore.updateStatus("auth", SpecStatus.CANCELLED);
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
  void aReconcilerReplayLandingMidPipelineIsShedByTheStatusGuard() {
    createSpec("auth", "in_progress");
    var ctrl = new AtomicReference<ReviewPipelineController>();
    ctrl.set(
        controller(
            singleAgentStage("no_critical"),
            (p, a, pr, rid, cred) -> {
              ctrl.get().onEvent(reconcilerStoppedEvent("auth"));
              return CLEAN_REVIEW;
            }));

    ctrl.get().onEvent(agentStoppedEvent("auth"));

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
    assertEquals("Quota exceeded", review.error(), "why it failed is durable, not journal-only");
    var stage = reviewStore.stagesForReview(review.id()).getFirst();
    assertEquals("Quota exceeded", stage.error());
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

    specStore.updateStatus("auth", SpecStatus.IN_PROGRESS);
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
    var exhausted = reviewStore.createReview("auth", 3);
    reviewStore.updateReviewStatus(exhausted, "escalated");
    reviewStore.supersedeForSpec("auth");
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
    var interrupted = reviewStore.createReview("auth", 1);
    reviewStore.updateReviewStatus(interrupted, "running");
    reviewStore.supersedeForSpec("auth");
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
        controller(
            p -> twoAgentStages(), p -> "codex", (p, a, pr, rid, cred) -> CLEAN_REVIEW, null);

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
                        "claude",
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
    ReviewAgentRunner runner =
        (p, agent, prompt, rid, cred) -> {
          var prompts = promptsByAgent.computeIfAbsent(agent, k -> new ArrayList<>());
          prompts.add(prompt);
          if (agent.equals("claude")) {
            return prompts.size() == 1 ? highOutput : fixAllCarried(prompt);
          }
          return agent.equals("codex") ? CLEAN_REVIEW : "fix applied";
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
    var claudePrompts = promptsByAgent.get("claude");
    assertEquals(2, claudePrompts.size());
    assertTrue(
        claudePrompts.get(1).contains("Persistent high"),
        "the finding returns to the stage that emitted it");
  }

  @Test
  void aHumanStageOpensWithTheRoomVerdictListingDisputedFindings() {
    createSpec("auth", "in_progress");
    var messages = new MessageStore(db);
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
    ReviewAgentRunner runner =
        (p, a, prompt, rid, cred) ->
            switch (calls.incrementAndGet()) {
              case 1 -> highOutput;
              case 2 -> "fix applied";
              default -> ReviewScripts.disputeAllCarried(prompt);
            };
    var ctrl = controller(config, runner);
    ctrl.useMessages(messages);

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
    var messages = new MessageStore(db);
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
    ReviewAgentRunner runner =
        (p, a, prompt, rid, cred) ->
            switch (calls.incrementAndGet()) {
              case 1 -> "{\"verdicts\": [], \"findings\": [" + elevenHighs + "]}";
              case 2 -> "fix applied";
              default -> ReviewScripts.disputeAllCarried(prompt);
            };
    var ctrl = controller(config, runner);
    ctrl.useMessages(messages);

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
        controller(
            p -> agentThenHuman(), p -> "codex", (p, a, pr, rid, cred) -> CLEAN_REVIEW, null);

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
    var ctrl = controller(p -> null, p -> "codex", (p, a, pr, rid, cred) -> CLEAN_REVIEW, null);

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
    ReviewAgentRunner failing =
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
    assertEquals("review-pipeline", ctrl.name());
  }

  @Test
  void reviewPromptIncludesCategories() {
    createSpec("auth", "in_progress");
    var capturedPrompt = new AtomicReference<String>();
    ReviewAgentRunner capturing =
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
    ReviewAgentRunner runner =
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
    var reviewIds = new java.util.ArrayList<String>();
    ReviewAgentRunner runner =
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
    var ctrl = controller(p -> config, p -> "codex", (p, a, pr, rid, cred) -> criticalOutput, null);

    ctrl.onEvent(agentStoppedEvent("auth"));

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("escalated", review.status());
  }

  @Test
  void erroredRetriesAreBoundedSoARescueLoopCanNeverBurnAgentsForever() throws Exception {
    createSpec("auth", "in_progress");

    try (var bus = new EventBus()) {
      var captured = captureEvents(bus, Set.of("review_escalated"), 1);
      var ctrl =
          controller(
              p -> singleAgentStage("no_critical"),
              p -> "codex",
              (p, a, pr, rid, cred) -> "prose with no fenced block",
              bus);

      ctrl.onEvent(agentStoppedEvent("auth"));
      ctrl.onEvent(reconcilerStoppedEvent("auth"));
      ctrl.onEvent(reconcilerStoppedEvent("auth"));

      var reviews = reviewStore.reviewsForSpec("auth");
      assertEquals(3, reviews.size());
      assertTrue(
          reviews.stream().allMatch(r -> r.errored() && r.iteration() == 1),
          "errored attempts retry the same iteration");

      ctrl.onEvent(reconcilerStoppedEvent("auth"));
      BusTesting.awaitDelivery(captured.latch());

      assertEquals(
          3,
          reviewStore.reviewsForSpec("auth").size(),
          "the fourth stop must escalate instead of starting a fourth doomed review");
      assertEquals("escalated", reviewStore.latestReviewForSpec("auth").orElseThrow().status());
      var detail =
          java.util.Objects.toString(captured.events().getFirst().data().get("detail"), "");
      assertTrue(
          detail.contains("errored"),
          "escalation must say WHY — an error budget, not exhausted iterations: " + detail);
    }
  }

  @Test
  void anUnparseableReviewNarratesAsErroredNotAsAFailedGate() throws Exception {
    createSpec("auth", "in_progress");

    try (var bus = new EventBus()) {
      var captured = captureEvents(bus, Set.of("review_errored", "review_stage_failed"), 1);
      var ctrl =
          controller(
              p -> singleAgentStage("no_critical"),
              p -> "codex",
              (p, a, pr, rid, cred) -> "no fenced block here",
              bus);

      ctrl.onEvent(agentStoppedEvent("auth"));
      BusTesting.awaitDelivery(captured.latch());

      assertEquals(
          List.of("review_errored"),
          captured.events().stream().map(Event::type).toList(),
          "a parse failure is an infrastructure error, not a gate verdict — one message, not a"
              + " misleading 'stage failed (no findings)' followed by 'errored'");
    }
  }

  @Test
  void aFixIterationCommitsWorkTheAgentLeftUncommitted() throws Exception {
    createSpec("auth", "in_progress", List.of("api"));
    var criticalOutput =
        """
        ```json
        {"verdicts": [], "findings": [{"severity": "CRITICAL", "category": "SECURITY", "file": "a.java",
          "line_start": 1, "line_end": 1, "title": "Bad",
          "description": "Very bad", "confidence": 0.9}]}
        ```
        """;
    var ensured = new AtomicReference<List<Object>>();
    var calls = new AtomicInteger();
    var runner =
        new ReviewAgentRunner() {
          @Override
          public String run(String p, String a, String prompt, String rid, String cred) {
            return calls.incrementAndGet() == 1 ? criticalOutput : fixAllCarried(prompt);
          }

          @Override
          public List<Rescue> ensureCommitted(
              String project, List<String> repos, String branch, String commitMessage) {
            ensured.set(List.of(project, repos, branch, commitMessage));
            return List.of(
                new Rescue("api", List.of("Api.java", "ApiTest.java")),
                new Rescue("web", List.of("App.tsx")));
          }
        };

    try (var bus = new EventBus()) {
      var captured = captureEvents(bus, Set.of(Event.WellKnownTypes.GUARDRAIL_TRIGGERED), 1);
      var ctrl = controller(p -> singleAgentStage("no_critical"), p -> "codex", runner, bus);

      ctrl.onEvent(agentStoppedEvent("auth"));
      BusTesting.awaitDelivery(captured.latch());

      assertEquals(
          List.of("test-project", List.of("api"), "feat/test"),
          ensured.get().subList(0, 3),
          "after the fix agent runs, its work is verified committed on the spec branch");
      assertTrue(
          ensured.get().get(3).toString().contains("Bad"),
          "the rescue commit message names the findings the fix addressed, so the PR history"
              + " explains itself instead of reading 'left uncommitted by the agent'");
      var reason =
          java.util.Objects.toString(captured.events().getFirst().data().get("reason"), "");
      assertTrue(reason.contains("api"), "the guardrail names the contaminated repo");
      assertTrue(
          reason.contains("Api.java"),
          "the guardrail names the files it swept, so debris is visible the moment it happens");
      assertTrue(
          reason.contains("web (1 file: App.tsx)"),
          "every rescued repo is named, joined into one readable line");
    }
  }

  @Test
  void theFixLaneCarriesTheSpecBranchReposAndTuningIntoTheGate() throws Exception {
    createSpec("auth", "in_progress", List.of("api"), "opus-5", "xhigh");
    var criticalOutput =
        """
        ```json
        {"verdicts": [], "findings": [{"severity": "CRITICAL", "category": "SECURITY", "file": "a.java",
          "line_start": 1, "line_end": 1, "title": "Bad",
          "description": "Very bad", "confidence": 0.9}]}
        ```
        """;
    var fixLaunch = new AtomicReference<List<Object>>();
    var reviewEffort = new AtomicReference<String>();
    var calls = new AtomicInteger();
    var runner =
        new ReviewAgentRunner() {
          @Override
          public String run(String p, String a, String prompt, String rid, String cred) {
            return calls.incrementAndGet() == 1 ? criticalOutput : fixAllCarried(prompt);
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
            reviewEffort.set(effort);
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

    try (var bus = new EventBus()) {
      var captured = captureEvents(bus, Set.of("review_completed"), 1);
      var ctrl = controller(p -> singleAgentStage("no_critical"), p -> "codex", runner, bus);

      ctrl.onEvent(agentStoppedEvent("auth"));
      BusTesting.awaitDelivery(captured.latch());

      assertEquals(
          List.of("claude-code", "feat/test", List.of("api"), "opus-5", "xhigh"),
          fixLaunch.get(),
          "the fix agent launches as the spec's own agent with the spec's branch, repo scope,"
              + " model, and reasoning effort — a spec dispatched at xhigh is fixed at xhigh");
      assertEquals(
          "xhigh",
          reviewEffort.get(),
          "the reviewer judges at the spec's effort; the model stays out of the review lane"
              + " because model names are agent-specific and the reviewer is the other agent");
    }
  }

  @Test
  void theLoopNarratesVerdictsIntoTheSpecRoom() throws Exception {
    createSpec("auth", "in_progress", List.of("api"));
    var messages = new MessageStore(db);
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
    ReviewAgentRunner runner =
        (p, a, pr, rid, cred) ->
            calls.incrementAndGet() == 1
                ? failedOutput
                : ReviewScripts.fixAllCarried(pr, lowFinding);

    try (var bus = new EventBus()) {
      var captured = captureEvents(bus, Set.of("review_completed"), 1);
      var ctrl = controller(p -> singleAgentStage("no_critical"), p -> "codex", runner, bus);
      ctrl.useMessages(messages);

      ctrl.onEvent(agentStoppedEvent("auth"));
      BusTesting.awaitDelivery(captured.latch());

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
  }

  @Test
  void aRoomWriteFailureNeverFailsThePipeline() {
    createSpec("auth", "in_progress");
    var brokenDb = Sqlite.open(tempDir.resolve("broken.db"));
    new SchemaManager(brokenDb).migrate();
    var brokenMessages = new MessageStore(brokenDb);
    brokenDb.close();

    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);
    ctrl.useMessages(brokenMessages);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(
        SpecStatus.AWAITING_MERGE,
        specStore.findById("auth").orElseThrow().status(),
        "narration is best-effort: a dead room store must not strand the verdict");
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
          controller(
              p -> singleAgentStage("no_critical"),
              p -> "codex",
              (p, a, pr, rid, cred) -> CLEAN_REVIEW,
              bus);

      ctrl.executePipeline(reviewId, singleAgentStage("no_critical"), "test-project", "auth");

      assertTrue(bus.publishedCount() > 0);
    }
  }

  @Test
  void stageEventsCarryFindingCountsBySeverity() throws Exception {
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

    try (var bus = new EventBus()) {
      var captured = captureStagePassedEvents(bus, 1);
      var ctrl =
          controller(
              p -> singleAgentStage("no_critical"),
              p -> "codex",
              (p, a, pr, rid, cred) -> agentOutput,
              bus);

      ctrl.onEvent(agentStoppedEvent("auth"));
      BusTesting.awaitDelivery(captured.latch());

      var event = captured.events().getFirst();
      assertEquals("security", event.data().get("detail"));
      assertEquals(Map.of("high", 2, "low", 1), event.data().get("findings"));
    }
  }

  @Test
  void cleanStageEventOmitsFindingCounts() throws Exception {
    createSpec("auth", "in_progress");

    try (var bus = new EventBus()) {
      var captured = captureStagePassedEvents(bus, 1);
      var ctrl =
          controller(
              p -> singleAgentStage("no_critical"),
              p -> "codex",
              (p, a, pr, rid, cred) -> CLEAN_REVIEW,
              bus);

      ctrl.onEvent(agentStoppedEvent("auth"));
      BusTesting.awaitDelivery(captured.latch());

      assertFalse(captured.events().getFirst().data().containsKey("findings"));
    }
  }

  private record Captured(List<Event> events, CountDownLatch latch) {}

  private static Captured captureStagePassedEvents(EventBus bus, int expected) {
    return captureEvents(bus, Set.of("review_stage_passed"), expected);
  }

  private static Captured captureEvents(EventBus bus, Set<String> types, int expected) {
    var events = new java.util.concurrent.CopyOnWriteArrayList<Event>();
    var latch = new CountDownLatch(expected);
    bus.subscribe(
        BusTesting.latching(
            new EventSubscriber() {
              @Override
              public String name() {
                return "capture";
              }

              @Override
              public java.util.function.Predicate<Event> filter() {
                return e -> types.contains(e.type());
              }

              @Override
              public void onEvent(Event event) {
                events.add(event);
              }
            },
            latch));
    return new Captured(events, latch);
  }

  @Test
  void aHandlerFailurePublishesALoudPipelineErrorEvent() throws Exception {
    createSpec("auth", "in_progress");
    try (var bus = new EventBus()) {
      var events = new java.util.concurrent.CopyOnWriteArrayList<Event>();
      var latch = new CountDownLatch(1);
      bus.subscribe(
          BusTesting.latching(
              new EventSubscriber() {
                @Override
                public String name() {
                  return "capture";
                }

                @Override
                public java.util.function.Predicate<Event> filter() {
                  return e -> "review_pipeline_error".equals(e.type());
                }

                @Override
                public void onEvent(Event event) {
                  events.add(event);
                }
              },
              latch));
      var ctrl =
          controller(
              p -> singleAgentStage("no_critical"),
              p -> "codex",
              (p, a, pr, rid, cred) -> CLEAN_REVIEW,
              bus);
      db.close();

      ctrl.onEvent(agentStoppedEvent("auth"));
      BusTesting.awaitDelivery(latch);

      assertEquals(1, events.size());
      assertEquals("auth", events.getFirst().spec());
      assertTrue(events.getFirst().data().get("detail").toString().contains("closed"));
    }
  }

  @Test
  void aRunningReviewIsNotRestartedByADuplicateEvent() {
    createSpec("auth", "in_progress");
    var reviewId = reviewStore.createReview("auth", 1);
    reviewStore.updateReviewStatus(reviewId, "running");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertEquals(1, reviewStore.reviewsForSpec("auth").size());
  }

  @Test
  void awaitCompletionBlocksUntilAnInFlightPipelineFinishes() throws Exception {
    createSpec("auth", "in_progress");
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    ReviewAgentRunner gated =
        (p, a, pr, rid, cred) -> {
          started.countDown();
          await(release);
          return CLEAN_REVIEW;
        };
    var ctrl =
        new ReviewPipelineController(
            specStore,
            reviewStore,
            p -> singleAgentStage("no_critical"),
            p -> "codex",
            gated,
            null,
            () -> {});

    ctrl.onEvent(agentStoppedEvent("auth"));
    assertTrue(started.await(5, TimeUnit.SECONDS), "pipeline should reach the agent runner");
    release.countDown();
    ctrl.awaitCompletion(5000);

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("passed", review.status());
  }

  @Test
  void reviewRunIsVisibleWhileTheAgentRunsAndCompletedOnExit() throws Exception {
    createSpec("auth", "in_progress");
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    ReviewAgentRunner gated =
        (p, a, pr, rid, cred) -> {
          started.countDown();
          await(release);
          return CLEAN_REVIEW;
        };
    var runs = new RunStore(db);
    var ctrl =
        new ReviewPipelineController(
            specStore,
            reviewStore,
            p -> singleAgentStage("no_critical"),
            p -> "codex",
            gated,
            null,
            () -> {},
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
            runs,
            () -> "node-a");

    ctrl.onEvent(agentStoppedEvent("auth"));
    assertTrue(started.await(5, TimeUnit.SECONDS), "pipeline should reach the agent runner");

    var running = runs.listForSpec("auth").getFirst();
    assertEquals("review", running.role());
    assertEquals("running", running.status());
    assertEquals("node-a", running.node());
    assertEquals("codex", running.agent());
    assertEquals("feat/test", running.branch());
    assertEquals("/home/dev/.sail/runs/" + running.id() + "/review.log", running.logPath());

    release.countDown();
    ctrl.awaitCompletion(5000);

    var completed = runs.findById(running.id()).orElseThrow();
    assertEquals("completed", completed.status());
    assertEquals(0, completed.exitCode());
    assertNotNull(completed.completedAt());
    ctrl.close();
  }

  @Test
  void reviewRunRecordsTheAgentExitCodeOnFailure() {
    createSpec("auth", "in_progress");
    var runs = new RunStore(db);
    var ctrl =
        new ReviewPipelineController(
            specStore,
            reviewStore,
            p -> singleAgentStage("no_critical"),
            p -> "codex",
            (p, a, pr, rid, cred) -> {
              throw new ReviewAgentExecutionException("quota", 17);
            },
            null,
            () -> {},
            new DirectExecutorService(),
            runs,
            () -> "node-a");

    ctrl.onEvent(agentStoppedEvent("auth"));

    var failed = runs.listForSpec("auth").getFirst();
    assertEquals("failed", failed.status());
    assertEquals(17, failed.exitCode());
    assertNotNull(failed.completedAt());
  }

  @Test
  void reviewerAndFixAgentsEachReceiveALiveCredentialForTheirReviewRun() {
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
    var runs = new RunStore(db);
    var calls = new AtomicInteger();
    var credentials = new java.util.concurrent.CopyOnWriteArrayList<List<String>>();
    ReviewAgentRunner runner =
        (p, a, pr, rid, cred) -> {
          credentials.add(List.of(rid, cred));
          return calls.incrementAndGet() == 1 ? criticalOutput : fixAllCarried(pr);
        };
    var ctrl =
        new ReviewPipelineController(
            specStore,
            reviewStore,
            p -> singleAgentStage("no_critical"),
            p -> "codex",
            runner,
            null,
            () -> {},
            new DirectExecutorService(),
            runs,
            () -> "node-a");

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertTrue(credentials.size() >= 2, "reviewer and fix agent both ran");
    for (var invocation : credentials) {
      var reviewId = invocation.get(0);
      var credential = invocation.get(1);
      assertFalse(credential.isBlank(), "every review invocation carries a credential");
      var resolvedAtCallTime = runs.findById(reviewId).orElseThrow();
      assertEquals(
          "codex/review-" + reviewId,
          resolvedAtCallTime.principal(),
          "the credential's run records the review principal the agent acts as");
    }
    var firstReview = credentials.getFirst();
    var reviewerRun = runs.findById(firstReview.get(0)).orElseThrow();
    assertEquals("review", reviewerRun.role());
  }

  @Test
  void aFixLaneCredentialResolvesToTheStillRunningReviewRun() throws Exception {
    createSpec("auth", "in_progress");
    var criticalOutput =
        """
        ```json
        {"verdicts": [], "findings": [{"severity": "CRITICAL", "category": "SECURITY", "file": "a.java",
          "line_start": 1, "line_end": 1, "title": "Bad",
          "description": "Very bad", "confidence": 0.9}]}
        ```
        """;
    var runs = new RunStore(db);
    var calls = new AtomicInteger();
    var fixResolved = new AtomicReference<RunStore.RunRow>();
    ReviewAgentRunner runner =
        (p, a, pr, rid, cred) -> {
          if (calls.incrementAndGet() == 2) {
            fixResolved.set(runs.findByCredential(cred).orElse(null));
          }
          return calls.get() == 1 ? criticalOutput : fixAllCarried(pr);
        };
    var ctrl =
        new ReviewPipelineController(
            specStore,
            reviewStore,
            p -> singleAgentStage("no_critical"),
            p -> "codex",
            runner,
            null,
            () -> {},
            new DirectExecutorService(),
            runs,
            () -> "node-a");

    ctrl.onEvent(agentStoppedEvent("auth"));

    assertNotNull(fixResolved.get(), "the fix agent's re-issued credential resolves to a run");
    assertEquals("review", fixResolved.get().role());
    assertEquals(
        "running",
        fixResolved.get().status(),
        "the fix lane rejoins the still-open review negotiation");
  }

  @Test
  void closeAwaitsInFlightPipelines() {
    createSpec("auth", "in_progress");
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);

    ctrl.onEvent(agentStoppedEvent("auth"));
    ctrl.close();

    var review = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals("passed", review.status());
  }

  @Test
  void awaitCompletionWithNoInFlightReturnsImmediately() throws Exception {
    var ctrl = controller(singleAgentStage("no_critical"), (p, a, pr, rid, cred) -> CLEAN_REVIEW);
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
            (p, a, pr, rid, cred) -> CLEAN_REVIEW,
            null,
            () -> {},
            new DirectExecutorService());
    errDb.close();

    assertDoesNotThrow(() -> ctrl.onEvent(agentStoppedEvent("auth")));
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
    try (var bus = new EventBus()) {
      var ctrl =
          controller(
              p -> singleAgentStage("no_critical"),
              p -> "codex",
              (p, a, pr, rid, cred) -> CLEAN_REVIEW,
              bus);

      ctrl.onEvent(agentStoppedEvent("auth", 1));

      assertTrue(bus.publishedCount() > 0);
    }
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
    var reviewId = reviewStore.createReview("auth", 3);
    reviewStore.updateReviewStatus(reviewId, "failed");
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
    ReviewAgentRunner runner =
        (p, a, pr, rid, cred) -> {
          if (calls.incrementAndGet() == 1) return criticalOutput;
          throw new RuntimeException("fix agent crashed");
        };
    var ctrl = controller(singleAgentStage("no_critical"), runner);

    assertDoesNotThrow(() -> ctrl.onEvent(agentStoppedEvent("auth")));
    assertTrue(calls.get() >= 2, "the review ran and a fix was attempted");
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
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
    ReviewAgentRunner runner =
        (p, a, pr, rid, cred) -> calls.incrementAndGet() == 1 ? HIGH_FINDING : CLEAN_REVIEW;
    var ctrl = controller(p -> noHighGate(2), p -> "codex", runner, null);

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
  void aGateBlockingFindingStillOpenTwiceEscalatesWithTheFindingNamed() throws Exception {
    createSpec("auth", "in_progress");
    var calls = new AtomicInteger();
    ReviewAgentRunner runner =
        (p, a, pr, rid, cred) -> calls.incrementAndGet() == 1 ? HIGH_FINDING : CLEAN_REVIEW;

    try (var bus = new EventBus()) {
      var captured = captureEvents(bus, Set.of("review_escalated"), 1);
      var ctrl = controller(p -> noHighGate(5), p -> "codex", runner, bus);

      ctrl.onEvent(agentStoppedEvent("auth"));
      BusTesting.awaitDelivery(captured.latch());

      assertEquals(
          3,
          reviewStore.reviewsForSpec("auth").size(),
          "the stuck loop is caught after 2 fix iterations, not after max_iterations=5");
      var detail =
          java.util.Objects.toString(captured.events().getFirst().data().get("detail"), "");
      assertTrue(detail.contains("Sticky high"), "escalation names the stuck finding: " + detail);
      assertTrue(detail.contains("survived 2 fix iterations"), detail);
    }
  }

  @Test
  void anotherStagesAgedSubGateFindingNeverTripsTheFailingStagesConvergenceCheck()
      throws Exception {
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
                        "claude",
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
    ReviewAgentRunner runner =
        (p, agent, prompt, rid, cred) ->
            switch (agent) {
              case "codex" -> codexCalls.incrementAndGet() == 1 ? toleratedHigh : CLEAN_REVIEW;
              case "claude" ->
                  ReviewScripts.fixAllCarried(
                      prompt,
                      ("[{\"severity\": \"LOW\", \"category\": \"LOGIC\", \"file\": \"b.java\","
                              + " \"line_start\": 1, \"line_end\": 1, \"title\": \"Fresh low %d\","
                              + " \"description\": \"d\", \"confidence\": 0.4}]")
                          .formatted(claudeCalls.incrementAndGet()));
              default -> "fix applied";
            };

    try (var bus = new EventBus()) {
      var captured = captureEvents(bus, Set.of("review_escalated"), 1);
      var ctrl = controller(p -> config, p -> "codex", runner, bus);

      ctrl.onEvent(agentStoppedEvent("auth"));
      BusTesting.awaitDelivery(captured.latch());

      assertEquals(
          3,
          reviewStore.reviewsForSpec("auth").size(),
          "a converging loop — new blocker each round — runs to max_iterations");
      var detail =
          java.util.Objects.toString(captured.events().getFirst().data().get("detail"), "");
      assertTrue(
          detail.contains("review iterations exhausted"),
          "the loop exhausts its budget instead of escalating a foreign stage's finding: "
              + detail);
      assertFalse(
          detail.contains("Tolerated high"),
          "the security stage's aged HIGH passes its own gate; the correctness gate must never"
              + " judge it: "
              + detail);
    }
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
    ReviewAgentRunner runner =
        (p, a, pr, rid, cred) -> calls.incrementAndGet() == 1 ? lowOutput : CLEAN_REVIEW;
    var ctrl = controller(p -> noHighGate(3), p -> "codex", runner, null);

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
    ReviewAgentRunner runner =
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
    var ctrl = controller(p -> noHighGate(3), p -> "codex", runner, null);

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
  void theDisputeNegotiationRetiresAFalsePositiveInTheOpenAndPassesTheGate() throws Exception {
    createSpec("auth", "in_progress", List.of("api"));
    var messages = new MessageStore(db);
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
        new ReviewAgentRunner() {
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
            messages.append("auth", "codex/fix", argument, null);
            return "fixed the race, disputed the cap";
          }
        };

    try (var bus = new EventBus()) {
      var captured = captureEvents(bus, Set.of("review_completed"), 1);
      var ctrl = controller(p -> noHighGate(3), p -> "codex", runner, bus);
      ctrl.useMessages(messages);

      ctrl.onEvent(agentStoppedEvent("auth"));
      BusTesting.awaitDelivery(captured.latch());

      var reviews = reviewStore.reviewsForSpec("auth");
      assertEquals("passed", reviews.get(1).status(), "the negotiation converges in one round");
      assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());

      var disputed = reviewStore.disputedFindings("auth");
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
          "disputed findings are listed in the pass verdict for the human to confirm: "
              + passedBody);
      assertTrue(passedBody.contains("Worker cap ignored"), passedBody);
      assertTrue(
          passedBody.contains(argument),
          "the ruled argument travels with the verdict so the human sees both sides");
      assertTrue(
          reviewStore.openFindingsAfterPass("auth").isEmpty(),
          "a false positive retired by argument leaves nothing open — without a human dismiss");
    }
  }
}
