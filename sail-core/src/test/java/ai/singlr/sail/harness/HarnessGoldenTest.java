/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.gen.LanguageRulesGenerator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins every launch command and rule file sail produces for each harness, as text. The expected
 * strings here never change; only the expressions that produce them do.
 */
class HarnessGoldenTest {

  private static final String TASK = "/home/dev/.sail/agent-task.txt";
  private static final String SETTINGS = "/home/dev/.sail/claude-settings.json";
  private static final String PROMPT = " -p \"$(cat " + TASK + ")\"";
  private static final String CODEX_TASK = " \"$(cat " + TASK + ")\"";
  private static final String HOME = "/home/dev/";

  private static final String MALFORMED_ID =
      "Malformed session id; refusing to build a resume command from replicated data.";

  private static final String CLAUDE_ROOM =
      "claude --print --settings /home/dev/.sail/claude-settings.json --setting-sources \"\""
          + " --strict-mcp-config --tools \"Bash,Read,Grep,Glob\" --allowedTools \"Bash(spec:*)\""
          + " \"Bash(cd:*)\"";

  private static final String CLAUDE_ROOM_STREAMED =
      "claude --print --output-format stream-json --verbose --settings"
          + " /home/dev/.sail/claude-settings.json --setting-sources \"\" --strict-mcp-config"
          + " --tools \"Bash,Read,Grep,Glob\" --allowedTools \"Bash(spec:*)\" \"Bash(cd:*)\"";

  private static String claudeHeadless(
      boolean full, String model, String effort, String resume, boolean stream) {
    return resume == null
        ? AgentCli.CLAUDE_CODE.headlessCommand(TASK, full, model, effort, SETTINGS, stream)
        : AgentCli.CLAUDE_CODE.headlessResumeCommand(
            resume, TASK, full, model, effort, SETTINGS, stream);
  }

  private static String codexHeadless(
      boolean full, String model, String effort, String resume, boolean stream) {
    return resume == null
        ? AgentCli.CODEX.headlessCommand(TASK, full, model, effort, null, stream)
        : AgentCli.CODEX.headlessResumeCommand(resume, TASK, full, model, effort, null, stream);
  }

  private static String claudeReadOnly(String model, String resume, boolean stream) {
    return resume == null
        ? AgentCli.CLAUDE_CODE.headlessRoomCommand(TASK, model, SETTINGS, stream)
        : AgentCli.CLAUDE_CODE.headlessRoomResumeCommand(resume, TASK, model, SETTINGS, stream);
  }

  @Test
  void claudeCodeHeadlessFresh() {
    assertEquals(
        "claude --print --settings /home/dev/.sail/claude-settings.json" + PROMPT,
        claudeHeadless(false, null, null, null, false));
    assertEquals(
        "claude --print --settings /home/dev/.sail/claude-settings.json"
            + " --dangerously-skip-permissions"
            + PROMPT,
        claudeHeadless(true, null, null, null, false));
    assertEquals(
        "claude --print --output-format stream-json --verbose --settings"
            + " /home/dev/.sail/claude-settings.json --dangerously-skip-permissions"
            + " --model claude-opus-4"
            + PROMPT,
        claudeHeadless(true, "claude-opus-4", "high", null, true));
    assertEquals(
        "claude --print --settings /home/dev/.sail/claude-settings.json" + PROMPT,
        claudeHeadless(false, null, "high", null, false),
        "Claude Code has no reasoning effort flag; the effort is dropped");
  }

  @Test
  void claudeCodeHeadlessResumed() {
    assertEquals(
        "claude --print --settings /home/dev/.sail/claude-settings.json --resume sess-42" + PROMPT,
        claudeHeadless(false, null, null, "sess-42", false));
    assertEquals(
        "claude --print --output-format stream-json --verbose --settings"
            + " /home/dev/.sail/claude-settings.json --dangerously-skip-permissions"
            + " --model claude-opus-4 --resume sess-42"
            + PROMPT,
        claudeHeadless(true, "claude-opus-4", "high", "sess-42", true));
  }

  @Test
  void codexHeadlessFresh() {
    assertEquals("codex exec" + CODEX_TASK, codexHeadless(false, null, null, null, false));
    assertEquals(
        "codex exec --dangerously-bypass-approvals-and-sandbox --dangerously-bypass-hook-trust"
            + CODEX_TASK,
        codexHeadless(true, null, null, null, false));
    assertEquals(
        "codex exec --dangerously-bypass-approvals-and-sandbox --dangerously-bypass-hook-trust"
            + " --model gpt-5.5 --config model_reasoning_effort='\"high\"'"
            + CODEX_TASK,
        codexHeadless(true, "gpt-5.5", "high", null, true));
    assertEquals(
        "codex exec --model gpt-5.5" + CODEX_TASK,
        codexHeadless(false, "gpt-5.5", null, null, false));
    assertEquals(
        "codex exec --config model_reasoning_effort='\"high\"'" + CODEX_TASK,
        codexHeadless(false, null, "high", null, false));
  }

