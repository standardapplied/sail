/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.harness.Harness.Launch;
import ai.singlr.sail.harness.Harness.LiveLaunch;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
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

  /** Sail's Codex provider as a test reads it, its per-launch id written as {@code sail_ID}. */
  private static final String PINNED_OPENAI =
      " -c model_provider=sail_ID -c 'model_providers.sail_ID={name=\"OpenAI\","
          + " base_url=\"https://api.openai.com/v1\", env_key=\"CODEX_API_KEY\","
          + " wire_api=\"responses\"}' -c 'shell_environment_policy.set.CODEX_API_KEY=\"\"'";

  /** {@code command} with each Codex provider id Sail made written as {@code sail_ID}. */
  static String withoutId(String command) {
    return command.replaceAll("sail_[0-9a-f]{12}", "sail_ID");
  }

  private static String claudeHeadless(
      boolean full, String model, String effort, String resume, boolean stream) {
    return CLAUDE_CODE.headless(new Launch(TASK, full, model, effort, resume, stream, null));
  }

  private static String codexHeadless(
      boolean full, String model, String effort, String resume, boolean stream) {
    return CODEX.headless(new Launch(TASK, full, model, effort, resume, stream, null));
  }

  private static String claudeReadOnly(String model, String resume, boolean stream) {
    return CLAUDE_CODE.readOnly(new Launch(TASK, false, model, null, resume, stream, null));
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

  /**
   * L2: a run on a stored key gives Codex the key through the provider of Sail's own, on the
   * command line, which fixes the OpenAI API as its address over any config file of the
   * container's; a run on its container's own login gets no provider.
   */
  @Test
  void codexHeadlessOnAStoredKeySendsItToTheOpenAiApiAlone() {
    assertEquals(
        "codex exec --dangerously-bypass-approvals-and-sandbox --dangerously-bypass-hook-trust"
            + PINNED_OPENAI
            + " --model gpt-5.5"
            + CODEX_TASK,
        withoutId(
            CODEX.headless(
                new Launch(TASK, true, "gpt-5.5", null, null, true, CODEX.loginKind("api-key")))));
    assertEquals(
        "codex exec resume --dangerously-bypass-approvals-and-sandbox"
            + " --dangerously-bypass-hook-trust"
            + PINNED_OPENAI
            + " sess-42"
            + CODEX_TASK,
        withoutId(
            CODEX.headless(
                new Launch(TASK, true, null, null, "sess-42", false, CODEX.loginKind("api-key")))));
  }

  /**
   * Each launch on a stored key names its provider by an id of its own, so no config file can name
   * it ahead and merge a table into it (a user's {@code [model_providers.sail]} sent the key to its
   * {@code model_catalog_url}, codex-cli 0.163, measured); a resumed session takes a new id as well
   * as a fresh one does (measured: its turns carry the session's earlier ones).
   */
  @Test
  void everyCodexLaunchOnAStoredKeyNamesItsProviderByAnIdOfItsOwn() {
    var key = CODEX.loginKind("api-key");
    var ids = new HashSet<String>();
    for (var resume : List.of("", "sess-42")) {
      for (var i = 0; i < 3; i++) {
        var command =
            CODEX.headless(
                new Launch(TASK, true, null, null, resume.isEmpty() ? null : resume, false, key));
        var named = Pattern.compile("model_provider=(sail_[0-9a-f]{12}) ").matcher(command);
        assertTrue(named.find(), command);
        assertTrue(command.contains("'model_providers." + named.group(1) + "={"), command);
        ids.add(named.group(1));
      }
    }
    ids.add(
        Pattern.compile("sail_[0-9a-f]{12}")
            .matcher(CODEX.interactiveLogin(key).args())
            .results()
            .findFirst()
            .orElseThrow()
            .group());
    assertEquals(7, ids.size(), "each launch's id is its own: " + ids);
  }

  /** Claude Code's address is fixed in its environment, so a stored login adds nothing here. */
  @Test
  void claudeCodeHeadlessIsTheSameOnAStoredKeyAsOnItsContainersOwnLogin() {
    for (var kind : CLAUDE_CODE.loginKinds()) {
      assertEquals(
          claudeHeadless(true, "opus", "high", "sess-42", true),
          CLAUDE_CODE.headless(new Launch(TASK, true, "opus", "high", "sess-42", true, kind)));
    }
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
        CLAUDE_CODE.readOnly(new Launch(TASK, true, null, "high", null, false, null)));
    assertEquals(
        CLAUDE_ROOM_STREAMED + " --model claude-opus-4 --effort high --resume sess-42" + PROMPT,
        CLAUDE_CODE.readOnly(
            new Launch(TASK, true, "claude-opus-4", "high", "sess-42", true, null)));
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
            () -> CODEX.readOnly(new Launch(TASK, false, null, null, null, false, null)));
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
            + " Read(//home/dev/.ssh/**), Read(//home/dev/.git-credentials),"
            + " Read(//home/dev/.config/gh/**), Read(//home/dev/.sail/runs/**/launch.env)]}}",
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
            "bypassPermissions=Bypass permissions, risky: claude --permission-mode bypassPermissions"),
        shown(CLAUDE_CODE));
    assertEquals(
        List.of("full-access=Full access, risky: codex --dangerously-bypass-approvals-and-sandbox"),
        shown(CODEX));
    assertEquals(Optional.empty(), CLAUDE_CODE.modesNote());
    assertEquals(
        Optional.of(
            "Sail starts Codex CLI in a workspace with full access, running every command without"
                + " asking and without its sandbox: that sandbox, bubblewrap, cannot start in a"
                + " workspace's container."),
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
        .map(
            mode ->
                mode.name()
                    + "="
                    + mode.label()
                    + (mode.risky() ? ", risky" : "")
                    + ": "
                    + harness.interactive(mode.name()))
        .toList();
  }

  /**
   * How an interactive session takes each kind, so the harness keeps nothing of it and sends it
   * nowhere but its service: Claude Code's API key through a helper of the session's own settings,
   * so it is neither asked about nor kept, which the host flag would ignore, so the session drops
   * the flag and fixes the address in the same settings; Codex's key through a provider of its own
   * config reading the variable, never its store.
   */
  @Test
  void anInteractiveSessionTakesEachLoginWithoutTheHarnessKeepingIt() {
    assertEquals(
        new Harness.InteractiveLogin(
            "export SAIL_ANTHROPIC_API_KEY=\"$ANTHROPIC_API_KEY\"; unset ANTHROPIC_API_KEY"
                + " CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST",
            " --settings '{\"apiKeyHelper\": \"printenv SAIL_ANTHROPIC_API_KEY\", \"env\":"
                + " {\"ANTHROPIC_AUTH_TOKEN\": \"\", \"CLAUDE_CODE_OAUTH_TOKEN\": \"\","
                + " \"CLAUDE_CODE_API_KEY_FILE_DESCRIPTOR\": \"\","
                + " \"CLAUDE_CODE_OAUTH_TOKEN_FILE_DESCRIPTOR\": \"\","
                + " \"CLAUDE_CODE_GATEWAY_TOKEN_FILE_DESCRIPTOR\": \"\","
                + " \"CLAUDE_CODE_WEBSOCKET_AUTH_FILE_DESCRIPTOR\": \"\","
                + " \"ANTHROPIC_CUSTOM_HEADERS\": \"\", \"CLAUDE_CODE_USE_BEDROCK\": \"\","
                + " \"CLAUDE_CODE_USE_VERTEX\": \"\", \"CLAUDE_CODE_USE_FOUNDRY\": \"\","
                + " \"CLAUDE_CODE_USE_MANTLE\": \"\", \"CLAUDE_CODE_USE_ANTHROPIC_AWS\": \"\","
                + " \"CLAUDE_CODE_USE_ANTHROPIC_GOOGLE_CLOUD\": \"\","
                + " \"ANTHROPIC_BASE_URL\": \"https://api.anthropic.com\","
                + " \"ANTHROPIC_UNIX_SOCKET\": \"\", \"ANTHROPIC_API_KEY\": \"\"}}'"),
        CLAUDE_CODE.interactiveLogin(CLAUDE_CODE.loginKind("api-key")));
    assertEquals(
        new Harness.InteractiveLogin("", PINNED_OPENAI),
        new Harness.InteractiveLogin(
            "", withoutId(CODEX.interactiveLogin(CODEX.loginKind("api-key")).args())));
    assertEquals(
        List.of(".claude/.credentials.json", ".claude.json", ".claude/backups"),
        CLAUDE_CODE.storedLogins());
    assertEquals(List.of(".codex/auth.json"), CODEX.storedLogins());
  }

  /**
   * L4: every harness's own sign-in, by the name a person knows it by, in a sentence true for it,
   * and the one file under the dev user's home that holds it: Claude Code's {@code /login} keeps it
   * in {@code .credentials.json}, never in {@code .claude.json}, its own state; Codex's ChatGPT
   * sign-in, by device code once ChatGPT allows it, in {@code auth.json}.
   */
  @Test
  void eachHarnessNamesItsOwnSignInAndTheFileThatHoldsIt() {
    assertEquals(
        new SignIn(
            "Your Claude account",
            Remedy.of(
                "Claude Code asks you to sign in with your Claude account when it starts, once in"
                    + " each workspace."),
            ".claude/.credentials.json"),
        CLAUDE_CODE.signIn());
    assertEquals(
        new SignIn(
            "Your ChatGPT account",
            Remedy.of(
                "Codex CLI asks you to sign in with your ChatGPT account when it starts, once in"
                    + " each workspace. Choose “Sign in with Device Code” there, after"
                    + " turning it on in your ChatGPT security settings."),
            ".codex/auth.json"),
        CODEX.signIn());
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
            "api-key ANTHROPIC_API_KEY Create an API key in the Claude Console"
                + " {ANTHROPIC_BASE_URL=https://api.anthropic.com,"
                + " ANTHROPIC_UNIX_SOCKET=, CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST=1}"),
        described(CLAUDE_CODE),
        "its key fixes the address it is sent to, and makes Claude Code take it from its"
            + " environment alone");
    assertEquals(
        List.of("api-key CODEX_API_KEY Create an API key at platform.openai.com/api-keys {}"),
        described(CODEX),
        "Codex reads no address from its environment: its provider on the command line fixes it");
    assertEquals(
        List.of(
            "ANTHROPIC_AUTH_TOKEN",
            "ANTHROPIC_API_KEY",
            "CLAUDE_CODE_OAUTH_TOKEN",
            "CLAUDE_CODE_API_KEY_FILE_DESCRIPTOR",
            "CLAUDE_CODE_OAUTH_TOKEN_FILE_DESCRIPTOR",
            "CLAUDE_CODE_GATEWAY_TOKEN_FILE_DESCRIPTOR",
            "CLAUDE_CODE_WEBSOCKET_AUTH_FILE_DESCRIPTOR",
            "ANTHROPIC_CUSTOM_HEADERS"),
        CLAUDE_CODE.loginVariables(),
        "every variable Claude Code takes a credential from, the gateway token outranking both");
    assertEquals(
        List.of("CODEX_API_KEY", "CODEX_ACCESS_TOKEN"),
        CODEX.loginVariables(),
        "a container's own access token never outranks the stored key");
    assertEquals(
        List.of(
            "CLAUDE_CODE_USE_BEDROCK",
            "CLAUDE_CODE_USE_VERTEX",
            "CLAUDE_CODE_USE_FOUNDRY",
            "CLAUDE_CODE_USE_MANTLE",
            "CLAUDE_CODE_USE_ANTHROPIC_AWS",
            "CLAUDE_CODE_USE_ANTHROPIC_GOOGLE_CLOUD"),
        CLAUDE_CODE.credentialSwitches(),
        "each sends Claude Code to a cloud account, outranking any login");
    assertEquals(List.of(), CODEX.credentialSwitches());
  }

  private static List<String> described(Harness harness) {
    return harness.loginKinds().stream()
        .map(
            kind ->
                kind.kind()
                    + " "
                    + kind.variable()
                    + " "
                    + kind.howToMake().described()
                    + " "
                    + kind.fixed())
        .toList();
  }
}
