/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.engine.ContainerFilePush;
import ai.singlr.sail.engine.FindingParser;
import ai.singlr.sail.engine.ReviewPromptBuilder;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The review loop against a <strong>real incus container</strong> with a fake agent binary standing
 * in for claude/codex — no LLM, no API key. Validates the container-exec path the in-process tests
 * cannot reach: {@code incus file push} staging the prompt with dev ownership, the {@link
 * ai.singlr.sail.engine.AgentCli} command actually running in the container, {@code $(cat ...)}
 * reading the prompt, the binary being invoked on PATH, and stdout captured for {@link
 * FindingParser}. Runs only under the {@code integration} profile (maven-failsafe) against a real
 * incus daemon; skips elsewhere via {@link #ensureIncusOrSkip}.
 */
class ReviewAgentLoopIT extends AbstractIncusIT {

  private static final String CONTAINER = "sail-it-review-loop";

  /**
   * A stand-in for the agent CLI binary. A review prompt yields scripted findings — a critical
   * issue the first time, clean once the fix has supposedly landed (tracked by a counter file) —
   * and any other prompt (a fix task) is acknowledged. Deterministic, offline, no model.
   */
  private static final String FAKE_AGENT =
      """
      #!/usr/bin/env bash
      case "$*" in
        *"Output your findings"*)
          n_file="$HOME/.sail/review-count"
          n=$(( $(cat "$n_file" 2>/dev/null || echo 0) + 1 ))
          printf '%s' "$n" > "$n_file"
          if [ "$n" -eq 1 ]; then
            printf '```json\\n[{"severity":"CRITICAL","category":"SECURITY","file":"a.java","line_start":1,"line_end":1,"title":"Bad","description":"Very bad","evidence":"trace","confidence":0.95}]\\n```\\n'
          else
            printf '[]\\n'
          fi
          ;;
        *)
          printf 'fix applied\\n'
          ;;
      esac
      """;

  @BeforeEach
  void provision() throws Exception {
    ensureIncusOrSkip();
    launch(CONTAINER);
    var setup =
        exec(
            CONTAINER,
            List.of(
                "bash",
                "-c",
                "userdel -r ubuntu 2>/dev/null || true;"
                    + " id -u dev >/dev/null 2>&1 || useradd -m -u 1000 -s /bin/bash dev;"
                    + " mkdir -p /home/dev/.sail /home/dev/workspace;"
                    + " chown -R dev:dev /home/dev"));
    assertTrue(setup.ok(), "container provisioning failed: " + setup.stderr());
    ContainerFilePush.push(
        shell, CONTAINER, "/usr/local/bin/codex", FAKE_AGENT, List.of("--mode", "0755"));
  }

  @AfterEach
  void cleanup() {
    deleteContainerQuietly(CONTAINER);
  }

  @Test
  void theRealRunnerInvokesTheAgentInTheContainerAndParsesItsFindings() throws Exception {
    var prompt = ReviewPromptBuilder.build("feat/test", CONTAINER, List.of("security"));

    var output = new ContainerReviewAgentRunner(shell).run(CONTAINER, "codex", prompt);

    var parsed = FindingParser.parse(output);
    assertEquals(
        1,
        parsed.findings().size(),
        "findings must be parsed from real container output: " + output);
    assertEquals(Finding.Severity.CRITICAL, parsed.findings().getFirst().severity());
  }

  @Test
  void theReviewLoopReachesDoneAgainstARealContainer() throws Exception {
    var stateDir = Files.createTempDirectory("review-loop-it");
    try (var db = Sqlite.open(stateDir.resolve("loop.db"))) {
      new SchemaManager(db).migrate();
      var specStore = new SpecStore(db);
      var reviewStore = new ReviewStore(db);
      specStore.create(
          new SpecStore.SpecRow(
              "auth",
              CONTAINER,
              "T",
              SpecStatus.IN_PROGRESS,
              null,
              "codex",
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
                          "no_critical"))));

      var controller =
          new ReviewPipelineController(
              specStore,
              reviewStore,
              p -> config,
              p -> "codex",
              new ContainerReviewAgentRunner(shell),
              null,
              new DirectExecutorService());

      controller.onEvent(
          Event.of(
              CONTAINER,
              "auth",
              Event.WellKnownTypes.AGENT_SESSION_STOPPED,
              "codex",
              "host",
              Map.of(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER)));

      assertEquals(SpecStatus.DONE, specStore.findById("auth").orElseThrow().status());
      assertEquals(2, reviewStore.reviewsForSpec("auth").size());
    } finally {
      deleteRecursively(stateDir);
    }
  }
}
