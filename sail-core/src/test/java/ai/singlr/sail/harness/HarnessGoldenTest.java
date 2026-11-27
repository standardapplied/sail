/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ChatItem;
import ai.singlr.sail.engine.ChatItem.Detail;
import ai.singlr.sail.engine.LiveStream;
import ai.singlr.sail.engine.Recorded;
import ai.singlr.sail.harness.Harness.Launch;
import ai.singlr.sail.harness.Harness.LiveLaunch;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            + " Read(//home/dev/.git-credentials.sail-new), Read(//home/dev/.config/gh/**),"
            + " Read(//home/dev/.sail/gh-token), Read(//home/dev/.sail/runs/**/launch.env),"
            + " Read(//home/dev/.sail/sessions/**)]}}",
        settings.entries().toString());
  }

  /**
   * A rule's single leading {@code /} anchors it at the directory of the {@code --settings} file
   * that holds it, {@code //} at the root and {@code ~/} at the home (Claude Code's permission
   * rules): probe 2 recorded the first denying nothing and the other two denying, so each deny
   * names its file from the root, the one form probe 3 recorded in both lanes, never from {@code
   * ~/.sail}.
   */
  @Test
  void everyReadDenyNamesItsFileFromTheRoot() {
    @SuppressWarnings("unchecked")
    var denies =
        (List<String>)
            ((Map<String, Object>)
                    CLAUDE_CODE.liveSettings().orElseThrow().entries().get("permissions"))
                .get("deny");

    assertEquals(8, denies.size());
    assertTrue(denies.stream().allMatch(rule -> rule.startsWith("Read(//")), denies.toString());
    assertEquals(
        Map.of(
            "03a-read-deny-abs",
            List.of("Read(/home/dev/probe-secret.txt)", "control_request"),
            "03b-read-deny-double-slash",
            List.of("Read(//home/dev/probe-secret.txt)", "user"),
            "03c-read-deny-tilde",
            List.of("Read(~/probe-secret.txt)", "user")),
        Map.of(
            "03a-read-deny-abs",
            ruleAndAnswer("03a-read-deny-abs", 13),
            "03b-read-deny-double-slash",
            ruleAndAnswer("03b-read-deny-double-slash", 14),
            "03c-read-deny-tilde",
            ruleAndAnswer("03c-read-deny-tilde", 14)),
        "probe 2: a single / in a --settings file denies nothing (a request came), // and ~/"
            + " deny (a refused result came)");
  }

  private static List<String> ruleAndAnswer(String recording, int line) {
    var rule =
        Pattern.compile("Read\\([^)]*\\)")
            .matcher((String) Recorded.parsed(recording, 1).get("note"));
    assertTrue(rule.find(), recording);
    return List.of(rule.group(), (String) Recorded.parsed(recording, line).get("type"));
  }

  @Test
  void codexHasNoLiveMode() {
    assertEquals(Optional.empty(), CODEX.live(new LiveLaunch(true, null, null)));
    assertEquals(Optional.empty(), CODEX.liveSettings());
  }

  private static final HarnessChat CLAUDE_CODE_CHAT = CLAUDE_CODE.chat().orElseThrow();

  /**
   * The argv the S0 probe ran a chat with and saw every request arrive under (02b in its default
   * mode, 03b in plan mode), less the {@code --tools} it passed only to shorten its prompt, and
   * with {@code --forward-subagent-text} where 05c passed it, before the model.
   */
  @SuppressWarnings("unchecked")
  private static List<String> recordedChat(String recording) {
    var argv = new ArrayList<>((List<String>) Recorded.parsed(recording, 1).get("argv"));
    var tools = argv.indexOf("--tools");
    argv.subList(tools, tools + 2).clear();
    var forwarding = (List<String>) Recorded.parsed("05c-subagent-forward-text", 1).get("argv");
    assertEquals("--model", forwarding.get(forwarding.indexOf("--forward-subagent-text") + 1));
    argv.add(argv.indexOf("--model"), "--forward-subagent-text");
    return argv;
  }

  @Test
  void claudeCodeChatIsTheLineItWasRecordedAskingUnder() {
    assertEquals(
        List.of(
            Recorded.parsed("01a-mode-manual", 1).get("argv"),
            Recorded.parsed("01c-mode-manual-over-workspace-acceptEdits", 1).get("argv")),
        List.of(
            CLAUDE_CODE_CHAT.argv(new HarnessChat.Launch("default", "haiku", null)),
            CLAUDE_CODE_CHAT.argv(new HarnessChat.Launch("default", "haiku", null))),
        "Asks first is named, as manual, the name --help gives Claude Code's default, so no"
            + " workspace default overrides it: probe 3 launched exactly this line (01a, 01c)");
    assertEquals(
        recordedChat("03b-plan-exit-plan-mode"),
        CLAUDE_CODE_CHAT.argv(new HarnessChat.Launch("plan", "haiku", null)));
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
            "--settings",
            "/home/dev/.sail/claude-live.json",
            "--forward-subagent-text",
            "--permission-prompt-tool",
            "stdio",
            "--permission-mode",
            "acceptEdits",
            "--resume",
            "sess-1"),
        CLAUDE_CODE_CHAT.argv(new HarnessChat.Launch("acceptEdits", null, "sess-1")));
  }

  @Test
  void aResumedChatStartsInTheModeItsHarnessLastSaidEvenOneNoPersonChooses() {
    assertEquals(
        List.of("--permission-mode", "bypassPermissions", "--resume", "sess-1"),
        tail(CLAUDE_CODE_CHAT.argv(new HarnessChat.Launch("bypassPermissions", null, "sess-1"))),
        "a workspace's settings chose it, and the chat goes on in it");
    assertEquals(
        List.of("--permission-mode", "dontAsk", "--resume", "sess-1"),
        tail(CLAUDE_CODE_CHAT.argv(new HarnessChat.Launch("dontAsk", null, "sess-1"))));
    assertEquals(
        List.of("--permission-mode", "manual", "--resume", "sess-1"),
        tail(CLAUDE_CODE_CHAT.argv(new HarnessChat.Launch("default", null, "sess-1"))),
        "a chat mode's own flags, Claude Code's default named too");
  }

  private static List<String> tail(List<String> argv) {
    return argv.subList(argv.size() - 4, argv.size());
  }

  @Test
  void aChatLaunchRefusesWhatItCannotPassOn() {
    for (var mode :
        List.of("-p", "--dangerously-skip-permissions", "a b", "plan;id", "", "m".repeat(65))) {
      assertEquals(
          "A chat's mode is a mode's name, such as 'default' or 'acceptEdits'.",
          assertThrows(
                  IllegalArgumentException.class, () -> new HarnessChat.Launch(mode, null, null))
              .getMessage(),
          mode);
    }
    assertEquals(
        "A chat's mode is a mode's name, such as 'default' or 'acceptEdits'.",
        assertThrows(IllegalArgumentException.class, () -> new HarnessChat.Launch(null, null, null))
            .getMessage());
    assertEquals(
        List.of("--permission-mode", "m".repeat(64)),
        tail(CLAUDE_CODE_CHAT.argv(new HarnessChat.Launch("m".repeat(64), null, null)))
            .subList(2, 4),
        "the longest mode's name it takes");
    assertEquals(
        MALFORMED_ID,
        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessChat.Launch("default", null, "-p; rm -rf /"))
            .getMessage());
    assertEquals(
        "A chat's model is a model's name, such as 'sonnet' or 'claude-sonnet-5-5'.",
        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessChat.Launch("default", "--dangerously-skip-permissions", null))
            .getMessage());
    assertEquals(
        List.of("--model", "claude-opus-5-5[1m]"),
        CLAUDE_CODE_CHAT
            .argv(new HarnessChat.Launch("default", "claude-opus-5-5[1m]", null))
            .subList(11, 13),
        "one argument, never a shell's pattern: the argv is run as a list");
  }

  @Test
  void aChatsModesAreSaidInWordsAPersonChoosesBy() {
    assertEquals(
        List.of("default=Asks first", "acceptEdits=Edits freely", "plan=Plans first"),
        CLAUDE_CODE_CHAT.modes().stream()
            .map(mode -> mode.name() + "=" + mode.label() + (mode.risky() ? ", risky" : ""))
            .toList());
    assertEquals(
        "Asks before it edits a file or runs a command, except commands it counts as safe, such as"
            + " sleep or echo, which run without asking.",
        CLAUDE_CODE_CHAT.modes().getFirst().about(),
        "never a claim that it asks where it was recorded not to"
            + " (LiveChatTest.everyModeWordIsARecordedDecision)");
    assertTrue(CLAUDE_CODE_CHAT.modes().stream().noneMatch(mode -> mode.about().isBlank()));
    assertTrue(
        CLAUDE_CODE.modes().stream().anyMatch(mode -> mode.name().equals("bypassPermissions")),
        "a terminal still offers it");
    assertEquals(Optional.empty(), CODEX.chat());
  }

  @Test
  void claudeCodeNamesWhatEachOfItsToolsDoes() {
    var kinds = new LinkedHashMap<String, ChatItem.ToolKind>();
    for (var tool :
        List.of(
            "Bash",
            "Edit",
            "Write",
            "NotebookEdit",
            "Read",
            "Grep",
            "Glob",
            "WebFetch",
            "WebSearch",
            "Agent",
            "Task",
            "mcp__github__create_issue",
            "Skill",
            "AskUserQuestion",
            "")) {
      kinds.put(tool, CLAUDE_CODE_CHAT.kind(tool));
    }

    assertEquals(
        "{Bash=COMMAND, Edit=EDIT, Write=EDIT, NotebookEdit=EDIT, Read=READ, Grep=SEARCH,"
            + " Glob=SEARCH, WebFetch=WEB, WebSearch=WEB, Agent=AGENT, Task=AGENT,"
            + " mcp__github__create_issue=MCP, Skill=OTHER, AskUserQuestion=OTHER, =OTHER}",
        kinds.toString());
  }

  @Test
  void claudeCodeSaysWhatACallWorksOnInNoNameOfItsOwn() {
    assertEquals(
        changed(ChatItem.ChangeKind.CHANGE, "/w/a.txt", "old", "new", false, false),
        CLAUDE_CODE_CHAT.detail(
            "Edit", Map.of("file_path", "/w/a.txt", "old_string", "old", "new_string", "new")));
    assertEquals(
        changed(ChatItem.ChangeKind.CHANGE, "/w/a.txt", "old", "new", true, false),
        CLAUDE_CODE_CHAT.detail(
            "Edit",
            Map.of(
                "file_path",
                "/w/a.txt",
                "old_string",
                "old",
                "new_string",
                "new",
                "replace_all",
                true)),
        "an edit of every occurrence says so");
    assertEquals(
        new Detail.Read("/w/a.txt", false),
        CLAUDE_CODE_CHAT.detail("Read", Map.of("file_path", "/w/a.txt")));
    assertEquals(
        new Detail.Search("TODO", "/w", false),
        CLAUDE_CODE_CHAT.detail("Grep", Map.of("pattern", "TODO", "path", "/w")));
    assertEquals(
        new Detail.Search("**/*.java", null, false),
        CLAUDE_CODE_CHAT.detail("Glob", Map.of("pattern", "**/*.java")));
    assertEquals(
        new Detail.Search("sail docs", null, false),
        CLAUDE_CODE_CHAT.detail("WebSearch", Map.of("query", "sail docs")));
    assertEquals(
        new Detail.Fetch("https://example.com", false),
        CLAUDE_CODE_CHAT.detail("WebFetch", Map.of("url", "https://example.com")));
    assertEquals(
        new Detail.Command("ls", false, false),
        CLAUDE_CODE_CHAT.detail("Bash", Map.of("command", "ls", "description", "List")),
        "a description only describes the call (02b:16)");
    assertEquals(
        new Detail.Command("sleep 30", true, false),
        CLAUDE_CODE_CHAT.detail("Bash", Map.of("command", "sleep 30", "run_in_background", true)),
        "a command that keeps running after the turn says so (04b:40)");
    assertEquals(
        new Detail.Command("sleep 25", false, false),
        CLAUDE_CODE_CHAT.detail("Bash", Map.of("command", "sleep 25", "run_in_background", false)));
    assertEquals(
        new Detail.Other("mcp__github__create_issue", "{\"title\": \"t\"}", false),
        CLAUDE_CODE_CHAT.detail("mcp__github__create_issue", Map.of("title", "t")));
    var longer = "z".repeat(LiveStream.CONTENT_CODE_POINTS + 1);
    assertEquals(
        changed(ChatItem.ChangeKind.CHANGE, "/w/a.txt", null, longer.substring(1), false, true),
        CLAUDE_CODE_CHAT.detail("Write", Map.of("file_path", "/w/a.txt", "content", longer)),
        "a file written whole has no text before, and what is shown is clipped and says so");
  }

  /**
   * A person is shown everything they allow: a call is drawn as its kind's detail only when its
   * input holds nothing but what that detail draws and what only describes it; any other field, or
   * a field of another type, draws the whole input as it is.
   */
  @Test
  void aCallWithAFieldItsDetailDoesNotDrawIsShownWhole() {
    var cases =
        List.of(
            List.<Object>of("Bash", Map.of("command", "ls", "dangerouslyDisableSandbox", true)),
            List.<Object>of("Bash", Map.of("command", List.of("rm", "-rf", "~"))),
            List.<Object>of("Bash", Map.of("command", "ls", "run_in_background", "yes")),
            List.<Object>of("Bash", Map.of("command", "ls", "timeout", 600000)),
            List.<Object>of("Edit", Map.of("file_path", "/w/a", "old_string", "a", "x", "y")),
            List.<Object>of("Write", Map.of("file_path", "/w/a", "content", 7)),
            List.<Object>of("Read", Map.of("file_path", "/w/a", "limit", 3)),
            List.<Object>of("Grep", Map.of("pattern", "TODO", "-i", true)),
            List.<Object>of("WebFetch", Map.of("url", "https://example.com", "prompt", "p")));
    for (var call : cases) {
      @SuppressWarnings("unchecked")
      var input = (Map<String, Object>) call.get(1);
      var tool = (String) call.get(0);
      assertEquals(
          new Detail.Other(tool, YamlUtil.dumpJsonValue(input), false),
          CLAUDE_CODE_CHAT.detail(tool, input),
          call.toString());
    }
  }

  private static Detail changed(
      ChatItem.ChangeKind kind,
      String path,
      String before,
      String after,
      boolean everywhere,
      boolean clipped) {
    return new Detail.Edit(
        List.of(new ChatItem.Change(kind, path, null, before, after, everywhere, clipped)));
  }

  private static List<ChatItem.Always> always(Object... suggestions) {
    return CLAUDE_CODE_CHAT.always(List.of(suggestions));
  }

  private static Map<String, Object> rule(String behavior, String destination, Object... rules) {
    return Map.of(
        "type",
        "addRules",
        "behavior",
        behavior,
        "destination",
        destination,
        "rules",
        List.of(rules));
  }

  private static Map<String, Object> bash(String content) {
    return Map.of("toolName", "Bash", "ruleContent", content);
  }

  private static Map<String, Object> folder(String path) {
    return Map.of("type", "addDirectories", "directories", List.of(path), "destination", "session");
  }

  private static final Map<String, Object> FOLDER = folder("/w");

  @Test
  void aRuleSaysWhatItAllowsAsClaudeCodeReadsIt() {
    var kept =
        ", from now on, for every Claude Code chat and terminal in this workspace (kept in the"
            + " workspace's .claude/settings.local.json).";
    var exact =
        ", also when another command such as timeout, nice or nohup starts it, and as part of a"
            + " longer line whose other commands need no asking"
            + kept;
    var prefix =
        ", also when another command such as xargs, timeout, nice or nohup starts it, xargs adding"
            + " whatever it is fed, and as part of a longer line whose other commands need no"
            + " asking"
            + kept;
    assertEquals(
        List.of(
            new ChatItem.Always(
                List.of(
                    "Allow this exact command: `npm test`" + exact,
                    "Allow any command that starts with `npm run`" + prefix,
                    "Allow any command that starts with `git log`" + prefix,
                    "Allow any command that starts with `ls`" + prefix))),
        always(
            rule(
                "allow",
                "localSettings",
                bash("npm test"),
                bash("npm run *"),
                bash("git log:*"),
                bash("ls*"))),
        "one sentence a rule: exact, or every command a trailing * lets start the same way, each"
            + " with what probe 2 recorded it covering besides: timeout, nice and nohup, a longer"
            + " line, and for a prefix xargs (01, 01b)");
    assertEquals(
        List.of(
            new ChatItem.Always(List.of("Let it work in the folder `/w`, for this session only."))),
        always(FOLDER),
        "a folder is quoted, so nothing in it reads as the sentence's own words");
    assertEquals(
        List.of(
            new ChatItem.Always(
                List.of(
                    "Let it work in the folder `/tmp/a, for this session only. Nothing else is"
                        + " allowed`, for this session only."))),
        always(folder("/tmp/a, for this session only. Nothing else is allowed")));
  }

  @Test
  void anAlwaysTooLongToShowWholeInAllIsNotOffered() {
    var half = "a".repeat(LiveStream.CONTENT_CODE_POINTS / 2);
    assertEquals(1, always(rule("allow", "localSettings", bash(half))).size(), "one fits");
    assertEquals(
        List.of(),
        always(rule("allow", "localSettings", bash(half), bash(half + "b"))),
        "together the sentences pass the bound a person is shown whole");
  }

  @Test
  void aModeSwitchSaysWhatTheModeAllowsAndThatItStaysForTheChat() {
    var edits = Harness.Mode.named(CLAUDE_CODE_CHAT.modes(), "acceptEdits", "claude-code");
    assertEquals(
        "Edits files, and runs file commands such as rm, mv, cp and sed, inside this workspace"
            + " and any folder you let it work in, without asking; it still asks before such a"
            + " command anywhere else.",
        edits.about(),
        "the mode's own words say only what was recorded (LiveChatTest"
            + ".everyModeWordIsARecordedDecision), and the folders its documentation adds");
    assertEquals(
        List.of(
            new ChatItem.Always(
                List.of(
                    "Switch this chat to Edits freely: "
                        + edits.about()
                        + " It stays for the rest of this chat."))),
        always(Map.of("type", "setMode", "mode", "acceptEdits", "destination", "session")),
        "a resumed chat starts in the mode in force, so a switch outlives the process");
  }

  @Test
  void anAlwaysSailCannotSayInPlainWordsIsNotOffered() {
    var unsaid =
        List.of(
            Map.entry(
                "a deny rule never reads as an allow",
                (Object) rule("deny", "localSettings", bash("rm *"))),
            Map.entry("an ask rule", (Object) rule("ask", "localSettings", bash("ls"))),
            Map.entry(
                "a * that is not last",
                (Object) rule("allow", "localSettings", bash("git * main"))),
            Map.entry("a * alone", (Object) rule("allow", "localSettings", bash("*"))),
            Map.entry("a rule kept for the session", (Object) rule("allow", "session", bash("ls"))),
            Map.entry(
                "a folder kept from now on",
                (Object)
                    Map.of(
                        "type",
                        "addDirectories",
                        "directories",
                        List.of("/w"),
                        "destination",
                        "localSettings")),
            Map.entry("two *", (Object) rule("allow", "localSettings", bash("a * b *"))),
            Map.entry(
                "another tool's rule",
                (Object)
                    rule(
                        "allow",
                        "localSettings",
                        Map.of("toolName", "Read", "ruleContent", "/w/a.txt"))),
            Map.entry(
                "a tool's every call",
                (Object) rule("allow", "localSettings", Map.of("toolName", "Bash"))),
            Map.entry("a blank rule", (Object) rule("allow", "localSettings", bash(" "))),
            Map.entry("no rule", (Object) rule("allow", "localSettings")),
            Map.entry("projectSettings", (Object) rule("allow", "projectSettings", bash("ls"))),
            Map.entry("userSettings", (Object) rule("allow", "userSettings", bash("ls"))),
            Map.entry(
                "no destination",
                (Object)
                    Map.of("type", "addRules", "behavior", "allow", "rules", List.of(bash("ls")))),
            Map.entry(
                "no folder",
                (Object)
                    Map.of(
                        "type",
                        "addDirectories",
                        "directories",
                        List.of(),
                        "destination",
                        "session")),
            Map.entry(
                "a blank folder",
                (Object)
                    Map.of(
                        "type",
                        "addDirectories",
                        "directories",
                        List.of(""),
                        "destination",
                        "session")),
            Map.entry(
                "a mode no chat offers",
                (Object)
                    Map.of(
                        "type", "setMode", "mode", "bypassPermissions", "destination", "session")),
            Map.entry(
                "a mode kept from now on",
                (Object)
                    Map.of(
                        "type", "setMode", "mode", "acceptEdits", "destination", "localSettings")),
            Map.entry(
                "another type",
                (Object)
                    Map.of(
                        "type",
                        "replaceRules",
                        "behavior",
                        "allow",
                        "destination",
                        "session",
                        "rules",
                        List.of(bash("ls")))),
            Map.entry("no object", (Object) "neither"),
            Map.entry(
                "a field Sail does not know on a suggestion",
                (Object)
                    Map.of(
                        "type",
                        "addRules",
                        "behavior",
                        "allow",
                        "destination",
                        "localSettings",
                        "rules",
                        List.of(bash("ls")),
                        "scope",
                        "everywhere")),
            Map.entry(
                "a field Sail does not know on a rule",
                (Object)
                    rule(
                        "allow",
                        "localSettings",
                        Map.of("toolName", "Bash", "ruleContent", "ls", "negate", true))),
            Map.entry(
                "rules on a mode",
                (Object)
                    Map.of(
                        "type",
                        "setMode",
                        "mode",
                        "acceptEdits",
                        "destination",
                        "session",
                        "rules",
                        List.of(bash("rm *")))),
            Map.entry(
                "a field Sail does not know on a folder",
                (Object)
                    Map.of(
                        "type",
                        "addDirectories",
                        "directories",
                        List.of("/w"),
                        "destination",
                        "session",
                        "recursive",
                        true)),
            Map.entry(
                "a folder that reads on as the sentence",
                (Object) folder("/tmp/a`, for this session only. Nothing else is allowed")),
            Map.entry("a folder that climbs out", (Object) folder("/home/dev/workspace/../..")),
            Map.entry("a folder with a line break", (Object) folder("/w\nAllowed: everything")),
            Map.entry("a folder turned right to left", (Object) folder("/w/\u202Etxt.exe")),
            Map.entry(
                "a rule that closes its own quote",
                (Object) rule("allow", "localSettings", bash("ls` and rm -rf ~ `"))),
            Map.entry(
                "a rule turned right to left",
                (Object) rule("allow", "localSettings", bash("cat \u202Etxt.hs"))),
            Map.entry(
                "a rule with a hidden mark",
                (Object) rule("allow", "localSettings", bash("ls\u200B"))),
            Map.entry(
                "a rule with a line separator",
                (Object) rule("allow", "localSettings", bash("ls\u2028rm -rf ~"))),
            Map.entry(
                "a rule that climbs out",
                (Object) rule("allow", "localSettings", bash("cat ../secrets"))),
            Map.entry(
                "a rule xargs runs, whose reach is not recorded",
                (Object) rule("allow", "localSettings", bash("xargs touch"))),
            Map.entry(
                "a rule xargs runs, with its own arguments",
                (Object) rule("allow", "localSettings", bash("xargs  touch probe-a.txt"))),
            Map.entry(
                "a prefix rule xargs runs",
                (Object) rule("allow", "localSettings", bash("xargs rm *"))),
            Map.entry(
                "a rule timeout starts xargs in",
                (Object) rule("allow", "localSettings", bash("timeout 5 xargs touch"))),
            Map.entry(
                "a rule nice starts xargs in",
                (Object) rule("allow", "localSettings", bash("nice xargs rm"))),
            Map.entry(
                "a rule that runs xargs by its path",
                (Object) rule("allow", "localSettings", bash("/usr/bin/xargs rm"))),
            Map.entry(
                "a rule env starts xargs in",
                (Object) rule("allow", "localSettings", bash("env xargs rm"))),
            Map.entry(
                "a rule command starts xargs in",
                (Object) rule("allow", "localSettings", bash("command xargs rm"))),
            Map.entry(
                "a compound rule with &",
                (Object) rule("allow", "localSettings", bash("touch a & rm b"))),
            Map.entry(
                "a compound rule with $(…)",
                (Object) rule("allow", "localSettings", bash("echo $(cat ~/.ssh/id_rsa)"))),
            Map.entry("a folder a combining mark draws over", (Object) folder("/tmp/a\u0338")),
            Map.entry(
                "a rule with a private-use character",
                (Object) rule("allow", "localSettings", bash("ls \uE000"))),
            Map.entry(
                "a rule with a lone surrogate",
                (Object) rule("allow", "localSettings", bash("ls \uD800"))),
            Map.entry(
                "a rule with an unassigned character",
                (Object) rule("allow", "localSettings", bash("ls \u0378"))),
            Map.entry(
                "a compound rule with &&",
                (Object) rule("allow", "localSettings", bash("make && make install"))),
            Map.entry(
                "a compound rule with ||",
                (Object) rule("allow", "localSettings", bash("test -f a || touch a"))),
            Map.entry(
                "a compound rule with ;",
                (Object) rule("allow", "localSettings", bash("cd w; ls"))),
            Map.entry(
                "a compound rule with a pipe",
                (Object) rule("allow", "localSettings", bash("ls | head *"))),
            Map.entry(
                "a compound rule over two lines",
                (Object) rule("allow", "localSettings", bash("ls\nrm -rf ~"))),
            Map.entry(
                "a rule longer than a person is shown",
                (Object)
                    rule(
                        "allow",
                        "localSettings",
                        bash("x".repeat(LiveStream.CONTENT_CODE_POINTS) + " *"))));
    for (var suggestion : unsaid) {
      assertEquals(List.of(), always(suggestion.getValue()), suggestion.getKey());
      assertEquals(
          List.of(),
          always(FOLDER, suggestion.getValue()),
          suggestion.getKey() + ": every suggestion is kept together, so none is offered");
    }
    assertEquals(List.of(), always(), "nothing suggested");
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
