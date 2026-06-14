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
    assertFalse(cli.requiresNode());
    assertEquals(AgentCli.InstallMethod.NATIVE_SCRIPT, cli.method());
    assertTrue(cli.installCommand().contains("claude.ai/install.sh"));
  }

  @Test
  void fromYamlNameResolvesCodex() {
    var cli = AgentCli.fromYamlName("codex");

    assertEquals("codex", cli.yamlName());
    assertEquals("codex", cli.binaryName());
    assertTrue(cli.requiresNode());
    assertEquals(AgentCli.InstallMethod.NPM, cli.method());
    assertTrue(cli.installCommand().contains("@openai/codex"));
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
  void claudeCodeDoesNotRequireNode() {
    assertFalse(AgentCli.CLAUDE_CODE.requiresNode());
  }

  @Test
  void codexRequiresNode() {
    assertTrue(AgentCli.CODEX.requiresNode());
  }

  @Test
  void claudeCodeUsesNativeScript() {
    assertEquals(AgentCli.InstallMethod.NATIVE_SCRIPT, AgentCli.CLAUDE_CODE.method());
  }

  @Test
  void codexUsesNpm() {
    assertEquals(AgentCli.InstallMethod.NPM, AgentCli.CODEX.method());
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
    assertEquals("Codex CLI (@openai/codex)", AgentCli.CODEX.displayName());
  }
}
