/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.AgentCli;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentAttachCommandTest {

  @Test
  void aRecordedSessionResumesExactlyByIdForClaudeCode() {
    var cmd = AgentAttachCommand.buildResumeCommand(AgentCli.CLAUDE_CODE, "abc-123");
    assertEquals(List.of("bash", "-lc", "cd ~/workspace && claude --resume abc-123"), cmd);
  }

  @Test
  void aRecordedSessionResumesExactlyByIdForCodex() {
    var cmd = AgentAttachCommand.buildResumeCommand(AgentCli.CODEX, "abc-123");
    assertEquals(
        List.of("bash", "-lc", "cd ~/workspace && codex resume abc-123"),
        cmd,
        "codex resumes by id via 'codex resume <SESSION_ID>' — verified against current docs");
  }

  @Test
  void aNullSessionAttachesFreshNeverAnInteractivePicker() {
    assertEquals(
        List.of("bash", "-lc", "cd ~/workspace && claude"),
        AgentAttachCommand.buildResumeCommand(AgentCli.CLAUDE_CODE, null),
        "no recorded session means a fresh conversation, not '--resume' picker roulette");
    assertEquals(
        List.of("bash", "-lc", "cd ~/workspace && codex"),
        AgentAttachCommand.buildResumeCommand(AgentCli.CODEX, null));
  }

  @Test
  void ordinarySessionIdShapesAreSafe() {
    assertTrue(AgentAttachCommand.isSafeSessionId("0198f00d-1234-7000-8000-abcdefabcdef"));
    assertTrue(AgentAttachCommand.isSafeSessionId("abc-123"));
    assertTrue(AgentAttachCommand.isSafeSessionId("a"));
    assertTrue(AgentAttachCommand.isSafeSessionId("9session.name_x"));
  }

  @Test
  void sessionIdStartingWithDashIsRejectedAsOptionInjection() {
    assertFalse(
        AgentAttachCommand.isSafeSessionId("--dangerously-bypass-approvals-and-sandbox"),
        "a leading '-' would be parsed by the agent CLI as an option, not a session id");
    assertFalse(AgentAttachCommand.isSafeSessionId("-r"));
    assertFalse(AgentAttachCommand.isSafeSessionId(".hidden"));
    assertFalse(AgentAttachCommand.isSafeSessionId("_x"));
  }

  @Test
  void sessionIdWithShellMetacharactersOrOversizeIsRejected() {
    assertFalse(AgentAttachCommand.isSafeSessionId("abc; rm -rf /"));
    assertFalse(AgentAttachCommand.isSafeSessionId("abc$(id)"));
    assertFalse(AgentAttachCommand.isSafeSessionId(""));
    assertFalse(AgentAttachCommand.isSafeSessionId("a".repeat(129)));
    assertTrue(AgentAttachCommand.isSafeSessionId("a".repeat(128)));
  }

  @Test
  void buildIncusExecWithTtyIncludesTtyFlag() {
    var cmd =
        AgentAttachCommand.buildIncusExecWithTty(
            "myproject", List.of("bash", "-lc", "claude --resume abc"));
    assertTrue(cmd.contains("-t"));
    assertTrue(cmd.contains("myproject"));
    assertTrue(cmd.contains("--user"));
    assertTrue(cmd.contains("1000"));
    assertEquals("claude --resume abc", cmd.getLast());
  }

  @Test
  void buildIncusExecSetsHomeEnv() {
    var cmd = AgentAttachCommand.buildIncusExecWithTty("proj", List.of("echo", "test"));
    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("HOME=/home/dev"));
  }
}
