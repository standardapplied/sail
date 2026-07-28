/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentLogCommandTest {

  @TempDir Path tempDir;

  private static RunStore.RunRow run(String logPath) {
    return new RunStore.RunRow(
        "r1",
        "acme",
        "auth",
        "node-a",
        "build",
        "claude-code",
        "feat/x",
        "do it",
        1,
        null,
        "running",
        null,
        logPath,
        null,
        "t0",
        null,
        java.util.List.of(),
        null);
  }

  private static final String STREAM_EVENT =
      "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Reading.\"}]}}";

  @Test
  void jsonOutputStreamsTheRawStructuredLineForMachineConsumers() {
    assertEquals(
        STREAM_EVENT,
        AgentLogCommand.renderForLog(STREAM_EVENT, true),
        "--json (incl. --follow --json) must stream raw NDJSON events, not human-rendered text");
  }

  @Test
  void humanOutputRendersStreamJsonToReadableText() {
    assertEquals("Reading.", AgentLogCommand.renderForLog(STREAM_EVENT, false));
  }

  @Test
  void buildLogFollowsTheLatestRunsRunScopedLog() {
    assertEquals(
        "/home/dev/.sail/runs/r1/agent.log",
        AgentLogCommand.logPathFrom(Optional.of(run("/home/dev/.sail/runs/r1/agent.log"))),
        "a dispatched run's log lives under its own run dir, not the shared agent.log");
  }

  @Test
  void noRunRowMeansNoBuildLog() {
    assertNull(
        AgentLogCommand.logPathFrom(Optional.empty()),
        "every session is a run, so without a run there is no log to fall back to");
  }

  @Test
  void aRunWithoutARecordedLogPathMeansNoBuildLog() {
    assertNull(AgentLogCommand.logPathFrom(Optional.of(run(""))));
  }

  @Test
  void reviewLogFollowsTheLatestReviewsOwnLog() {
    try (var db = Sqlite.open(tempDir.resolve("log.db"))) {
      new SchemaManager(db).migrate();
      var specs = new SpecStore(db);
      specs.create(
          new SpecStore.SpecRow(
              "auth",
              "acme",
              "Add auth",
              ai.singlr.sail.config.SpecStatus.REVIEW,
              null,
              null,
              null,
              null,
              null,
              0,
              null,
              "",
              "",
              null,
              java.util.List.of(),
              java.util.List.of()));
      var reviews = new ReviewStore(db);
      var reviewId = reviews.createReview("auth", 1);

      assertEquals(
          "/home/dev/.sail/runs/" + reviewId + "/review.log",
          AgentLogCommand.reviewLogPathFrom(Optional.of(run("x")), reviews),
          "--review follows the live review's per-review log, where the negotiation actually"
              + " lands");
      assertEquals(
          "/home/dev/.sail/review.log",
          AgentLogCommand.reviewLogPathFrom(Optional.empty(), reviews),
          "no run resolves to the fixed legacy path");
    }
  }
}
