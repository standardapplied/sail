/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentCliTest {

  @Test
  void fromYamlNameResolvesClaudeCode() {
    var cli = AgentCli.fromYamlName("claude-code");

    assertEquals("claude-code", cli.yamlName());
    assertEquals("claude", cli.binaryName());
    assertTrue(cli.installCommand().contains("claude.ai/install.sh"));
  }

  @Test
  void fromYamlNameResolvesCodex() {
    var cli = AgentCli.fromYamlName("codex");

    assertEquals("codex", cli.yamlName());
    assertEquals("codex", cli.binaryName());
    assertTrue(
        cli.installCommand().contains("chatgpt.com/codex/install.sh"),
        "Codex installs via the native script, not npm");
    assertTrue(cli.installCommand().contains("CODEX_NON_INTERACTIVE=1"));
  }

  @Test
  void fromYamlNameThrowsOnUnknown() {
    var ex =
        assertThrows(IllegalArgumentException.class, () -> AgentCli.fromYamlName("unknown-agent"));

    assertTrue(ex.getMessage().contains("Unknown agent CLI"));
    assertTrue(ex.getMessage().contains("unknown-agent"));
    assertTrue(ex.getMessage().contains("claude-code, codex"));
  }

  @Test
  void bothAgentsInstallViaNativeScript() {
    assertTrue(AgentCli.CLAUDE_CODE.installCommand().startsWith("curl "));
    assertTrue(AgentCli.CODEX.installCommand().startsWith("curl "));
    assertFalse(AgentCli.CODEX.installCommand().contains("npm"));
  }

  private static final String TASK = "/home/dev/.sail/agent-task.txt";

  @Test
  void headlessCommandClaudeCodeWithPermissions() {
    var cmd = AgentCli.CLAUDE_CODE.headlessCommand(TASK, true);

    assertTrue(cmd.contains("claude --print"));
    assertTrue(cmd.contains("--dangerously-skip-permissions"));
    assertTrue(cmd.contains("-p \"$(cat " + TASK + ")\""));
    assertFalse(cmd.contains("--settings"), "no settings flag when caller passes null path");
  }

  @Test
  void headlessCommandClaudeCodeIncludesSettingsPathWhenProvided() {
    var cmd =
        AgentCli.CLAUDE_CODE.headlessCommand(
            TASK, true, null, null, "/home/dev/.sail/claude-settings.json");

    assertTrue(
        cmd.contains("claude --print --settings /home/dev/.sail/claude-settings.json"),
        "settings flag must appear before permission flag for stable arg ordering");
    assertTrue(cmd.contains("--dangerously-skip-permissions"));
  }

  @Test
  void headlessCommandClaudeCodeBlankSettingsPathOmitsFlag() {
    var cmd = AgentCli.CLAUDE_CODE.headlessCommand(TASK, false, null, null, "");

    assertFalse(cmd.contains("--settings"));
  }

  @Test
  void headlessCommandCodexIgnoresSettingsPath() {
    var cmd =
        AgentCli.CODEX.headlessCommand(
            TASK, true, null, null, "/home/dev/.sail/claude-settings.json");

    assertFalse(cmd.contains("--settings"), "settings flag is Claude-only");
  }

  @Test
  void headlessCommandClaudeCodeStreamingAddsStreamJson() {
    var cmd = AgentCli.CLAUDE_CODE.headlessCommand(TASK, true, null, null, null, true);

    assertTrue(
        cmd.contains("claude --print --output-format stream-json --verbose"),
        "streaming dispatch emits newline-delimited JSON events");
    assertTrue(cmd.contains("-p \"$(cat " + TASK + ")\""));
  }

  @Test
  void headlessCommandClaudeCodeNonStreamingHasNoStreamJson() {
    var cmd = AgentCli.CLAUDE_CODE.headlessCommand(TASK, true, null, null, null, false);

    assertFalse(cmd.contains("stream-json"));
  }

  @Test
  void headlessCommandCodexIgnoresStreamFlag() {
    var streamed = AgentCli.CODEX.headlessCommand(TASK, true, null, null, null, true);
    var plain = AgentCli.CODEX.headlessCommand(TASK, true, null, null, null, false);

    assertEquals(plain, streamed, "Codex streams readable text already; the flag is a no-op");
    assertFalse(streamed.contains("stream-json"));
  }

  @Test
  void headlessCommandClaudeCodeWithoutPermissions() {
    var cmd = AgentCli.CLAUDE_CODE.headlessCommand(TASK, false);

    assertTrue(cmd.contains("claude --print"));
    assertTrue(cmd.contains("-p \"$(cat " + TASK + ")\""));
    assertFalse(cmd.contains("--dangerously-skip-permissions"));
  }

  @Test
  void headlessCommandCodexWithPermissions() {
    var cmd = AgentCli.CODEX.headlessCommand(TASK, true);

    assertTrue(cmd.contains("codex exec"));
    assertTrue(cmd.contains("--dangerously-bypass-approvals-and-sandbox"));
    assertTrue(cmd.contains("\"$(cat " + TASK + ")\""));
    assertFalse(cmd.contains("--print"));
  }

  @Test
  void headlessCommandCodexWithoutPermissions() {
    var cmd = AgentCli.CODEX.headlessCommand(TASK, false);

    assertTrue(cmd.contains("codex exec"));
    assertFalse(cmd.contains("--full-auto"));
  }

  @Test
  void interactiveCommandClaudeCodeWithPermissions() {
    assertEquals(
        "claude --dangerously-skip-permissions", AgentCli.CLAUDE_CODE.interactiveCommand(true));
  }

  @Test
  void interactiveCommandClaudeCodeWithoutPermissions() {
    assertEquals("claude", AgentCli.CLAUDE_CODE.interactiveCommand(false));
  }

  @Test
  void interactiveCommandCodexWithPermissions() {
    assertEquals(
        "codex --dangerously-bypass-approvals-and-sandbox",
        AgentCli.CODEX.interactiveCommand(true));
  }

  @Test
  void interactiveCommandCodexWithoutPermissions() {
    assertEquals("codex", AgentCli.CODEX.interactiveCommand(false));
  }

  @Test
  void displayNameClaudeCode() {
    assertEquals("Claude Code", AgentCli.CLAUDE_CODE.displayName());
  }

  @Test
  void displayNameCodex() {
    assertEquals("Codex CLI", AgentCli.CODEX.displayName());
  }
}
