/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.harness.Harness.Launch;
import java.util.List;
import org.junit.jupiter.api.Test;

class HarnessesTest {

  private static final String TASK = "/home/dev/.sail/agent-task.txt";
  private static final Harness CLAUDE_CODE = Harnesses.of("claude-code");
  private static final Harness CODEX = Harnesses.of("codex");

  private static Launch fresh(boolean full) {
    return new Launch(TASK, full, null, null, null, false);
  }

  @Test
  void ofResolvesClaudeCode() {
    assertEquals("claude-code", CLAUDE_CODE.yamlName());
    assertEquals("claude", CLAUDE_CODE.binaryName());
    assertTrue(CLAUDE_CODE.installCommand().contains("claude.ai/install.sh"));
  }

  @Test
  void ofResolvesCodex() {
    assertEquals("codex", CODEX.yamlName());
    assertEquals("codex", CODEX.binaryName());
    assertTrue(
        CODEX.installCommand().contains("chatgpt.com/codex/install.sh"),
        "Codex installs via the native script, not npm");
    assertTrue(CODEX.installCommand().contains("CODEX_NON_INTERACTIVE=1"));
  }

  @Test
  void ofThrowsOnUnknown() {
    var ex = assertThrows(IllegalArgumentException.class, () -> Harnesses.of("unknown-agent"));

    assertTrue(ex.getMessage().contains("Unknown agent CLI"));
    assertTrue(ex.getMessage().contains("unknown-agent"));
    assertTrue(ex.getMessage().contains("claude-code, codex"));
  }

  @Test
  void theDefaultIsClaudeCodeAndListsItFirst() {
    assertSame(CLAUDE_CODE, Harnesses.DEFAULT);
    assertEquals(List.of(CLAUDE_CODE, CODEX), Harnesses.all());
    assertEquals(List.of("claude-code", "codex"), Harnesses.names());
  }

  @Test
  void bothAgentsInstallViaNativeScript() {
    assertTrue(CLAUDE_CODE.installCommand().startsWith("curl "));
    assertTrue(CODEX.installCommand().startsWith("curl "));
    assertFalse(CODEX.installCommand().contains("npm"));
  }

  @Test
  void headlessResumeClaudeCodeResumesTheRecordedSession() {
    var cmd = CLAUDE_CODE.headless(new Launch(TASK, true, null, null, "sess-42", true));

    assertTrue(cmd.startsWith("claude --print"), cmd);
    assertTrue(cmd.contains("--output-format stream-json --verbose"), cmd);
    assertTrue(cmd.contains("--settings /home/dev/.sail/claude-settings.json"), cmd);
    assertTrue(cmd.contains("--dangerously-skip-permissions"), cmd);
    assertTrue(cmd.contains("--resume sess-42 -p \"$(cat " + TASK + ")\""), cmd);
  }

  @Test
  void headlessResumeCodexUsesExecResumeWithTheTaskAsPrompt() {
    var cmd = CODEX.headless(new Launch(TASK, true, "gpt-5", "high", "sess-42", true));

    assertTrue(cmd.startsWith("codex exec resume"), cmd);
    assertTrue(cmd.contains("--dangerously-bypass-approvals-and-sandbox"), cmd);
    assertTrue(cmd.contains("--dangerously-bypass-hook-trust"), cmd);
    assertTrue(cmd.contains("--model gpt-5"), cmd);
    assertTrue(cmd.contains("model_reasoning_effort"), cmd);
    assertTrue(cmd.endsWith(" sess-42 \"$(cat " + TASK + ")\""), cmd);
  }

  @Test
  void headlessClaudeCodeWithPermissions() {
    var cmd = CLAUDE_CODE.headless(fresh(true));

    assertTrue(cmd.contains("claude --print"));
    assertTrue(cmd.contains("--dangerously-skip-permissions"));
    assertTrue(cmd.contains("-p \"$(cat " + TASK + ")\""));
  }

  @Test
  void headlessClaudeCodePutsTheSettingsFileBeforeThePermissionFlag() {
    var cmd = CLAUDE_CODE.headless(fresh(true));

    assertTrue(
        cmd.contains("claude --print --settings /home/dev/.sail/claude-settings.json"),
        "settings flag must appear before permission flag for stable arg ordering");
    assertTrue(cmd.contains("--dangerously-skip-permissions"));
  }

  @Test
  void headlessClaudeCodeStreamingAddsStreamJson() {
    var cmd = CLAUDE_CODE.headless(new Launch(TASK, true, null, null, null, true));

    assertTrue(
        cmd.contains("claude --print --output-format stream-json --verbose"),
        "streaming dispatch emits newline-delimited JSON events");
    assertTrue(cmd.contains("-p \"$(cat " + TASK + ")\""));
  }

  @Test
  void headlessClaudeCodeNonStreamingHasNoStreamJson() {
    assertFalse(CLAUDE_CODE.headless(fresh(true)).contains("stream-json"));
  }

  @Test
  void headlessCodexIgnoresStreamFlag() {
    var streamed = CODEX.headless(new Launch(TASK, true, null, null, null, true));
    var plain = CODEX.headless(fresh(true));

    assertEquals(plain, streamed, "Codex streams readable text already; the flag is a no-op");
    assertFalse(streamed.contains("stream-json"));
  }

