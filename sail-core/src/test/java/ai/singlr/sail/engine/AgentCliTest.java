/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
  void headlessResumeCommandClaudeCodeResumesTheRecordedSession() {
    var cmd =
        AgentCli.CLAUDE_CODE.headlessResumeCommand(
            "sess-42", TASK, true, null, null, "/home/dev/.sail/claude-settings.json", true);

    assertTrue(cmd.startsWith("claude --print"), cmd);
    assertTrue(cmd.contains("--output-format stream-json --verbose"), cmd);
    assertTrue(cmd.contains("--settings /home/dev/.sail/claude-settings.json"), cmd);
    assertTrue(cmd.contains("--dangerously-skip-permissions"), cmd);
    assertTrue(cmd.contains("--resume sess-42 -p \"$(cat " + TASK + ")\""), cmd);
  }

  @Test
  void headlessResumeCommandCodexUsesExecResumeWithTheTaskAsPrompt() {
    var cmd =
        AgentCli.CODEX.headlessResumeCommand("sess-42", TASK, true, "gpt-5", "high", null, true);

    assertTrue(cmd.startsWith("codex exec resume"), cmd);
    assertTrue(cmd.contains("--dangerously-bypass-approvals-and-sandbox"), cmd);
    assertTrue(cmd.contains("--dangerously-bypass-hook-trust"), cmd);
    assertTrue(cmd.contains("--model gpt-5"), cmd);
    assertTrue(cmd.contains("model_reasoning_effort"), cmd);
    assertTrue(cmd.endsWith(" sess-42 \"$(cat " + TASK + ")\""), cmd);
  }

  @Test
  void headlessResumeCommandRefusesAMalformedSessionId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentCli.CLAUDE_CODE.headlessResumeCommand(
                "$(rm -rf ~)", TASK, true, null, null, null, true));
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentCli.CODEX.headlessResumeCommand(null, TASK, true, null, null, null, true));
  }

  @Test
  void isSafeSessionIdAcceptsUuidsAndRejectsShellMetacharacters() {
    assertTrue(AgentCli.isSafeSessionId("0195a2f0-0000-7000-8000-000000000001"));
    assertFalse(AgentCli.isSafeSessionId("a; rm -rf /"));
    assertFalse(AgentCli.isSafeSessionId(""));
    assertFalse(AgentCli.isSafeSessionId(null));
  }

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
  void headlessCommandClaudeCodeToleratesModelAndReasoningEffort() {
    var cmd = AgentCli.CLAUDE_CODE.headlessCommand(TASK, true, "claude-opus-4", "high", null);

    assertTrue(cmd.contains("claude --print"));
    assertTrue(cmd.contains("--model claude-opus-4"), "an explicit model choice is honored");
    assertFalse(cmd.contains("reasoning"), "reasoning_effort is dropped for Claude Code");
    assertTrue(cmd.contains("-p \"$(cat " + TASK + ")\""));
  }

  @Test
  void headlessCommandClaudeCodeToleratesReasoningEffortNone() {
    var cmd = AgentCli.CLAUDE_CODE.headlessCommand(TASK, true, null, "none", null);

    assertTrue(cmd.contains("claude --print"));
    assertFalse(cmd.contains("--model"), "no model flag when model is null");
    assertFalse(cmd.contains("reasoning"));
  }

  @Test
  void headlessCommandCodexStillReceivesModelAndReasoningEffort() {
    var cmd = AgentCli.CODEX.headlessCommand(TASK, true, "gpt-5.5", "high", null);

    assertTrue(cmd.contains("--model gpt-5.5"));
    assertTrue(cmd.contains("model_reasoning_effort='\"high\"'"));
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
    assertTrue(
        cmd.contains("--dangerously-bypass-hook-trust"),
        "Codex silently skips untrusted hooks in headless exec, so without this flag the"
            + " sail-owned hooks layer (and its stop gate) would never fire");
    assertTrue(cmd.contains("\"$(cat " + TASK + ")\""));
    assertFalse(cmd.contains("--print"));
  }

  @Test
  void headlessCommandCodexWithoutPermissions() {
    var cmd = AgentCli.CODEX.headlessCommand(TASK, false);

    assertTrue(cmd.contains("codex exec"));
    assertFalse(cmd.contains("--full-auto"));
    assertFalse(
        cmd.contains("--dangerously-bypass-hook-trust"),
        "hooks run outside the Codex sandbox, so a sandboxed session must not auto-trust them");
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

  @Test
  void roomLaneIsClaudeOnly() {
    assertTrue(AgentCli.CLAUDE_CODE.supportsRoomLane());
    assertFalse(AgentCli.CODEX.supportsRoomLane());
  }

  @Test
  void headlessRoomCommandIsHarnessRestrictedNeverFullPermission() {
    var cmd =
        AgentCli.CLAUDE_CODE.headlessRoomCommand(
            TASK, null, "/home/dev/.sail/claude-settings.json", true);

    assertTrue(cmd.startsWith("claude --print"), cmd);
    assertTrue(cmd.contains("--output-format stream-json --verbose"), cmd);
    assertTrue(cmd.contains("--settings /home/dev/.sail/claude-settings.json"), cmd);
    assertFalse(cmd.contains("--dangerously-skip-permissions"), cmd);
    assertTrue(cmd.contains("--tools \"Bash,Read,Grep,Glob\""), cmd);
    assertTrue(cmd.contains("\"Bash(spec:*)\""), cmd);
    assertTrue(cmd.contains("\"Bash(cd:*)\""), cmd);
    assertFalse(
        cmd.contains("git"),
        "git is not allowlisted: git diff --output=<path> writes through a prefix rule, and"
            + " git's external-diff/pager config is a command-execution surface — a read-only"
            + " lane exposes neither. Reading is Read/Grep/Glob. Command: "
            + cmd);
    assertTrue(cmd.endsWith(" -p \"$(cat " + TASK + ")\""), cmd);
  }

  @Test
  void roomCommandExcludesAmbientSettingsSoNoWorkspaceFileCanWidenPermissions() {
    var cmd =
        AgentCli.CLAUDE_CODE.headlessRoomCommand(
            TASK, null, "/home/dev/.sail/claude-settings.json", true);

    assertTrue(
        cmd.contains("--setting-sources \"\""),
        "ambient user/project/local settings merge permission allow-rules additively; the room"
            + " lane must exclude every settings source except the sail-owned --settings file."
            + " Command: "
            + cmd);
    assertTrue(
        cmd.contains("--strict-mcp-config"),
        "a workspace .mcp.json launches MCP server processes into the session; the room lane"
            + " must ignore every ambient MCP configuration. Command: "
            + cmd);
  }

  @Test
  void headlessRoomResumeCommandKeepsTheRestrictionsOnTheRecordedSession() {
    var cmd = AgentCli.CLAUDE_CODE.headlessRoomResumeCommand("sess-42", TASK, "opus", null, true);

    assertFalse(cmd.contains("--dangerously-skip-permissions"), cmd);
    assertTrue(cmd.contains("--tools \"Bash,Read,Grep,Glob\""), cmd);
    assertTrue(cmd.contains("--setting-sources \"\""), cmd);
    assertTrue(cmd.contains("--strict-mcp-config"), cmd);
    assertTrue(cmd.contains("--model opus"), cmd);
    assertTrue(cmd.contains("--resume sess-42 -p \"$(cat " + TASK + ")\""), cmd);
  }

  @Test
  void headlessRoomResumeCommandRefusesAMalformedSessionId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentCli.CLAUDE_CODE.headlessRoomResumeCommand("$(rm -rf ~)", TASK, null, null, true));
  }

  @Test
  void roomCommandsRefuseCodexLoudly() {
    assertThrows(
        IllegalStateException.class,
        () -> AgentCli.CODEX.headlessRoomCommand(TASK, null, null, true));
    assertThrows(
        IllegalStateException.class,
        () -> AgentCli.CODEX.headlessRoomResumeCommand("sess-42", TASK, null, null, true));
  }

  @Test
  void readOnlyInviteSupportMatchesTheRoomLaneBoundary() {
    assertTrue(AgentCli.CLAUDE_CODE.supportsReadOnlyInvite());
    assertFalse(AgentCli.CODEX.supportsReadOnlyInvite());
  }

  @Test
  void readOnlyInviteRefusalNamesTheFullLaneAsTheAlternative() {
    assertNull(AgentCli.CLAUDE_CODE.readOnlyInviteRefusal());
    var reason = AgentCli.CODEX.readOnlyInviteRefusal();
    assertTrue(reason.contains("Codex CLI"), reason);
    assertTrue(reason.contains("full access"), reason);
  }
}
