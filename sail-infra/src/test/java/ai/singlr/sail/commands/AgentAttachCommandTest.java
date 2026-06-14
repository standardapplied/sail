/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.AgentCli;
import org.junit.jupiter.api.Test;

class AgentAttachCommandTest {

  @Test
  void buildResumeCommandForClaudeCode() {
    var cmd = AgentAttachCommand.buildResumeCommand(AgentCli.CLAUDE_CODE);
    assertEquals(3, cmd.size());
    assertTrue(cmd.get(2).contains("claude --resume"));
  }

  @Test
  void buildResumeCommandForCodex() {
    var cmd = AgentAttachCommand.buildResumeCommand(AgentCli.CODEX);
    assertEquals(3, cmd.size());
    assertTrue(cmd.get(2).contains("codex --last"));
  }

  @Test
  void buildIncusExecWithTtyIncludesTtyFlag() {
    var cmd =
        AgentAttachCommand.buildIncusExecWithTty(
            "myproject", java.util.List.of("bash", "-lc", "claude --resume"));
    assertTrue(cmd.contains("-t"));
    assertTrue(cmd.contains("myproject"));
    assertTrue(cmd.contains("--user"));
    assertTrue(cmd.contains("1000"));
    assertEquals("claude --resume", cmd.getLast());
  }

  @Test
  void buildIncusExecSetsHomeEnv() {
    var cmd = AgentAttachCommand.buildIncusExecWithTty("proj", java.util.List.of("echo", "test"));
    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("HOME=/home/dev"));
  }
}
