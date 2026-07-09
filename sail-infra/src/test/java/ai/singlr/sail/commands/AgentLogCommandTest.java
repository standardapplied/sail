/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.store.RunStore;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentLogCommandTest {

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
        "t0",
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
  void reviewFlagSelectsTheReviewLogOtherwiseTheBuildLog() {
    assertEquals(
        "/home/dev/.sail/agent.log",
        AgentLogCommand.logPathFor(false),
        "default follows the coder's build log");
    assertEquals(
        "/home/dev/.sail/review.log",
        AgentLogCommand.logPathFor(true),
        "--review follows the reviewer/fix negotiation log");
  }

  @Test
  void buildLogFollowsTheLatestRunsRunScopedLog() {
    assertEquals(
        "/home/dev/.sail/runs/r1/agent.log",
        AgentLogCommand.logPathFrom(Optional.of(run("/home/dev/.sail/runs/r1/agent.log"))),
        "a dispatched run's log lives under its own run dir, not the shared agent.log");
  }

  @Test
  void buildLogFallsBackToTheSharedLogWhenNoRunExists() {
    assertEquals("/home/dev/.sail/agent.log", AgentLogCommand.logPathFrom(Optional.empty()));
  }

  @Test
  void buildLogFallsBackToTheSharedLogWhenTheRunHasNoLogPath() {
    assertEquals("/home/dev/.sail/agent.log", AgentLogCommand.logPathFrom(Optional.of(run(""))));
  }
}
