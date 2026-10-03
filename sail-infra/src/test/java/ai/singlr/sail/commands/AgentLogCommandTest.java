/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.singlr.sail.store.RunStore;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentLogCommandTest {

  @TempDir Path tempDir;

  private static final String RUN = "0195e0a0-1111-7abc-8def-0123456789ab";

  private static RunStore.RunRow run(String role) {
    return new RunStore.RunRow(
        RUN,
        "acme",
        "auth",
        "node-a",
        role,
        "claude-code",
        "feat/x",
        "do it",
        1,
        null,
        "running",
        null,
        "/a/path/sync/could/have/written",
        null,
        "t0",
        null,
        java.util.List.of(),
        null,
        null,
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
  void aRunsLogIsItsOwnRunScopedFileWhicheverLaneItRanIn() {
    for (var role : java.util.List.of("build", "review", "fix", "room")) {
      assertEquals(
          "/home/dev/.sail/runs/" + RUN + "/agent.log",
          AgentLogCommand.logPathFrom(Optional.of(run(role))),
          "a " + role + " run's log is derived from its id: one file shape for every lane");
    }
  }

  @Test
  void noRunMeansNoLog() {
    assertNull(
        AgentLogCommand.logPathFrom(Optional.empty()),
        "every agent session is a run, so a lane with no run has no log to show");
  }
}