  @Test
  void headlessClaudeCodeToleratesModelAndReasoningEffort() {
    var cmd = CLAUDE_CODE.headless(new Launch(TASK, true, "claude-opus-4", "high", null, false));

    assertTrue(cmd.contains("claude --print"));
    assertTrue(cmd.contains("--model claude-opus-4"), "an explicit model choice is honored");
    assertFalse(cmd.contains("reasoning"), "reasoning_effort is dropped for Claude Code");
    assertTrue(cmd.contains("-p \"$(cat " + TASK + ")\""));
  }

  @Test
  void headlessClaudeCodeToleratesReasoningEffortNone() {
    var cmd = CLAUDE_CODE.headless(new Launch(TASK, true, null, "none", null, false));

    assertTrue(cmd.contains("claude --print"));
    assertFalse(cmd.contains("--model"), "no model flag when model is null");
    assertFalse(cmd.contains("reasoning"));
  }

  @Test
  void headlessCodexStillReceivesModelAndReasoningEffort() {
    var cmd = CODEX.headless(new Launch(TASK, true, "gpt-5.5", "high", null, false));

    assertTrue(cmd.contains("--model gpt-5.5"));
    assertTrue(cmd.contains("model_reasoning_effort='\"high\"'"));
  }

  @Test
  void headlessClaudeCodeWithoutPermissions() {
    var cmd = CLAUDE_CODE.headless(fresh(false));

    assertTrue(cmd.contains("claude --print"));
    assertTrue(cmd.contains("-p \"$(cat " + TASK + ")\""));
    assertFalse(cmd.contains("--dangerously-skip-permissions"));
  }

  @Test
  void headlessCodexWithPermissions() {
    var cmd = CODEX.headless(fresh(true));

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
  void headlessCodexWithoutPermissions() {
    var cmd = CODEX.headless(fresh(false));

    assertTrue(cmd.contains("codex exec"));
    assertFalse(cmd.contains("--full-auto"));
    assertFalse(
        cmd.contains("--dangerously-bypass-hook-trust"),
        "hooks run outside the Codex sandbox, so a sandboxed session must not auto-trust them");
  }

  @Test
  void displayNames() {
    assertEquals("Claude Code", CLAUDE_CODE.displayName());
    assertEquals("Codex CLI", CODEX.displayName());
  }

  @Test
  void capabilitiesAreAnswersNotNames() {
    assertFalse(CLAUDE_CODE.honoursReasoningEffort());
    assertTrue(CODEX.honoursReasoningEffort());
    assertEquals(3000, CLAUDE_CODE.loginTunnelPort().orElseThrow());
    assertTrue(CODEX.loginTunnelPort().isEmpty());
    assertEquals(
        "Type /rc inside Claude Code to connect from your phone via Remote Control.",
        CLAUDE_CODE.interactiveTip().orElseThrow());
    assertTrue(CODEX.interactiveTip().isEmpty());
    assertEquals(".claude/skills/", CLAUDE_CODE.skillsDir());
    assertEquals(".agents/skills/", CODEX.skillsDir());
  }

  @Test
  void roomLaneIsClaudeOnly() {
    assertTrue(CLAUDE_CODE.readOnlyRefusal().isEmpty());
    assertTrue(CODEX.readOnlyRefusal().isPresent());
  }

  @Test
  void readOnlyIsHarnessRestrictedNeverFullPermission() {
    var cmd = CLAUDE_CODE.readOnly(new Launch(TASK, true, null, null, null, true));

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
  void readOnlyExcludesAmbientSettingsSoNoWorkspaceFileCanWidenPermissions() {
    var cmd = CLAUDE_CODE.readOnly(new Launch(TASK, false, null, null, null, true));

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
  void readOnlyResumeKeepsTheRestrictionsOnTheRecordedSession() {
    var cmd = CLAUDE_CODE.readOnly(new Launch(TASK, false, "opus", null, "sess-42", true));

    assertFalse(cmd.contains("--dangerously-skip-permissions"), cmd);
    assertTrue(cmd.contains("--tools \"Bash,Read,Grep,Glob\""), cmd);
    assertTrue(cmd.contains("--setting-sources \"\""), cmd);
    assertTrue(cmd.contains("--strict-mcp-config"), cmd);
    assertTrue(cmd.contains("--model opus"), cmd);
    assertTrue(cmd.contains("--resume sess-42 -p \"$(cat " + TASK + ")\""), cmd);
  }

  @Test
  void readOnlyRefusesCodexLoudly() {
    assertThrows(IllegalStateException.class, () -> CODEX.readOnly(fresh(false)));
    assertThrows(
        IllegalStateException.class,
        () -> CODEX.readOnly(new Launch(TASK, false, null, null, "sess-42", true)));
  }

  @Test
  void readOnlyRefusalNamesTheFullLaneAsTheAlternative() {
    var reason = CODEX.readOnlyRefusal().orElseThrow();
    assertTrue(reason.contains("Codex CLI"), reason);
    assertTrue(reason.contains("full access"), reason);
  }
}
