/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the review loop through the <strong>real</strong> {@link ContainerReviewAgentRunner} —
 * its {@link ai.singlr.sail.engine.AgentCli} command construction, prompt staging, and {@link
 * ai.singlr.sail.engine.FindingParser} parsing — against a fake container shell that impersonates a
 * coding-agent CLI: it captures each pushed prompt and, on the following agent invocation, returns
 * scripted output (review findings or a fix acknowledgement). No container, no LLM, no API key —
 * just a deterministic stand-in for the agent binary, so the whole code→review→fix→re-review path
 * runs in normal CI. {@link ReviewLoopIntegrationTest} fakes the runner; this fakes only the
 * binary.
 */
class ReviewAgentLoopTest {

  private static final String CRITICAL_FINDING =
      """
      ```json
      [{"severity": "CRITICAL", "category": "SECURITY", "file": "a.java",
        "line_start": 1, "line_end": 1, "title": "Bad",
        "description": "Very bad", "confidence": 0.95}]
      ```
      """;

  @TempDir Path tempDir;
  private Sqlite db;
  private SpecStore specStore;
  private ReviewStore reviewStore;
  private EventBus bus;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("agentloop.db"));
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
            "T",
            SpecStatus.IN_PROGRESS,
            null,
            "claude-code",
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

  private ReviewPipelineConfig config(int maxIterations) {
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
                    "no_critical"))));
  }

  private void subscribe(ReviewPipelineConfig config, ShellExec agentShell, CountDownLatch latch) {
    var controller =
        new ReviewPipelineController(
            specStore,
            reviewStore,
            p -> config,
            p -> "codex",
            new ContainerReviewAgentRunner(agentShell),
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

  @Test
  void aRealRunnerReviewFixReReviewLoopReachesDone() throws Exception {
    createSpec("auth");
    var latch = new CountDownLatch(1);
    subscribe(config(3), new FakeAgentShell(List.of(CRITICAL_FINDING)), latch);

    bus.publish(stop("auth"));

    BusTesting.awaitDelivery(latch);
    assertEquals(SpecStatus.DONE, specStore.findById("auth").orElseThrow().status());
    assertEquals(2, reviewStore.reviewsForSpec("auth").size());
  }

  @Test
  void aRealRunnerLoopEscalatesWhenTheFixNeverLands() throws Exception {
    createSpec("auth");
    var latch = new CountDownLatch(1);
    subscribe(config(2), new FakeAgentShell(List.of(CRITICAL_FINDING, CRITICAL_FINDING)), latch);

    bus.publish(stop("auth"));

    BusTesting.awaitDelivery(latch);
    assertEquals("escalated", reviewStore.latestReviewForSpec("auth").orElseThrow().status());
    assertEquals(SpecStatus.REVIEW, specStore.findById("auth").orElseThrow().status());
  }

  /**
   * Stands in for the agent CLI running under the review unit. The runner writes the prompt with
   * {@code printf} (captured here), reports the unit inactive so the await loop exits at once, and
   * reads the agent's output from {@code review.log} — a review prompt yields the next scripted
   * findings, anything else (a fix prompt) is acknowledged.
   */
  private static final class FakeAgentShell implements ShellExec {
    private final List<String> reviewOutputs;
    private int reviewCall;
    private String lastPrompt = "";

    FakeAgentShell(List<String> reviewOutputs) {
      this.reviewOutputs = reviewOutputs;
    }

    @Override
    public Result exec(List<String> command) {
      return exec(command, null, null);
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) {
      var joined = String.join(" ", command);
      if (command.getLast().endsWith("review-prompt.txt")) {
        lastPrompt = command.get(command.size() - 2);
        return new Result(0, "", "");
      }
      if (joined.contains("tail -c")) {
        if (lastPrompt.contains("Output your findings")) {
          var i = reviewCall++;
          return new Result(0, i < reviewOutputs.size() ? reviewOutputs.get(i) : "[]", "");
        }
        return new Result(0, "fix applied", "");
      }
      return new Result(0, "", "");
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
