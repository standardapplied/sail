/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ChatItem.Asked;
import ai.singlr.sail.engine.ChatItem.Choice;
import ai.singlr.sail.engine.ChatItem.Detail;
import ai.singlr.sail.engine.ChatItem.ToolKind;
import ai.singlr.sail.engine.ChatItem.TurnEnded;
import ai.singlr.sail.engine.LiveStream.Image;
import ai.singlr.sail.engine.LiveStream.Step;
import ai.singlr.sail.harness.HarnessChat;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.harness.LoginOutcome;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A chat's side of Claude Code's live stream, against the S0 probe's recordings ({@link Recorded}):
 * what a person is shown of each step, and every line a chat writes, equal to the line Claude Code
 * 2.1.332 was written and acted on.
 */
class LiveChatTest {

  private static final HarnessChat CLAUDE_CODE = Harnesses.of("claude-code").chat().orElseThrow();

  private static final String KEPT =
      ", from now on, for every Claude Code chat and terminal in this workspace (kept in the"
          + " workspace's .claude/settings.local.json).";

  private static final String EXACT =
      ", also when another command such as timeout, nice or nohup starts it, and as part of a"
          + " longer line whose other commands need no asking"
          + KEPT;

  private static final String PREFIX =
      ", except one that writes to a file with `>`, also when another command such as xargs, timeout, nice or nohup starts it, xargs adding"
          + " whatever it is fed, and as part of a longer line whose other commands need no asking"
          + KEPT;

  private static final String FOLDER =
      "Let it work in the folder `/home/dev/workspace`, for this session only.";

  private static final String EDITS_FREELY =
      "Switch this chat to Edits freely: Edits files, and runs file commands such as rm, mv, cp"
          + " and sed, inside this workspace and any folder you let it work in, without asking; it"
          + " still asks before such a command anywhere else. It stays for the rest of this chat.";

  private static Detail written(String path, String text) {
    return new Detail.Edit(
        List.of(
            new ChatItem.Change(ChatItem.ChangeKind.CHANGE, path, null, null, text, false, false)));
  }

  private static List<ChatItem> shown(String recording, int line) {
    return LiveChat.items(LiveStream.read(Recorded.line(recording, line)), CLAUDE_CODE);
  }

  private static <T extends Step> T step(Class<T> type, String recording, int line) {
    return type.cast(LiveStream.read(Recorded.line(recording, line)));
  }

  private static void assertWritten(String recording, int line, String written) {
    assertEquals(Recorded.parsed(recording, line), Recorded.parsed(written));
  }

  @Test
  void theAgentsTextIsShownAsItIsWrittenInParagraphs() {
    assertEquals(
        List.of(new ChatItem.Text(null, "", true, false)), shown("01-baseline-live-flags", 20));
    assertEquals(
        List.of(new ChatItem.Text(null, "done", false, false)),
        shown("01-baseline-live-flags", 21));
  }

  @Test
  void aToolCallIsARowOfItsKindInPlainWords() {
    assertEquals(
        List.of(
            new ChatItem.Tool(
                null,
                "toolu_011VvCwXcvkemp321CMEyur6",
                ToolKind.COMMAND,
                "Print probe-ok",
                new Detail.Command("echo probe-ok", false, false),
                false)),
        shown("01-baseline-live-flags", 13));
    assertEquals(
        List.of(
            new ChatItem.Tool(
                null,
                "toolu_01GQbMEZJcBzatfHNojTS8E8",
                ToolKind.EDIT,
                "/home/dev/workspace/notes.txt",
                written("/home/dev/workspace/notes.txt", "hi\n"),
                false)),
        shown("02b-relay-stdio", 42),
        "a file written whole has no text before");
    assertEquals(
        List.of(
            new ChatItem.Tool(
                null,
                "toolu_01TbR5tat9n6LS2N65WNpEcZ",
                ToolKind.AGENT,
                "Run echo sub-ok",
                new Detail.Other(
                    "Agent",
                    "{\"description\": \"Run echo sub-ok\", \"subagent_type\": \"general-purpose\","
                        + " \"prompt\": \"Run the shell command `echo sub-ok` using the Bash tool."
                        + " Then report the output in one sentence.\", \"run_in_background\": false}",
                    false),
                false)),
        shown("05b-subagent", 28));
    assertEquals(
        List.of(
            new ChatItem.ToolDone(
                null, "toolu_011VvCwXcvkemp321CMEyur6", "probe-ok", false, false)),
        shown("01-baseline-live-flags", 17));
    assertEquals(
        List.of(
            new ChatItem.ToolDone(
                null,
                "toolu_01GQbMEZJcBzatfHNojTS8E8",
                "The person declined this edit.",
                true,
                false)),
        shown("02b-relay-stdio", 48));
  }

  @Test
  void aToolCallNamingNoDescriptionIsShownByItsFirstArgument() {
    assertEquals(
        List.of(
            new ChatItem.Tool(
                null,
                "t1",
                ToolKind.READ,
                "/home/dev/workspace/a.txt",
                new Detail.Read("/home/dev/workspace/a.txt", false),
                false)),
        LiveChat.items(
            new Step.ToolCalls(
                List.of(
                    new LiveStream.ToolCall(
                        "t1",
                        "Read",
                        "/home/dev/workspace/a.txt",
                        Map.of("file_path", "/home/dev/workspace/a.txt")))),
            CLAUDE_CODE));
  }

