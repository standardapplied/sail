/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

  private static final String HERE =
      "in this workspace from now on (kept in .claude/settings.local.json)";

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
    assertEquals(List.of(new ChatItem.Text(null, "", true)), shown("01-baseline-live-flags", 20));
    assertEquals(
        List.of(new ChatItem.Text(null, "done", false)), shown("01-baseline-live-flags", 21));
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
                new Detail.Command("echo probe-ok"))),
        shown("01-baseline-live-flags", 13));
    assertEquals(
        List.of(
            new ChatItem.Tool(
                null,
                "toolu_01GQbMEZJcBzatfHNojTS8E8",
                ToolKind.EDIT,
                "/home/dev/workspace/notes.txt",
                new Detail.Edit("/home/dev/workspace/notes.txt", null, "hi\n"))),
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
                        + " Then report the output in one sentence.\", \"run_in_background\": false}"))),
        shown("05b-subagent", 28));
    assertEquals(
        List.of(new ChatItem.ToolDone(null, "toolu_011VvCwXcvkemp321CMEyur6", "probe-ok", false)),
        shown("01-baseline-live-flags", 17));
    assertEquals(
        List.of(
            new ChatItem.ToolDone(
                null, "toolu_01GQbMEZJcBzatfHNojTS8E8", "The person declined this edit.", true)),
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
                new Detail.Read("/home/dev/workspace/a.txt"))),
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
                new Detail.Command("echo sub-ok"))),
        shown("05b-subagent", 35));
    assertEquals(
        List.of(new ChatItem.ToolDone(call, "toolu_013wKQqCQd4JL5cPCj24T27x", "sub-ok", false)),
        shown("05b-subagent", 36));
    assertEquals(List.of(), shown("05b-subagent", 32));
    assertEquals(
        List.of(
            new ChatItem.Text(
                "toolu_011gGRQDib3ZHFWutXd9HSGT",
                "The command `echo sub-ok` printed \"sub-ok\".",
                true)),
        shown("05c-subagent-forward-text", 50));
  }

  @Test
  void aPermissionRequestIsShownInPlainWordsWithWhyAndWhatAlwaysWouldChange() {
    assertEquals(
        List.of(
            new ChatItem.Permission(
                "a871f652-0656-42bf-8cc8-860ad0a34b79",
                "toolu_01C2VMe9ubaU1aFMnnUS5nxC",
                ToolKind.COMMAND,
                "Write current date into stamp.txt",
                null,
                new Detail.Command("date > stamp.txt"),
                List.of(
                    "Allow `date > stamp.txt` " + HERE,
                    "Let it work in /home/dev/workspace for this session"))),
        shown("02b-relay-stdio", 16));
    assertEquals(
        List.of(
            new ChatItem.Permission(
                "79327ade-fc36-4737-926f-eb6dde76e6df",
                "toolu_01GQbMEZJcBzatfHNojTS8E8",
                ToolKind.EDIT,
                "notes.txt",
                null,
                new Detail.Edit("/home/dev/workspace/notes.txt", null, "hi\n"),
                List.of("Switch to Edits freely for this session"))),
        shown("02b-relay-stdio", 44));
    var reasoned = (ChatItem.Permission) shown("04d-interrupt-while-tool-runs", 20).getFirst();
    assertEquals("This command requires approval", reasoned.reason());
    assertEquals(
        List.of(
            new ChatItem.Permission(
                "r1", "t1", ToolKind.COMMAND, "Bash", null, new Detail.Command("ls"), List.of())),
        LiveChat.items(
            new Step.Permission(
                "r1", "t1", "Bash", "Bash", Map.of("command", "ls"), null, null, List.of()),
            CLAUDE_CODE),
        "with no description it is named by the tool, and with no suggestion never always");
  }

  @Test
  void oneAlwaysOnACommandSaysItAlsoWidensWhereAndSwitchesTheMode() {
    assertEquals(
        List.of(
            "Allow `touch always.txt` " + HERE,
            "Let it work in /home/dev/workspace for this session",
            "Switch to Edits freely for this session"),
        ((ChatItem.Permission) shown("02b-relay-stdio", 99).getFirst()).always());
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
                        List.of(new Choice("Red", "Choose red"), new Choice("Blue", "Choose blue")),
                        false)))),
        shown("03a-ask-user-question", 21));
    assertEquals(
        List.of(
            new ChatItem.Plan(
                "1164cc67-5e3b-4c52-9696-57d3610fcb0e",
                "toolu_01H6Ej9TxsJDvVS7r7Zex4dC",
                "Create /home/dev/workspace/hello.txt containing \"hi\".\n")),
        shown("03b-plan-exit-plan-mode", 28));
    assertEquals(
        List.of(new ChatItem.Withdrawn("dc0b76a9-43c6-4484-a47b-1b7c1e5a4b10")),
        shown("04a-cancel-request-by-interrupt", 20));
  }

  @Test
  void aRetryAndACompactionAreNoticesAndAModeChangeIsShown() {
    assertEquals(
        List.of(new ChatItem.Retrying(1, 2, 513, "overloaded")), shown("05e-api-retry", 5));
    assertEquals(List.of(new ChatItem.Compacted("manual", 18074, 3069)), shown("05d-compact", 17));
    assertEquals(List.of(new ChatItem.ModeChanged("acceptEdits")), shown("02b-relay-stdio", 103));
    assertEquals(List.of(), shown("01-baseline-live-flags", 4));
    assertEquals(List.of(), shown("05d-compact", 14));
  }

  @Test
  void eachTurnSaysItsSession() {
    var init = step(Step.Init.class, "04b-set-permission-mode-and-background-task", 148);

    assertEquals(
        List.of(
            new ChatItem.Session("claude-haiku-5-5", "default", init.commands(), init.skills())),
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
                "Invalid API key · Fix external API key", new LoginOutcome.Refused()),
            new TurnEnded(null, 0, 0, 286)),
        shown("06a-invalid-key", 6));
    assertEquals(
        List.of(
            new ChatItem.Failed(
                "Not logged in · Please run /login", new LoginOutcome.Undelivered()),
            new TurnEnded(null, 0, 0, 182)),
        shown("06b-no-credential", 6));
    assertEquals(
        List.of(
            new ChatItem.Failed(
                "API Error: 529 Overloaded. This is a server-side issue, usually temporary — try"
                    + " again in a moment. If it persists, check your inference gateway"
                    + " (127.0.0.1:18529).",
                null),
            new TurnEnded(null, 0, 0, 1935)),
        shown("05e-api-retry", 8));
    assertEquals(
        List.of(new ChatItem.Failed("error_max_turns", null), new TurnEnded(null, 0, 0, 0)),
        LiveChat.items(
            LiveStream.read(
                "{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true}"),
            CLAUDE_CODE),
        "a failure with no words of its own is named by its reason");
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
        List.of(new ChatItem.Refused("mode-2", "Cannot set permission mode")),
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
        List.of(new ChatItem.Tool(null, "t1", ToolKind.COMMAND, "ls", new Detail.Command("ls"))),
        LiveChat.items(
            new Step.ToolCalls(
                List.of(
                    new LiveStream.ToolCall(
                        "t1", "Bash", "ls", Map.of("command", "ls", "description", " ")))),
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
