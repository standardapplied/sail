/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.harness.Harness.Launch;
import ai.singlr.sail.harness.Harness.LiveLaunch;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins every launch command and rule file sail produces for each harness, as text. The expected
 * strings here never change; only the expressions that produce them do.
 */
class HarnessGoldenTest {

  private static final String TASK = "/home/dev/.sail/agent-task.txt";
  private static final String PROMPT = " -p \"$(cat " + TASK + ")\"";
  private static final String CODEX_TASK = " \"$(cat " + TASK + ")\"";
  private static final Harness CLAUDE_CODE = Harnesses.of("claude-code");
  private static final Harness CODEX = Harnesses.of("codex");

  private static final String MALFORMED_ID =
      "Malformed session id; refusing to build a resume command from replicated data.";

  private static final String READ_ONLY_RULES =
      " --tools \"Bash,Read,Grep,Glob\" --allowedTools \"Bash(spec show:*)\""
          + " \"Bash(spec list:*)\" \"Bash(spec board:*)\" \"Bash(spec comments:*)\""
          + " \"Bash(spec comment:*)\" \"Bash(spec whoami:*)\" \"Bash(cd:*)\""
          + " --disallowedTools \"Bash(spec *--body-file*)\" \"Bash(spec *--plan-file*)\""
          + " \"Bash(printenv:*)\" \"Bash(env:*)\" \"Bash(set:*)\" \"Bash(export:*)\""
          + " \"Bash(declare:*)\" \"Read(//proc/**)\"";

  private static final String CLAUDE_ROOM =
      "claude --print --settings /home/dev/.sail/claude-settings.json --setting-sources \"\""
          + " --strict-mcp-config"
          + READ_ONLY_RULES;

  private static final String CLAUDE_ROOM_STREAMED =
      "claude --print --output-format stream-json --verbose --settings"
          + " /home/dev/.sail/claude-settings.json --setting-sources \"\" --strict-mcp-config"
          + READ_ONLY_RULES;

  private static String claudeHeadless(
      boolean full, String model, String effort, String resume, boolean stream) {
    return CLAUDE_CODE.headless(new Launch(TASK, full, model, effort, resume, stream));
  }

  private static String codexHeadless(
      boolean full, String model, String effort, String resume, boolean stream) {
    return CODEX.headless(new Launch(TASK, full, model, effort, resume, stream));
  }