  @Test
  void aSubagentsItemsAreShownUnderItsRow() {
    var call = "toolu_01TbR5tat9n6LS2N65WNpEcZ";

    assertEquals(
        List.of(
            new ChatItem.Tool(
                call,
                "toolu_013wKQqCQd4JL5cPCj24T27x",
                ToolKind.COMMAND,
                "Print sub-ok via echo",
                new Detail.Command("echo sub-ok", false, false),
                false)),
        shown("05b-subagent", 35));
    assertEquals(
        List.of(
            new ChatItem.ToolDone(call, "toolu_013wKQqCQd4JL5cPCj24T27x", "sub-ok", false, false)),
        shown("05b-subagent", 36));
    assertEquals(List.of(), shown("05b-subagent", 32));
    assertEquals(
        List.of(
            new ChatItem.Text(
                "toolu_011gGRQDib3ZHFWutXd9HSGT",
                "The command `echo sub-ok` printed \"sub-ok\".",
                true,
                false)),
        shown("05c-subagent-forward-text", 50));
  }

  @Test
  void aPermissionRequestIsShownInPlainWordsWithWhyAndTheDetailBesideThem() {
    assertEquals(
        List.of(
            new ChatItem.Permission(
                "a871f652-0656-42bf-8cc8-860ad0a34b79",
                "toolu_01C2VMe9ubaU1aFMnnUS5nxC",
                null,
                ToolKind.COMMAND,
                "Write current date into stamp.txt",
                null,
                new Detail.Command("date > stamp.txt", false, false),
                List.of(),
                false)),
        shown("02b-relay-stdio", 16));
    assertEquals(
        List.of(
            new ChatItem.Permission(
                "79327ade-fc36-4737-926f-eb6dde76e6df",
                "toolu_01GQbMEZJcBzatfHNojTS8E8",
                null,
                ToolKind.EDIT,
                "notes.txt",
                null,
                written("/home/dev/workspace/notes.txt", "hi\n"),
                List.of(new ChatItem.Always(List.of(EDITS_FREELY))),
                false)),
        shown("02b-relay-stdio", 44));
    assertEquals(
        "af6598c6aac9ae006",
        ((ChatItem.Permission) shown("04a-subagent-permission-request", 45).getFirst()).agent(),
        "a subagent's request names the agent asking");
    var reasoned = (ChatItem.Permission) shown("04d-interrupt-while-tool-runs", 20).getFirst();
    assertEquals("This command requires approval", reasoned.reason());
    assertEquals(
        List.of(
            new ChatItem.Permission(
                "r1",
                "t1",
                null,
                ToolKind.COMMAND,
                "Bash",
                null,
                new Detail.Command("ls", false, false),
                List.of(),
                false)),
        LiveChat.items(
            new Step.Permission(
                "r1", "t1", null, "Bash", "Bash", Map.of("command", "ls"), null, null, List.of()),
            CLAUDE_CODE),
        "with no description it is named by the tool, and with no suggestion never always");
  }