  @Test
  void codexHeadlessResumed() {
    assertEquals(
        "codex exec resume sess-42" + CODEX_TASK,
        codexHeadless(false, null, null, "sess-42", false));
    assertEquals(
        "codex exec resume --dangerously-bypass-approvals-and-sandbox"
            + " --dangerously-bypass-hook-trust --model gpt-5.5"
            + " --config model_reasoning_effort='\"high\"' sess-42"
            + CODEX_TASK,
        codexHeadless(true, "gpt-5.5", "high", "sess-42", true));
  }

  @Test
  void claudeCodeReadOnlyFresh() {
    assertEquals(CLAUDE_ROOM + PROMPT, claudeReadOnly(null, null, false));
    assertEquals(
        CLAUDE_ROOM_STREAMED + " --model claude-opus-4" + PROMPT,
        claudeReadOnly("claude-opus-4", null, true));
  }

  @Test
  void claudeCodeReadOnlyResumed() {
    assertEquals(
        CLAUDE_ROOM + " --resume sess-42" + PROMPT, claudeReadOnly(null, "sess-42", false));
    assertEquals(
        CLAUDE_ROOM_STREAMED + " --model claude-opus-4 --resume sess-42" + PROMPT,
        claudeReadOnly("claude-opus-4", "sess-42", true));
  }

  @Test
  void codexHasNoReadOnlySession() {
    assertEquals(
        "Codex CLI has no harness-enforced read-only session inside a sail container: its"
            + " bubblewrap sandbox needs user namespaces, which incus containers block, so its only"
            + " working mode bypasses all restrictions. Add it with full access instead — the"
            + " per-turn repo reservation guards that lane.",
        AgentCli.CODEX.readOnlyRefusal());
    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> AgentCli.CODEX.headlessRoomCommand(TASK, null, null, false));
    assertEquals(
        "Codex CLI has no harness-enforced read-only session inside a sail container; the room"
            + " lane refuses to launch it.",
        ex.getMessage());
  }

  @Test
  void aMalformedSessionIdIsRefused() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> claudeHeadless(true, null, null, "$(rm -rf ~)", true));
    assertEquals(MALFORMED_ID, ex.getMessage());
    var room =
        assertThrows(
            IllegalArgumentException.class, () -> claudeReadOnly(null, "$(rm -rf ~)", true));
    assertEquals(MALFORMED_ID, room.getMessage());
    var codex =
        assertThrows(
            IllegalArgumentException.class,
            () -> codexHeadless(true, null, null, "a; rm -rf /", true));
    assertEquals(MALFORMED_ID, codex.getMessage());
  }

  @Test
  void interactive() {
    assertEquals("claude", AgentCli.CLAUDE_CODE.interactiveCommand(false));
    assertEquals(
        "claude --dangerously-skip-permissions", AgentCli.CLAUDE_CODE.interactiveCommand(true));
    assertEquals("codex", AgentCli.CODEX.interactiveCommand(false));
    assertEquals(
        "codex --dangerously-bypass-approvals-and-sandbox",
        AgentCli.CODEX.interactiveCommand(true));
  }

  @Test
  void anUnknownHarnessNameIsRefusedNamingTheKnownOnes() {
    var ex = assertThrows(IllegalArgumentException.class, () -> AgentCli.fromYamlName("helios"));
    assertEquals(
        "Unknown agent CLI: 'helios'. Known agents: claude-code, codex.\n"
            + "  Check the 'install' list in your sail.yaml agent section.",
        ex.getMessage());
  }

  @Test
  void claudeCodeLanguageRule() {
    var files = LanguageRulesGenerator.generateFiles(AgentCli.CLAUDE_CODE, rules(), HOME);

    assertEquals(HOME + ".claude/rules/java.md", files.get(0).remotePath());
    assertEquals(
        """
        ---
        paths:
          - "**/*.java"
          - "pom.xml"
        ---

        Use records.
        """,
        files.get(0).content());
    assertEquals(HOME + ".claude/rules/security.md", files.get(1).remotePath());
    assertEquals("Validate input.\n", files.get(1).content());
  }

  @Test
  void codexLanguageRule() {
    var files = LanguageRulesGenerator.generateFiles(AgentCli.CODEX, rules(), HOME);

    assertEquals(HOME + ".agents/skills/java/SKILL.md", files.get(0).remotePath());
    assertEquals(
        """
        ---
        name: java
        description: >
          Java coding standards for this project. Apply when writing or reviewing java (**/*.java, pom.xml).
        ---

        Use records.
        """,
        files.get(0).content());
    assertEquals(HOME + ".agents/skills/security/SKILL.md", files.get(1).remotePath());
    assertEquals(
        """
        ---
        name: security
        description: >
          Security coding standards for this project. Apply when writing or reviewing security.
        ---

        Validate input.
        """,
        files.get(1).content());
  }

  private static List<SailYaml.AgentRule> rules() {
    return List.of(
        new SailYaml.AgentRule("java", List.of("**/*.java", "pom.xml"), "Use records.\n"),
        new SailYaml.AgentRule("security", List.of(), "Validate input.\n"));
  }
}