  private static String claudeReadOnly(String model, String resume, boolean stream) {
    return CLAUDE_CODE.readOnly(new Launch(TASK, false, model, null, resume, stream));
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
            + " --model claude-opus-4 --effort high"
            + PROMPT,
        claudeHeadless(true, "claude-opus-4", "high", null, true));
    assertEquals(
        "claude --print --settings /home/dev/.sail/claude-settings.json --effort xhigh" + PROMPT,
        claudeHeadless(false, null, "xhigh", null, false),
        "Claude Code's --effort takes the effort the launch asks");
  }

  @Test
  void claudeCodeHeadlessResumed() {
    assertEquals(
        "claude --print --settings /home/dev/.sail/claude-settings.json --resume sess-42" + PROMPT,
        claudeHeadless(false, null, null, "sess-42", false));
    assertEquals(
        "claude --print --output-format stream-json --verbose --settings"
            + " /home/dev/.sail/claude-settings.json --dangerously-skip-permissions"
            + " --model claude-opus-4 --effort high --resume sess-42"
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
  void claudeCodeReadOnlyIgnoresFullPermissionsAndPassesTheEffort() {
    assertEquals(
        CLAUDE_ROOM + " --effort high" + PROMPT,
        CLAUDE_CODE.readOnly(new Launch(TASK, true, null, "high", null, false)));
    assertEquals(
        CLAUDE_ROOM_STREAMED + " --model claude-opus-4 --effort high --resume sess-42" + PROMPT,
        CLAUDE_CODE.readOnly(new Launch(TASK, true, "claude-opus-4", "high", "sess-42", true)));
  }

  @Test
  void codexHasNoReadOnlySession() {
    assertEquals(
        "Codex CLI has no harness-enforced read-only session inside a sail container: its"
            + " bubblewrap sandbox needs user namespaces, which incus containers block, so its only"
            + " working mode bypasses all restrictions. Add it with full access instead — the"
            + " per-turn repo reservation guards that lane.",
        CODEX.readOnlyRefusal().orElseThrow());
    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> CODEX.readOnly(new Launch(TASK, false, null, null, null, false)));
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
  void claudeCodeLiveFullMember() {
    assertEquals(
        List.of(
            "claude",
            "--print",
            "--input-format",
            "stream-json",
            "--output-format",
            "stream-json",
            "--verbose",
            "--include-partial-messages",
            "--permission-prompts",
            "none",
            "--settings",
            "/home/dev/.sail/claude-live.json",
            "--dangerously-skip-permissions"),
        CLAUDE_CODE.live(new LiveLaunch(true, null, null)).orElseThrow());
    assertEquals(
        List.of(
            "claude",
            "--print",
            "--input-format",
            "stream-json",
            "--output-format",
            "stream-json",
            "--verbose",
            "--include-partial-messages",
            "--permission-prompts",
            "none",
            "--settings",
            "/home/dev/.sail/claude-live.json",
            "--dangerously-skip-permissions",
            "--model",
            "opus",
            "--resume",
            "sess-1"),
        CLAUDE_CODE.live(new LiveLaunch(true, "opus", "sess-1")).orElseThrow());
  }

  @Test
  void claudeCodeLiveReadOnlyMember() {
    assertEquals(
        List.of(
            "claude",
            "--print",
            "--input-format",
            "stream-json",
            "--output-format",
            "stream-json",
            "--verbose",
            "--include-partial-messages",
            "--permission-prompts",
            "none",
            "--settings",
            "/home/dev/.sail/claude-live.json",
            "--setting-sources",
            "",
            "--strict-mcp-config",
            "--tools",
            "Bash,Read,Grep,Glob",
            "--allowedTools",
            "Bash(spec show:*)",
            "Bash(spec list:*)",
            "Bash(spec board:*)",
            "Bash(spec comments:*)",
            "Bash(spec comment:*)",
            "Bash(spec whoami:*)",
            "Bash(cd:*)",
            "--disallowedTools",
            "Bash(spec *--body-file*)",
            "Bash(spec *--plan-file*)",
            "Bash(printenv:*)",
            "Bash(env:*)",
            "Bash(set:*)",
            "Bash(export:*)",
            "Bash(declare:*)",
            "Read(//proc/**)",
            "--resume",
            "sess-1"),
        CLAUDE_CODE.live(new LiveLaunch(false, null, "sess-1")).orElseThrow());
  }

  @Test
  void aLiveLaunchRefusesAMalformedSessionId() {
    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> CLAUDE_CODE.live(new LiveLaunch(true, null, "-p; rm -rf /")));

    assertEquals(MALFORMED_ID, thrown.getMessage());
  }

  @Test
  void claudeCodeLiveSettingsCarryTheReadDeniesAndNoHook() {
    var settings = CLAUDE_CODE.liveSettings().orElseThrow();

    assertEquals("/home/dev/.sail/claude-live.json", settings.path());
    assertEquals("/home/dev/.sail", settings.directory());
    assertEquals(
        "{includeCoAuthoredBy=false, permissions={deny=[Read(//var/lib/sail/run/box.credential),"
            + " Read(/home/dev/.ssh/**), Read(/home/dev/.git-credentials),"
            + " Read(/home/dev/.config/gh/**), Read(/home/dev/.sail/runs/**/launch.env)]}}",
        settings.entries().toString());
  }

  @Test
  void codexHasNoLiveMode() {
    assertEquals(Optional.empty(), CODEX.live(new LiveLaunch(true, null, null)));
    assertEquals(Optional.empty(), CODEX.liveSettings());
  }

  /**
   * Claude Code's modes are its own permission modes, each once and passed as it names them, after
   * its own start with no flag; Codex has the one it runs in inside a container, and says why.
   */
  @Test
  void anInteractiveSessionStartsOnlyInAModeItsHarnessTakesInAWorkspace() {
    assertEquals(
        List.of(
            "default=Claude Code's own: claude",
            "manual=Manual: claude --permission-mode manual",
            "acceptEdits=Accept edits: claude --permission-mode acceptEdits",
            "plan=Plan: claude --permission-mode plan",
            "auto=Auto: claude --permission-mode auto",
            "dontAsk=Don't ask: claude --permission-mode dontAsk",
            "bypassPermissions=Bypass permissions: claude --permission-mode bypassPermissions"),
        shown(CLAUDE_CODE));
    assertEquals(
        List.of("full-access=Full access: codex --dangerously-bypass-approvals-and-sandbox"),
        shown(CODEX));
    assertEquals(Optional.empty(), CLAUDE_CODE.modesNote());
    assertEquals(
        Optional.of(
            "Codex CLI runs in a workspace only with full access: its sandbox needs user"
                + " namespaces, which a workspace's container does not allow. For a session that"
                + " asks before it acts, start Claude Code."),
        CODEX.modesNote());
    assertTrue(
        Stream.of(CLAUDE_CODE, CODEX)
            .flatMap(harness -> harness.modes().stream())
            .noneMatch(mode -> mode.about().isBlank()),
        "every mode says what it does");
    assertEquals(
        "Codex CLI takes no mode 'default' in a workspace; it takes full-access.",
        assertThrows(IllegalArgumentException.class, () -> CODEX.interactive("default"))
            .getMessage());
    assertEquals(
        "Claude Code takes no mode 'full' in a workspace; it takes default, manual, acceptEdits,"
            + " plan, auto, dontAsk, bypassPermissions.",
        assertThrows(IllegalArgumentException.class, () -> CLAUDE_CODE.interactive("full"))
            .getMessage());
  }

  private static List<String> shown(Harness harness) {
    return harness.modes().stream()
        .map(mode -> mode.name() + "=" + mode.label() + ": " + harness.interactive(mode.name()))
        .toList();
  }

  /**
   * How an interactive session takes each kind, so the harness keeps nothing of it: Claude Code's
   * token from its variable; its API key through a helper of the session's own settings, so it is
   * neither asked about nor kept; Codex's key through a provider of its own config reading the
   * variable, never its store.
   */
  @Test
  void anInteractiveSessionTakesEachLoginWithoutTheHarnessKeepingIt() {
    assertEquals(
        Harness.InteractiveLogin.READS_ITS_VARIABLE,
        CLAUDE_CODE.interactiveLogin(CLAUDE_CODE.loginKind("token")));
    assertEquals(
        new Harness.InteractiveLogin(
            "export SAIL_ANTHROPIC_API_KEY=\"$ANTHROPIC_API_KEY\"; unset ANTHROPIC_API_KEY",
            " --settings '{\"apiKeyHelper\": \"printenv SAIL_ANTHROPIC_API_KEY\"}'"),
        CLAUDE_CODE.interactiveLogin(CLAUDE_CODE.loginKind("api-key")));
    assertEquals(
        new Harness.InteractiveLogin(
            "",
            " -c model_provider=sail -c 'model_providers.sail={name=\"OpenAI\","
                + " base_url=\"https://api.openai.com/v1\", env_key=\"CODEX_API_KEY\","
                + " wire_api=\"responses\"}' -c 'shell_environment_policy.set.CODEX_API_KEY=\"\"'"),
        CODEX.interactiveLogin(CODEX.loginKind("api-key")));
    assertEquals(List.of(".claude/.credentials.json", ".claude.json"), CLAUDE_CODE.storedLogins());
    assertEquals(List.of(".codex/auth.json"), CODEX.storedLogins());
  }

  @Test
  void interactive() {
    assertEquals("claude", CLAUDE_CODE.interactive(false));
    assertEquals("claude --dangerously-skip-permissions", CLAUDE_CODE.interactive(true));
    assertEquals("codex", CODEX.interactive(false));
    assertEquals("codex --dangerously-bypass-approvals-and-sandbox", CODEX.interactive(true));
  }

  @Test
  void attach() {
    assertEquals("claude --resume sess-42", CLAUDE_CODE.attach("sess-42"));
    assertEquals("claude", CLAUDE_CODE.attach(null));
    assertEquals("codex resume sess-42", CODEX.attach("sess-42"));
    assertEquals("codex", CODEX.attach(null));
  }

  @Test
  void anUnknownHarnessNameIsRefusedNamingTheKnownOnes() {
    var ex = assertThrows(IllegalArgumentException.class, () -> Harnesses.of("unknown-agent"));
    assertEquals(
        "Unknown agent CLI: 'unknown-agent'. Known agents: claude-code, codex.\n"
            + "  Check the install list in the project's agent section.",
        ex.getMessage());
  }

  @Test
  void eachHarnessInstallsWithItsOwnCommand() {
    assertEquals("curl -fsSL https://claude.ai/install.sh | bash", CLAUDE_CODE.installCommand());
    assertEquals(
        "curl -fsSL https://chatgpt.com/codex/install.sh | CODEX_NON_INTERACTIVE=1 sh",
        CODEX.installCommand());
  }

  @Test
  void eachHarnessReadsItsStaticLoginsFromTheVariablesItsReleaseDocuments() {
    assertEquals(
        List.of(
            "token CLAUDE_CODE_OAUTH_TOKEN Run claude setup-token on a machine where Claude Code is"
                + " signed in",
            "api-key ANTHROPIC_API_KEY Create an API key in the Claude Console"),
        described(CLAUDE_CODE));
    assertEquals(
        List.of("api-key CODEX_API_KEY Create an API key at platform.openai.com/api-keys"),
        described(CODEX));
    assertEquals(
        List.of("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN"),
        CLAUDE_CODE.loginVariables(),
        "every variable Claude Code takes a credential from, the gateway token outranking both");
    assertEquals(
        List.of("CODEX_API_KEY", "CODEX_ACCESS_TOKEN"),
        CODEX.loginVariables(),
        "a container's own access token never outranks the stored key");
    assertEquals(
        List.of("CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY"),
        CLAUDE_CODE.credentialSwitches(),
        "each sends Claude Code to a cloud account, outranking any login");
    assertEquals(List.of(), CODEX.credentialSwitches());
  }

  private static List<String> described(Harness harness) {
    return harness.loginKinds().stream()
        .map(kind -> kind.kind() + " " + kind.variable() + " " + kind.howToMake().described())
        .toList();
  }
}