  /**
   * Every "always" Claude Code 2.1.332 offered in the S0 probes, in the words a person reads, each
   * rule with what probe 2 recorded it also covers (01, 01b): none for a compound command, a rule
   * Claude Code matches one command at a time, so it may never match again (03b:91's {@code &&},
   * 04d:20's {@code ;}, which is inside quotes but read as written, and 01:198); none for a rule
   * run by xargs (01:108, 01:260), whose reach is not recorded; none for a command that redirects,
   * whatever rule Claude Code suggests for it: exact (02b:16, 02d:17 and 04a:16's {@code date > …})
   * or a prefix with no {@code >} (02b:72's {@code echo original *}, probe 5's 04:17 and 05:16),
   * since Claude Code asked again for such a command with either kept (probes 6 and 7); and none
   * for a rule of another tool kept for the session (03a:13).
   */
  @Test
  void everyRecordedAlwaysIsSaidInPlainWords() {
    var recorded = new LinkedHashMap<String, List<List<String>>>();
    for (var line :
        List.of(
            "02b-relay-stdio:16",
            "02b-relay-stdio:44",
            "02b-relay-stdio:72",
            "02b-relay-stdio:99",
            "02d-relay-stdio-allow-without-input:17",
            "03b-plan-exit-plan-mode:91",
            "04a-cancel-request-by-interrupt:16",
            "04b-set-permission-mode-and-background-task:132",
            "04d-interrupt-while-tool-runs:20",
            "01-always-rule-wrappers:18",
            "01-always-rule-wrappers:108",
            "01-always-rule-wrappers:198",
            "01-always-rule-wrappers:260",
            "03a-read-deny-abs:13",
            "04a-subagent-permission-request:45",
            "04b-accept-edits-shell:160",
            "01c-mode-manual-over-workspace-acceptEdits:40",
            "01c-mode-manual-over-workspace-acceptEdits:68",
            "04-answer-after-withdrawn:17",
            "05-answer-twice:16")) {
      var at = line.split(":");
      recorded.put(line, always(at[0], Integer.parseInt(at[1])));
    }

    assertEquals(
        List.of(
            List.of(),
            List.of(List.of(EDITS_FREELY)),
            List.of(),
            List.of(
                List.of(
                    "Allow this exact command: `touch always.txt`" + EXACT, FOLDER, EDITS_FREELY)),
            List.of(),
            List.of(),
            List.of(),
            List.of(List.of(EDITS_FREELY)),
            List.of(),
            List.of(
                List.of(
                    "Allow this exact command: `touch probe-a.txt`" + EXACT, FOLDER, EDITS_FREELY)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(
                List.of(
                    "Allow this exact command: `touch sub-probe.txt`" + EXACT,
                    FOLDER,
                    EDITS_FREELY)),
            List.of(
                List.of(
                    "Allow this exact command: `rm /home/dev/scratch-outside.txt`" + EXACT,
                    "Let it work in the folder `/home/dev`, for this session only.")),
            List.of(List.of(EDITS_FREELY)),
            List.of(
                List.of(
                    "Allow this exact command: `touch probe-m.txt`" + EXACT, FOLDER, EDITS_FREELY)),
            List.of(),
            List.of()),
        List.copyOf(recorded.values()),
        recorded.keySet().toString());
  }

  /**
   * Probes 6 and 7 (Claude Code 2.1.332, fleet boxes): a command that writes through {@code >} was
   * asked for again with a kept rule that allowed it, exact (probe 6) or a prefix with no {@code >}
   * that Claude Code itself suggested for it ({@code printf pfx *}, probe 7 01:46, 01:105, 01:144),
   * in that process and in a fresh one that read the rule at start. So a Bash request whose command
   * holds {@code >} or {@code <} anywhere, inside quotes too, is offered no always whatever rule is
   * suggested; and a rule whose own text redirects is offered none either, whatever the command.
   */
  @Test
  void aCommandThatRedirectsIsOfferedNoAlwaysWhateverRuleIsSuggested() {
    var asked = new LinkedHashMap<String, List<List<String>>>();
    for (var pair :
        List.of(
            List.of("printf fleet-%s 7341 > always.txt", "printf fleet-%s 7341 > always.txt"),
            List.of("printf pfx 0 > pfx0.txt", "printf pfx *"),
            List.of("printf twice > twice.txt", "printf twice *"),
            List.of("printf fleet-%s 7341 >> always.txt", "printf fleet-%s *"),
            List.of("make 2> errors.txt", "make *"),
            List.of("sort < names.txt", "sort *"),
            List.of("echo '>'", "echo *"),
            List.of("make", "make 2> errors.txt"),
            List.of("sort", "sort < names.txt"))) {
      asked.put(pair.toString(), offered(pair.getFirst(), pair.getLast()));
    }

    assertEquals(Collections.nCopies(9, List.of()), List.copyOf(asked.values()), asked.toString());
  }

  /**
   * What a prefix rule holds for, so nothing that holds is withheld: probe 7 kept {@code touch *}
   * from a redirecting request's always, and Claude Code then asked nothing for {@code touch
   * pfx-1.txt} (03:88), which it had asked for with no rule (03:17), in that process and in a fresh
   * one (04:20), but asked again for {@code touch pfx-2.txt > pfx-r2.txt} (03:115, 04:49): the card
   * says so.
   */
  @Test
  void
      aCommandThatDoesNotRedirectIsOfferedAPrefixRuleSayingItStillAsksBeforeAWriteThroughARedirect() {
    assertEquals(
        List.of(List.of("Allow any command that starts with `touch`" + PREFIX, FOLDER)),
        offered("touch pfx-1.txt", "touch *"));
  }

  private static List<List<String>> offered(String command, String rule) {
    var suggestions =
        List.<Object>of(
            Map.of(
                "type",
                "addRules",
                "rules",
                List.of(Map.of("toolName", "Bash", "ruleContent", rule)),
                "behavior",
                "allow",
                "destination",
                "localSettings"),
            Map.of(
                "type",
                "addDirectories",
                "directories",
                List.of("/home/dev/workspace"),
                "destination",
                "session"));
    var shown =
        LiveChat.items(
            new Step.Permission(
                "607188f7-c248-4fd4-9873-1e3b542545d5",
                "toolu_018juR1jrmMSQWorGP9ZfQBU",
                null,
                "Bash",
                "Bash",
                Map.of("command", command),
                null,
                null,
                suggestions),
            CLAUDE_CODE);
    return ((ChatItem.Permission) shown.getFirst())
        .always().stream().map(ChatItem.Always::words).toList();
  }

  private static List<List<String>> always(String recording, int line) {
    return ((ChatItem.Permission) shown(recording, line).getFirst())
        .always().stream().map(ChatItem.Always::words).toList();
  }

  @Test
  void aQuestionAPlanAndAWithdrawalAreShownAsRequests() {
    assertEquals(
        List.of(
            new ChatItem.Question(
                "d4d9b320-e2f7-497b-8037-5563770aed16",
                "toolu_01P2oh1RhKTq1npuEiNF3yrK",
                List.of(
                    new Asked(
                        "Which color?",
                        "Color",
                        List.of(
                            new Choice("Red", "Choose red", false),
                            new Choice("Blue", "Choose blue", false)),
                        false,
                        false)))),
        shown("03a-ask-user-question", 21));
    assertEquals(
        List.of(
            new ChatItem.Plan(
                "1164cc67-5e3b-4c52-9696-57d3610fcb0e",
                "toolu_01H6Ej9TxsJDvVS7r7Zex4dC",
                "Create /home/dev/workspace/hello.txt containing \"hi\".\n",
                false)),
        shown("03b-plan-exit-plan-mode", 28));
    assertEquals(
        List.of(new ChatItem.Withdrawn("dc0b76a9-43c6-4484-a47b-1b7c1e5a4b10")),
        shown("04a-cancel-request-by-interrupt", 20));
  }

  @Test
  void aRetryAndACompactionAreNoticesAndAModeChangeIsShown() {
    assertEquals(
        List.of(new ChatItem.Retrying(1, 2, 513, "overloaded", false)), shown("05e-api-retry", 5));
    assertEquals(
        List.of(new ChatItem.Compacted("manual", 18074, 3069, false)), shown("05d-compact", 17));
    assertEquals(
        List.of(new ChatItem.ModeChanged("acceptEdits", false)), shown("02b-relay-stdio", 103));
    assertEquals(List.of(), shown("01-baseline-live-flags", 4));
    assertEquals(List.of(), shown("05d-compact", 14));
  }

  @Test
  void eachTurnSaysItsSession() {
    var init = step(Step.Init.class, "04b-set-permission-mode-and-background-task", 148);

    assertEquals(
        List.of(
            new ChatItem.Session(
                "claude-haiku-5-5", "default", init.commands(), init.skills(), false)),
        shown("04b-set-permission-mode-and-background-task", 148));
  }

  @Test
  void aTurnEndsWithWhatItAloneCostAndHowItEnded() {
    assertEquals(
        List.of(new TurnEnded(0.000301485, 23708, 86, 940)), shown("02b-relay-stdio", 138));
    assertEquals(
        List.of(new ChatItem.Interrupted(), new TurnEnded(0.000268005, 11014, 81, 2849)),
        shown("04a-cancel-request-by-interrupt", 24));
    assertEquals(
        List.of(new ChatItem.SelfStarted(), new TurnEnded(0.00017991, 12746, 20, 1147)),
        shown("04b-set-permission-mode-and-background-task", 159));
  }

  @Test
  void aFailedTurnIsShownInTheHarnessesWordsWithWhatCameOfItsLogin() {
    assertEquals(
        List.of(
            new ChatItem.Failed(
                "Invalid API key · Fix external API key", new LoginOutcome.Refused(), false),
            new TurnEnded(null, 0, 0, 286)),
        shown("06a-invalid-key", 6));
    assertEquals(
        List.of(
            new ChatItem.Failed(
                "Not logged in · Please run /login", new LoginOutcome.Undelivered(), false),
            new TurnEnded(null, 0, 0, 182)),
        shown("06b-no-credential", 6));
    assertEquals(
        List.of(
            new ChatItem.Failed(
                "API Error: 529 Overloaded. This is a server-side issue, usually temporary — try"
                    + " again in a moment. If it persists, check your inference gateway"
                    + " (127.0.0.1:18529).",
                null,
                false),
            new TurnEnded(null, 0, 0, 1935)),
        shown("05e-api-retry", 8));
    assertEquals(
        List.of(new ChatItem.Failed("error_max_turns", null, false), new TurnEnded(null, 0, 0, 0)),
        LiveChat.items(
            LiveStream.read(
                "{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true}"),
            CLAUDE_CODE),
        "a failure with no words of its own is named by its reason");
  }

  /**
   * Every claim a chat mode's words make, against the recorded decision it rests on. Asks first
   * (init {@code default}) asks before edits (a Write asked) and commands ({@code date} asked),
   * except safe ones ({@code sleep} and {@code echo} ran without asking). Edits freely (init {@code
   * acceptEdits}) changes files in this workspace without asking (a Write, {@code rm}, {@code mv},
   * {@code cp}, {@code sed -i} and {@code nice rm} inside it ran unasked), and claims nothing of
   * elsewhere, where {@code rm} asked. Plans first's words rest on no decision here.
   */
  @Test
  void everyModeWordIsARecordedDecision() {
    assertEquals(
        List.of(
            "default: Asks before edits and commands, except safe ones.",
            "acceptEdits: Changes files in this workspace without asking."),
        CLAUDE_CODE.modes().stream()
            .filter(mode -> !mode.name().equals("plan"))
            .map(mode -> mode.name() + ": " + mode.about())
            .toList(),
        "a word changed is a claim to check against the decisions below");
    assertEquals(
        List.of(
            "default: Write /home/dev/workspace/notes.txt asked",
            "default: date > stamp.txt asked",
            "default: sleep 30 ran unasked",
            "default: touch probe-a.txt && echo done ran unasked",
            "acceptEdits: Write /home/dev/workspace/accept.txt ran unasked",
            "acceptEdits: rm scratch-rm.txt ran unasked",
            "acceptEdits: mv scratch-mv.txt scratch-moved.txt ran unasked",
            "acceptEdits: cp scratch-cp.txt scratch-copied.txt ran unasked",
            "acceptEdits: sed -i s/one/two/ scratch-sed.txt ran unasked",
            "acceptEdits: nice rm scratch-rm2.txt ran unasked",
            "acceptEdits: rm /home/dev/scratch-outside.txt asked"),
        List.of(
            decided("02b-relay-stdio", 3, 42, 44),
            decided("02b-relay-stdio", 3, 14, 16),
            decided("04b-set-permission-mode-and-background-task", 3, 40, 46),
            decided("01-always-rule-wrappers", 333, 344, 348),
            decided("04b-set-permission-mode-and-background-task", 81, 99, 103),
            decided("04b-accept-edits-shell", 3, 14, 18),
            decided("04b-accept-edits-shell", 3, 43, 47),
            decided("04b-accept-edits-shell", 3, 72, 76),
            decided("04b-accept-edits-shell", 3, 102, 106),
            decided("04b-accept-edits-shell", 3, 130, 134),
            decided("04b-accept-edits-shell", 146, 158, 160)));
  }

  /**
   * What a recording says of one call: the mode its turn's {@code init} names, the call (its
   * command, or its tool and file), and whether it asked (a request on {@code decision}) or ran
   * with no request (a result Claude Code decided itself, by its configuration, on {@code
   * decision}).
   */
  @SuppressWarnings("unchecked")
  private static String decided(String recording, int init, int call, int decision) {
    var mode = step(Step.Init.class, recording, init).mode();
    var made = step(Step.ToolCalls.class, recording, call).calls().getFirst();
    var what =
        made.input().get("command") instanceof String command
            ? command
            : made.tool() + " " + made.input().get("file_path");
    var line = LiveStream.read(Recorded.line(recording, decision));
    if (line instanceof Step.Permission asked) {
      assertEquals(made.id(), asked.callId());
      return mode + ": " + what + " asked";
    }
    var meta =
        ((List<Map<String, Object>>) Recorded.parsed(recording, decision).get("tool_result_meta"))
            .getFirst();
    assertEquals(made.id(), meta.get("id"));
    var verdict = (Map<String, Object>) meta.get("permission_decision");
    assertEquals(
        List.of("accept", "config"), List.of(verdict.get("decision"), verdict.get("source")));
    return mode + ": " + what + " ran unasked";
  }

  /**
   * A read the chat's settings deny never reaches a person: the model is told so in a failed result
   * (probe 2's 03b:14, under {@code Read(//…)}).
   */
  @Test
  void aDeniedReadIsAFailedRowAndNoRequest() {
    assertEquals(
        List.of(
            new ChatItem.ToolDone(
                null,
                "toolu_01GfcXrasMMfCP65eM4YKhoQ",
                "<tool_use_error>File is in a directory that is denied by your permission"
                    + " settings.</tool_use_error>",
                true,
                false)),
        shown("03b-read-deny-double-slash", 14));
  }

  /**
   * Each way probe 3 recorded Claude Code refusing a call itself is a failed row, and its {@code
   * system/permission_denied} line, when there is one, shows nothing more: a Read a rule denies
   * comes with none (R, 03a-ro-dbl-ssh-Read:14); a {@code cat} the same rule denies (B,
   * 03a-ro-dbl-ssh-cat:26-27) and a call with no one to ask (N, 03c-ro-single-ssh-Read:14-15) come
   * with one.
   */
  @Test
  void eachRefusalClaudeCodeMakesItselfIsAFailedRow() {
    assertEquals(
        List.of(
            new Step.Other("system/permission_denied"), new Step.Other("system/permission_denied")),
        List.of(
            LiveStream.read(Recorded.line("03a-ro-dbl-ssh-cat", 26)),
            LiveStream.read(Recorded.line("03c-ro-single-ssh-Read", 14))));
    assertEquals(List.of(), shown("03a-ro-dbl-ssh-cat", 26));
    assertEquals(List.of(), shown("03c-ro-single-ssh-Read", 14));
    assertEquals(
        List.of(
            List.of(
                new ChatItem.ToolDone(
                    null,
                    "toolu_01YT47LXdLKqP7mATs42wAXj",
                    "<tool_use_error>File is in a directory that is denied by your permission"
                        + " settings.</tool_use_error>",
                    true,
                    false)),
            List.of(
                new ChatItem.ToolDone(
                    null,
                    "toolu_0151mafKMWdhmgpUTmPJL65d",
                    "Permission to use Bash with command cat /home/dev/.ssh/probe-secret.txt has"
                        + " been denied.",
                    true,
                    false))),
        List.of(shown("03a-ro-dbl-ssh-Read", 14), shown("03a-ro-dbl-ssh-cat", 27)));
    assertEquals(
        List.of(
            new ChatItem.ToolDone(
                null,
                "toolu_01RRsFyFXe9qk2tuwBpNKyfX",
                (String) Recorded.parsed("03c-ro-single-ssh-Read", 14).get("message"),
                true,
                false)),
        shown("03c-ro-single-ssh-Read", 15),
        "the result says what its permission_denied line said");
  }

  /**
   * Probe 4 recorded two more ways a deny rule refuses a call, each with a {@code
   * system/permission_denied} line of reason {@code rule} before its result: a Read through a
   * symlink to a denied file (L, 13-symlink-file-Read-full:46-48) and a {@code cat} of a relative
   * path after {@code cd} into a denied directory (C, 16-cd-relative-cat-full:26-27). Each is a
   * failed row, and its {@code permission_denied} line shows nothing more.
   */
  @Test
  void aRuleThatSaysPermissionDeniedBeforeItsResultIsAFailedRow() {
    assertEquals(
        List.of(
            new Step.Other("system/permission_denied"), new Step.Other("system/permission_denied")),
        List.of(
            LiveStream.read(Recorded.line("13-symlink-file-Read-full", 46)),
            LiveStream.read(Recorded.line("16-cd-relative-cat-full", 26))));
    assertEquals(List.of(), shown("13-symlink-file-Read-full", 46));
    assertEquals(List.of(), shown("16-cd-relative-cat-full", 26));
    assertEquals(
        List.of(
            List.of(
                new ChatItem.ToolDone(
                    null,
                    "toolu_01A7XBpoyZsMwrFxHgWyGPAr",
                    "Permission to read /home/dev/workspace/link.txt has been denied.",
                    true,
                    false)),
            List.of(
                new ChatItem.ToolDone(
                    null,
                    "toolu_019GS8cW4vBPBaqm81GX3tbs",
                    "cat from '/home/dev/.ssh/probe-secret.txt' was blocked by a deny rule.",
                    true,
                    false))),
        List.of(shown("13-symlink-file-Read-full", 48), shown("16-cd-relative-cat-full", 27)));
  }

  @Test
  void whatThePersonIsNotShownGivesNoItem() {
    assertEquals(List.of(), shown("01-baseline-live-flags", 8));
    assertEquals(List.of(), shown("04d-interrupt-while-tool-runs", 24));
    assertEquals(List.of(), shown("04a-cancel-request-by-interrupt", 21));
    assertEquals(List.of(), shown("04b-set-permission-mode-and-background-task", 78));
  }

  @Test
  void aRefusalOfTheHostsRequestIsShown() {
    assertEquals(
        List.of(new ChatItem.Refused("mode-2", "Cannot set permission mode", false)),
        LiveChat.items(
            LiveStream.read(
                "{\"type\":\"control_response\",\"response\":{\"subtype\":\"error\","
                    + "\"request_id\":\"mode-2\",\"error\":\"Cannot set permission mode\"}}"),
            CLAUDE_CODE));
  }

  @Test
  void aMessageIsTheRecordedLineWithItsImagesBeforeItsText() {
    assertWritten(
        "02b-relay-stdio",
        2,
        LiveStream.userMessage(
            "Use the Bash tool to run exactly: date > stamp.txt   Then reply with just: done"));
    assertWritten(
        "05a-image-block",
        2,
        LiveStream.userMessage(
            "What color is this image? Answer with one word.",
            new Image(
                "image/png",
                "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAFklEQVR4nGO4IyJCEmIY1TCqYfhqAAAC"
                    + "cQQQFwFJQgAAAABJRU5ErkJggg==")));
  }

  @Test
  void anImageIsOneTheModelReads() {
    assertEquals(
        "An image is image/png, image/jpeg, image/gif or image/webp, not 'text/html'.",
        assertThrows(IllegalArgumentException.class, () -> new Image("text/html", "PGI+"))
            .getMessage());
    assertEquals(
        "An image is image/png, image/jpeg, image/gif or image/webp, not 'null'.",
        assertThrows(IllegalArgumentException.class, () -> new Image(null, "PGI+")).getMessage());
    assertEquals(
        "data must not be blank.",
        assertThrows(IllegalArgumentException.class, () -> new Image("image/png", " "))
            .getMessage());
  }

  @Test
  void anAllowIsTheRecordedAnswerWithTheInputToRun() {
    var request = step(Step.Permission.class, "02b-relay-stdio", 16);

    assertWritten("02b-relay-stdio", 17, LiveChat.allow(request.requestId(), request.input()));
    assertWritten(
        "02b-relay-stdio",
        73,
        LiveChat.allow(
            "5c399f9f-8451-4eea-9496-521b3af32a3d",
            Map.of(
                "command",
                "echo changed-by-host > upd.txt",
                "description",
                "Write original text into upd.txt")));
  }

  @Test
  void anAllowAlwaysHandsBackTheHarnessesOwnSuggestions() {
    var request = step(Step.Permission.class, "02b-relay-stdio", 99);

    assertWritten(
        "02b-relay-stdio",
        100,
        LiveChat.allowAlways(request.requestId(), request.input(), request.suggestions()));
  }

  @Test
  void aPlanIsApprovedByAllowingItWithItsOwnInput() {
    var plan = step(Step.Plan.class, "03b-plan-exit-plan-mode", 58);

    assertWritten("03b-plan-exit-plan-mode", 59, LiveChat.allow(plan.requestId(), plan.input()));
  }

  @Test
  void aDenyCarriesThePersonsWords() {
    var edit = step(Step.Permission.class, "02b-relay-stdio", 44);
    var plan = step(Step.Plan.class, "03b-plan-exit-plan-mode", 28);

    assertWritten(
        "02b-relay-stdio", 45, LiveChat.deny(edit.requestId(), "The person declined this edit."));
    assertWritten(
        "03b-plan-exit-plan-mode",
        29,
        LiveChat.deny(plan.requestId(), "Change the plan: write the word bye instead."));
  }

  @Test
  void anAnswerIsTheQuestionsWithTheChosenAnswers() {
    var question = step(Step.Question.class, "03a-ask-user-question", 21);

    assertWritten(
        "03a-ask-user-question",
        22,
        LiveChat.answer(question.requestId(), question.input(), Map.of("Which color?", "Blue")));
  }

  @Test
  void anInterruptAndAModeChangeAreTheRecordedRequests() {
    assertWritten("04a-cancel-request-by-interrupt", 19, LiveChat.interrupt("int-1"));
    assertWritten(
        "04b-set-permission-mode-and-background-task",
        77,
        LiveChat.setMode("mode-1", "acceptEdits"));
  }

  @Test
  void aBlankDescriptionGivesARowItsFirstArgument() {
    assertEquals(
        List.of(
            new ChatItem.Tool(
                null, "t1", ToolKind.COMMAND, "ls", new Detail.Command("ls", false, false), false)),
        LiveChat.items(
            new Step.ToolCalls(
                List.of(
                    new LiveStream.ToolCall(
                        "t1",
                        "Bash",
                        "ls",
                        LiveStream.ordered("command", "ls", "description", " ")))),
            CLAUDE_CODE));
  }

  @Test
  void anAllowAlwaysNeedsSomethingToKeep() {
    assertEquals(
        "Claude Code offered no way to allow this always; allow it once instead.",
        assertThrows(
                IllegalArgumentException.class,
                () -> LiveChat.allowAlways("r1", Map.of("command", "ls"), List.of()))
            .getMessage());
  }

  /**
   * A person can never allow what they were not shown whole: a request any part of which would be
   * clipped is refused at once, its notice tells the person, and its step holds no input to allow.
   */
  @Test
  void aRequestTooLargeToShowWholeIsRefusedAndNeverAllowed() {
    var padded = "echo " + "a".repeat(70_000) + "; rm -rf ~";
    var huge = "w".repeat(70_000);
    var tooLarge =
        List.of(
            request("Bash", "{\"command\":\"" + padded + "\"}", ""),
            request(
                "Write",
                "{\"file_path\":\"/home/dev/workspace/a.txt\",\"content\":\"" + huge + "\"}",
                ""),
            request("ExitPlanMode", "{\"plan\":\"" + huge + "\"}", ""),
            request(
                "AskUserQuestion",
                "{\"questions\":[{\"question\":\"Which?\",\"header\":\"h\","
                    + "\"options\":[{\"label\":\"Yes\",\"description\":\""
                    + huge
                    + "\"}]}]}",
                ""),
            request("Bash", "{\"command\":\"ls\"}", ",\"description\":\"" + huge + "\""),
            request("Bash", "{\"command\":\"ls\"}", ",\"decision_reason\":\"" + huge + "\""),
            request("Bash", "{\"command\":\"ls\"}", ",\"display_name\":\"" + huge + "\""),
            request("mcp__" + huge, "{}", ""));
    for (var line : tooLarge) {
      var step = LiveStream.read(line);
      assertEquals(new Step.Unreadable("r1", true), step, line.substring(0, 120));
      assertEquals(
          List.of(new ChatItem.Unreadable(true, false)),
          LiveChat.items(step, CLAUDE_CODE),
          line.substring(0, 120));
    }
    assertEquals(
        LiveChat.deny(
            "r1",
            "This is too large to show a person whole, so no person was asked and it is refused."
                + " Split it into smaller steps, such as a shorter command or a large file written"
                + " in parts."),
        LiveChat.refuseUnreadable(new Step.Unreadable("r1", true)));
  }

  @Test
  void aRequestOfAnyLengthItsCardShowsWholeIsAsked() {
    var command =
        "a"
            .repeat(
                LiveStream.CONTENT_CODE_POINTS
                    - YamlUtil.dumpJsonValue(Map.of("command", "")).length());
    var described = "d".repeat(LiveStream.SUMMARY_CODE_POINTS + 10);

    assertEquals(
        List.of(
            new ChatItem.Permission(
                "r1",
                "t1",
                null,
                ToolKind.COMMAND,
                described,
                described,
                new Detail.Command(command, false, false),
                List.of(),
                false)),
        LiveChat.items(
            LiveStream.read(
                request(
                    "Bash",
                    "{\"command\":\"" + command + "\"}",
                    ",\"description\":\""
                        + described
                        + "\",\"decision_reason\":\""
                        + described
                        + "\"")),
            CLAUDE_CODE),
        "a command whose input is at the bound, and words past a row's, are shown whole");
    assertEquals(
        new Step.Unreadable("r1", true),
        LiveStream.read(request("Bash", "{\"command\":\"" + command + "a\"}", "")),
        "one more character, and the card could not show it whole");
  }

  private static String request(String tool, String input, String more) {
    return "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{\"subtype\":"
        + "\"can_use_tool\",\"tool_name\":\""
        + tool
        + "\",\"tool_use_id\":\"t1\",\"input\":"
        + input
        + more
        + "}}";
  }

  @Test
  void whatTheAgentWroteLongIsShownInPartAndSaysSo() {
    var huge = "w".repeat(200_000);
    var summary = "w".repeat(LiveStream.SUMMARY_CODE_POINTS);
    var content = "w".repeat(LiveStream.CONTENT_CODE_POINTS);

    assertEquals(
        List.of(new ChatItem.Failed(content, null, true), new TurnEnded(null, 0, 0, 0)),
        read("{\"type\":\"result\",\"is_error\":true,\"result\":\"%s\"}", huge));
    assertEquals(
        List.of(new ChatItem.ModeChanged("w".repeat(LiveStream.NAME_CODE_POINTS), true)),
        read("{\"type\":\"system\",\"subtype\":\"status\",\"permissionMode\":\"%s\"}", huge));
    assertEquals(
        List.of(new ChatItem.ToolDone(null, "t1", content, false, true)),
        read(
            "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\","
                + "\"tool_use_id\":\"t1\",\"content\":\"%s\"}]}}",
            huge));
    assertEquals(
        List.of(new ChatItem.Text("t9", content, true, true)),
        read(
            "{\"type\":\"assistant\",\"parent_tool_use_id\":\"t9\",\"message\":"
                + "{\"content\":[{\"type\":\"text\",\"text\":\"%s\"}]}}",
            huge));
    assertEquals(
        List.of(
            new ChatItem.Tool(
                null, "t1", ToolKind.READ, summary, new Detail.Read(content, true), true)),
        read(
            "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\","
                + "\"id\":\"t1\",\"name\":\"Read\",\"input\":{\"file_path\":\"%s\"}}]}}",
            huge));
    assertEquals(
        List.of(new ChatItem.Refused("m1", summary, true)),
        read(
            "{\"type\":\"control_response\",\"response\":{\"subtype\":\"error\","
                + "\"request_id\":\"m1\",\"error\":\"%s\"}}",
            huge));
  }

  private static List<ChatItem> read(String template, String text) {
    return LiveChat.items(LiveStream.read(template.replace("%s", text)), CLAUDE_CODE);
  }

  @Test
  void aRequestWhoseSuggestionIsMissingIsShownWithNoAlways() {
    assertEquals(
        List.of(),
        ((ChatItem.Permission)
                read(
                        "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{\"subtype\":"
                            + "\"can_use_tool\",\"tool_name\":\"Bash\",\"tool_use_id\":\"t1\","
                            + "\"input\":{\"command\":\"ls\"},\"permission_suggestions\":"
                            + "[{\"type\":\"setMode\",\"mode\":\"acceptEdits\",\"destination\":"
                            + "\"session\"},%s]}}",
                        "null")
                    .getFirst())
            .always());
  }

  @Test
  void aRequestThatCannotBeReadIsShownAndRefusedAtOnce() {
    assertEquals(
        List.of(
            new ChatItem.Unreadable(false, false),
            new ChatItem.Unreadable(true, false),
            new ChatItem.Unreadable(false, true),
            new ChatItem.Unreadable(true, true)),
        List.of(
                new Step.Unreadable("r1", false),
                new Step.Unreadable("r1", true),
                new Step.Unreadable(null, false),
                new Step.Unreadable(null, true))
            .stream()
            .flatMap(step -> LiveChat.items(step, CLAUDE_CODE).stream())
            .toList(),
        "refused by its id, or, naming none, the turn stopped: the notice says which");
    assertEquals(
        "requestId must not be blank.",
        assertThrows(
                IllegalArgumentException.class,
                () -> LiveChat.refuseUnreadable(new Step.Unreadable(null, false)))
            .getMessage(),
        "a request that names no id is stopped, never answered");
    assertWritten(
        "02b-relay-stdio",
        45,
        LiveChat.refuseUnreadable(
                new Step.Unreadable("79327ade-fc36-4737-926f-eb6dde76e6df", false))
            .replace(
                "Sail could not read this request whole, so no person was asked and it is refused."
                    + " Ask again in another way if it is still needed.",
                "The person declined this edit."));
  }

  @Test
  void aWriterRefusesWhatNamesNothing() {
    assertEquals(
        "requestId must not be blank.",
        assertThrows(IllegalArgumentException.class, () -> LiveChat.allow(" ", Map.of()))
            .getMessage());
    assertEquals(
        "message must not be blank.",
        assertThrows(IllegalArgumentException.class, () -> LiveChat.deny("r1", "")).getMessage());
    assertEquals(
        "mode must not be blank.",
        assertThrows(IllegalArgumentException.class, () -> LiveChat.setMode("m1", null))
            .getMessage());
  }
}
