/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.ChatItem.Asked;
import ai.singlr.sail.engine.ChatItem.Choice;
import ai.singlr.sail.engine.ChatItem.TurnEnded;
import ai.singlr.sail.engine.LiveStream.Step;
import ai.singlr.sail.engine.LiveStream.ToolCall;
import ai.singlr.sail.engine.LiveStream.ToolResult;
import ai.singlr.sail.harness.LoginOutcome;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What each line a chat needs says, read from the line Claude Code 2.1.332 wrote in the S0 probe
 * ({@link Recorded}): every step from a recorded line, and every line the reader does not read
 * kept, never fatal.
 */
class LiveStreamChatTest {

  private static Step read(String recording, int line) {
    return LiveStream.read(Recorded.line(recording, line));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> requestOf(String recording, int line) {
    return (Map<String, Object>) Recorded.parsed(recording, line).get("request");
  }

  @SuppressWarnings("unchecked")
  private static List<Object> suggestionsOf(String recording, int line) {
    return (List<Object>) requestOf(recording, line).get("permission_suggestions");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> inputOf(String recording, int line) {
    return (Map<String, Object>) requestOf(recording, line).get("input");
  }

  @SuppressWarnings("unchecked")
  private static List<String> namesOf(String recording, int line, String key) {
    return (List<String>) Recorded.parsed(recording, line).get(key);
  }

  @Test
  void initNamesItsModelModeToolsCommandsAndSkillsAtEveryTurn() {
    var recording = "04b-set-permission-mode-and-background-task";

    assertEquals(
        new Step.Init(
            "32c4139c-5288-4783-b817-4f6b504f696d",
            "claude-haiku-5-5",
            "default",
            List.of("Task", "AskUserQuestion", "Bash", "Edit", "ExitPlanMode", "Read", "Write"),
            namesOf(recording, 148, "slash_commands"),
            namesOf(recording, 148, "skills")),
        read(recording, 148));
  }

  @Test
  void aPermissionRequestCarriesWhatAPersonDecidesOn() {
    assertEquals(
        new Step.Permission(
            "a871f652-0656-42bf-8cc8-860ad0a34b79",
            "toolu_01C2VMe9ubaU1aFMnnUS5nxC",
            null,
            "Bash",
            "Bash",
            Map.of(
                "command", "date > stamp.txt", "description", "Write current date into stamp.txt"),
            "Write current date into stamp.txt",
            null,
            suggestionsOf("02b-relay-stdio", 16)),
        read("02b-relay-stdio", 16));
    assertEquals(
        new Step.Permission(
            "79327ade-fc36-4737-926f-eb6dde76e6df",
            "toolu_01GQbMEZJcBzatfHNojTS8E8",
            null,
            "Write",
            "Write",
            Map.of("file_path", "/home/dev/workspace/notes.txt", "content", "hi\n"),
            "notes.txt",
            null,
            List.of(Map.of("type", "setMode", "mode", "acceptEdits", "destination", "session"))),
        read("02b-relay-stdio", 44));
    assertEquals(
        new Step.Permission(
            "2b22c465-b2fe-4e9f-81f2-dab652d13cd5",
            "toolu_01J7oi1RVgyfg9PQ4pYFbPqJ",
            null,
            "Bash",
            "Bash",
            inputOf("04d-interrupt-while-tool-runs", 20),
            "Run the 25-second sleep command in foreground",
            "This command requires approval",
            suggestionsOf("04d-interrupt-while-tool-runs", 20)),
        read("04d-interrupt-while-tool-runs", 20));
  }

  @Test
  void aQuestionArrivesAsARequestWithItsChoices() {
    assertEquals(
        new Step.Question(
            "d4d9b320-e2f7-497b-8037-5563770aed16",
            "toolu_01P2oh1RhKTq1npuEiNF3yrK",
            inputOf("03a-ask-user-question", 21),
            List.of(
                new Asked(
                    "Which color?",
                    "Color",
                    List.of(
                        new Choice("Red", "Choose red", false),
                        new Choice("Blue", "Choose blue", false)),
                    false,
                    false))),
        read("03a-ask-user-question", 21));
  }

  @Test
  void aPlanArrivesAsARequestToApprove() {
    assertEquals(
        new Step.Plan(
            "1164cc67-5e3b-4c52-9696-57d3610fcb0e",
            "toolu_01H6Ej9TxsJDvVS7r7Zex4dC",
            inputOf("03b-plan-exit-plan-mode", 28),
            "Create /home/dev/workspace/hello.txt containing \"hi\".\n"),
        read("03b-plan-exit-plan-mode", 28));
  }

  @Test
  void aWithdrawnRequestNamesTheRequest() {
    assertEquals(
        new Step.Shown(new ChatItem.Withdrawn("dc0b76a9-43c6-4484-a47b-1b7c1e5a4b10")),
        read("04a-cancel-request-by-interrupt", 20));
  }

  @Test
  void anAnswerToTheHostsRequestIsReadAndARefusalIsShown() {
    assertEquals(
        new Step.Other("control_response"),
        read("04b-set-permission-mode-and-background-task", 78));
    assertEquals(
        new Step.Shown(
            new ChatItem.Refused(
                "mode-bypass",
                "Cannot set permission mode to bypassPermissions because the session was not"
                    + " launched with --dangerously-skip-permissions",
                false)),
        read("02c-set-mode-bypass-from-default", 15),
        "a switch Claude Code refuses, with its code and no response (probe 2)");
    assertEquals(
        new Step.Other("control_response"), read("02d-set-mode-bypass-with-allow-dangerously", 15));
    assertEquals(new Step.Other("control_response"), read("04a-cancel-request-by-interrupt", 21));
    assertEquals(
        new Step.Shown(new ChatItem.Refused("mode-2", "Cannot set permission mode", false)),
        LiveStream.read(
            "{\"type\":\"control_response\",\"response\":{\"subtype\":\"error\","
                + "\"request_id\":\"mode-2\",\"error\":\"Cannot set permission mode\"}}"),
        "a refusal is no success, whatever else it says");
    assertEquals(
        new Step.Shown(new ChatItem.Refused("mode-3", "", false)),
        LiveStream.read(
            "{\"type\":\"control_response\",\"response\":{\"request_id\":\"mode-3\"}}"));
    assertEquals(
        new Step.Shown(new ChatItem.Refused(null, "Cannot set permission mode", false)),
        LiveStream.read(
            "{\"type\":\"control_response\",\"response\":{\"subtype\":\"error\","
                + "\"error\":\"Cannot set permission mode\"}}"),
        "a refusal that names no request is still shown");
  }

  /**
   * Asks first, launched as {@code --permission-mode manual} (probe 3): Claude Code says it is in
   * {@code default}, the chat mode's own name, and an edit and a command each ask, even in a
   * workspace whose settings make {@code acceptEdits} its default (01c:2).
   */
  @Test
  void asksFirstLaunchedAsManualIsDefaultAndAsksOverAWorkspaceDefault() {
    var recording = "01c-mode-manual-over-workspace-acceptEdits";

    assertEquals(
        "{\"permissions\": {\"defaultMode\": \"acceptEdits\"}}",
        Recorded.parsed(recording, 2).get("content"));
    assertEquals(
        List.of("default", "default", "Edit", "Bash"),
        List.of(
            ((Step.Init) read("01a-mode-manual", 4)).mode(),
            ((Step.Init) read(recording, 4)).mode(),
            ((Step.Permission) read(recording, 40)).tool(),
            ((Step.Permission) read(recording, 68)).tool()));
  }

  /**
   * A subagent's request comes as any other, with no {@code parent_tool_use_id}, naming the agent
   * that asks ({@code agent_id}, probe 2's 04a:45), which the subagent's own lines name beside the
   * call that started it (04a:44): so a host draws the request under that call's row.
   */
  @Test
  void aSubagentsRequestNamesTheAgentWhoseLinesNameItsRow() {
    var asked = (Step.Permission) read("04a-subagent-permission-request", 45);
    var subagent = (Step.Subagent) read("04a-subagent-permission-request", 44);

    assertEquals(
        new Step.Permission(
            "c6fa258b-074f-4da0-b642-7376329312cd",
            "toolu_01GTWGhnCaioZBJJDGHfJr9T",
            "af6598c6aac9ae006",
            "Bash",
            "Bash",
            inputOf("04a-subagent-permission-request", 45),
            "Create empty file sub-probe.txt",
            null,
            suggestionsOf("04a-subagent-permission-request", 45)),
        asked);
    assertEquals(
        List.of("toolu_01DKiF1gdiVDM1aLBmAN68qz", "af6598c6aac9ae006"),
        List.of(subagent.callId(), subagent.agentId()));
    assertEquals(
        new Step.Unreadable("r1", false),
        LiveStream.read(
            "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{\"subtype\":"
                + "\"can_use_tool\",\"tool_name\":\"Bash\",\"input\":{},\"tool_use_id\":\"t1\","
                + "\"agent_id\":\"../x\"}}"),
        "an agent that is not safe to name: never shown as the agent's own request");
  }

  @Test
  void aSubagentsLinesNameItsCallAndAreItsOwn() {
    var call = "toolu_01TbR5tat9n6LS2N65WNpEcZ";

    assertEquals(
        new Step.Subagent(
            call,
            "a6b207b9bc5fa6211",
            "general-purpose",
            "Run echo sub-ok",
            new Step.ToolCalls(
                List.of(
                    new ToolCall(
                        "toolu_013wKQqCQd4JL5cPCj24T27x",
                        "Bash",
                        "echo sub-ok",
                        Map.of(
                            "command", "echo sub-ok", "description", "Print sub-ok via echo"))))),
        read("05b-subagent", 35));
    assertEquals(
        new Step.Subagent(
            call,
            "a6b207b9bc5fa6211",
            "general-purpose",
            "Run echo sub-ok",
            new Step.ToolResults(
                List.of(new ToolResult("toolu_013wKQqCQd4JL5cPCj24T27x", "sub-ok", false)))),
        read("05b-subagent", 36));
    assertEquals(
        new Step.Subagent(
            call,
            "a6b207b9bc5fa6211",
            "general-purpose",
            "Run echo sub-ok",
            new Step.Other("user")),
        read("05b-subagent", 32),
        "its prompt is the Agent call's input, not words it says");
    assertEquals(
        new Step.Subagent(
            "toolu_011gGRQDib3ZHFWutXd9HSGT",
            "a6386d65c13f33e33",
            "general-purpose",
            "Run echo sub-ok",
            new Step.Said("The command `echo sub-ok` printed \"sub-ok\".")),
        read("05c-subagent-forward-text", 50),
        "a subagent's text is said whole: it streams no fragments");
  }

  @Test
  void aSubagentsTextInTwoBlocksIsTwoParagraphs() {
    assertEquals(
        new Step.Subagent("toolu_9", null, null, null, new Step.Said("First.\n\nSecond.")),
        LiveStream.read(
            "{\"type\":\"assistant\",\"parent_tool_use_id\":\"toolu_9\",\"message\":"
                + "{\"content\":[{\"type\":\"text\",\"text\":\"First.\"},"
                + "{\"type\":\"text\",\"text\":\"Second.\"}]}}"));
  }

  @Test
  void aRetryACompactionAndStatusAreRead() {
    assertEquals(
        new Step.Shown(new ChatItem.Retrying(1, 2, 513, "overloaded", false)),
        read("05e-api-retry", 5));
    assertEquals(
        new Step.Shown(new ChatItem.Compacted("manual", 18074, 3069, false)),
        read("05d-compact", 17));
    assertEquals(new Step.Status("requesting", null), read("01-baseline-live-flags", 4));
    assertEquals(new Step.Status("compacting", null), read("05d-compact", 14));
    assertEquals(new Step.Status(null, null), read("05d-compact", 15));
    assertEquals(new Step.Status(null, "acceptEdits"), read("02b-relay-stdio", 103));
  }

  @Test
  void aToolCallsWholeInputAndAResultsOutputAreRead() {
    assertEquals(
        new Step.ToolCalls(
            List.of(
                new ToolCall(
                    "toolu_011VvCwXcvkemp321CMEyur6",
                    "Bash",
                    "echo probe-ok",
                    Map.of("command", "echo probe-ok", "description", "Print probe-ok")))),
        read("01-baseline-live-flags", 13));
    assertEquals(
        new Step.ToolResults(
            List.of(new ToolResult("toolu_011VvCwXcvkemp321CMEyur6", "probe-ok", false))),
        read("01-baseline-live-flags", 17));
    assertEquals(
        new Step.ToolResults(
            List.of(
                new ToolResult(
                    "toolu_01GQbMEZJcBzatfHNojTS8E8", "The person declined this edit.", true))),
        read("02b-relay-stdio", 48));
    assertEquals(
        new Step.ToolResults(
            List.of(new ToolResult("toolu_01TbR5tat9n6LS2N65WNpEcZ", handBackText(), false))),
        read("05b-subagent", 39),
        "a result given as blocks is their text");
  }

  @SuppressWarnings("unchecked")
  private static String handBackText() {
    var message = (Map<String, Object>) Recorded.parsed("05b-subagent", 39).get("message");
    var result = ((List<Map<String, Object>>) message.get("content")).getFirst();
    var blocks = (List<Map<String, Object>>) result.get("content");
    assertEquals(1, blocks.size());
    return (String) blocks.getFirst().get("text");
  }

  @Test
  void aResultIsTheTurnsOwnCostTokensAndTime() {
    assertEquals(
        new Step.Result(
            false, null, "done", false, false, null, new TurnEnded(0.000301485, 23708, 86, 940)),
        read("02b-relay-stdio", 138));
    assertEquals(
        new Step.Result(
            false,
            null,
            "",
            false,
            false,
            null,
            new TurnEnded(0.0011170400000000001, 20069, 640, 3446)),
        read("05d-compact", 20));
  }

  @Test
  void anInterruptedTurnIsToldFromItsResult() {
    assertEquals(
        new Step.Result(
            true,
            "error_during_execution",
            "",
            true,
            false,
            null,
            new TurnEnded(0.000268005, 11014, 81, 2849)),
        read("04a-cancel-request-by-interrupt", 24));
    assertEquals(
        new Step.Result(
            true,
            "error_during_execution",
            "",
            true,
            false,
            null,
            new TurnEnded(0.00036863, 11011, 283, 2081)),
        read("04c-interrupt-while-streaming-text", 82));
    assertEquals(
        new Step.Working(),
        read("04c-interrupt-while-streaming-text", 77),
        "the partial text it aborted was streamed already");
  }

  @Test
  void aTurnTheHarnessStartedItselfIsATurnNotAnError() {
    assertEquals(
        new Step.Result(
            false,
            null,
            "The background `sleep 30` finished with exit code 0.",
            false,
            true,
            null,
            new TurnEnded(0.00017991, 12746, 20, 1147)),
        read("04b-set-permission-mode-and-background-task", 159));
  }

  @Test
  void aFailedTurnSaysWhatCameOfItsLogin() {
    assertEquals(
        new Step.Result(
            true,
            null,
            "Invalid API key · Fix external API key",
            false,
            false,
            new LoginOutcome.Refused(),
            new TurnEnded(null, 0, 0, 286)),
        read("06a-invalid-key", 6));
    assertEquals(
        new Step.Result(
            true,
            null,
            "Not logged in · Please run /login",
            false,
            false,
            new LoginOutcome.Undelivered(),
            new TurnEnded(null, 0, 0, 182)),
        read("06b-no-credential", 6));
    assertEquals(
        new Step.Result(
            true,
            null,
            "API Error: 529 Overloaded. This is a server-side issue, usually temporary — try again in"
                + " a moment. If it persists, check your inference gateway (127.0.0.1:18529).",
            false,
            false,
            null,
            new TurnEnded(null, 0, 0, 1935)),
        read("05e-api-retry", 8),
        "a turn no model reports a cost for has none, not $0.00");
    assertEquals(
        new Step.Working(),
        read("06a-invalid-key", 5),
        "the harness's own error line repeats what its result says");
  }

  @Test
  void aRecordedLineTheReaderDoesNotReadIsKeptNamingItsType() {
    assertEquals(new Step.Other("system/task_started"), read("04d-interrupt-while-tool-runs", 24));
    assertEquals(new Step.Other("system/ui_invalidate"), read("08c-bare-no-key", 3));
    assertEquals(new Step.Other("control_response"), read("04a-cancel-request-by-interrupt", 21));
    assertEquals(
        new Step.Other("user"),
        read("04a-cancel-request-by-interrupt", 23),
        "the interruption is told by the turn's result");
    assertEquals(new Step.Working(), read("01-baseline-live-flags", 8));
  }

  @Test
  void aRequestWithNoSuggestionOffersNothingToKeep() {
    assertEquals(
        new Step.Permission(
            "r1", "t1", null, "Bash", "Bash", Map.of("command", "ls"), null, null, List.of()),
        LiveStream.read(
            "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{\"subtype\":"
                + "\"can_use_tool\",\"tool_name\":\"Bash\",\"display_name\":\"Bash\","
                + "\"input\":{\"command\":\"ls\"},\"tool_use_id\":\"t1\"}}"));
  }

  @Test
  void aTurnWhoseModelsNameNoCostHasNone() {
    assertEquals(
        new Step.Result(false, null, "ok", false, false, null, new TurnEnded(null, 3, 1, 0)),
        LiveStream.read(
            "{\"type\":\"result\",\"is_error\":false,\"result\":\"ok\","
                + "\"model_usage_since_last_result\":{\"a\":{\"inputTokens\":3,"
                + "\"outputTokens\":1}}}"));
  }

  @Test
  void aQuestionOfSeveralChoicesSaysSo() {
    assertEquals(
        new Step.Question(
            "r1",
            "t1",
            Map.of(
                "questions",
                List.of(
                    Map.of(
                        "question",
                        "Which?",
                        "multiSelect",
                        true,
                        "options",
                        List.of(Map.of("label", "A"), Map.of("label", "B"))))),
            List.of(
                new Asked(
                    "Which?",
                    null,
                    List.of(new Choice("A", null, false), new Choice("B", null, false)),
                    true,
                    false))),
        LiveStream.read(
            "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{\"subtype\":"
                + "\"can_use_tool\",\"tool_name\":\"AskUserQuestion\",\"tool_use_id\":\"t1\","
                + "\"input\":{\"questions\":[{\"question\":\"Which?\",\"multiSelect\":true,"
                + "\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}]}]}}}"));
  }

  @Test
  void aQuestionsLabelsAreHeldWholeToBeAnsweredExactly() {
    var label = "l".repeat(LiveStream.SUMMARY_CODE_POINTS + 10);
    var header = "h".repeat(LiveStream.SUMMARY_CODE_POINTS + 10);

    var question =
        (Step.Question)
            LiveStream.read(
                "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{\"subtype\":"
                    + "\"can_use_tool\",\"tool_name\":\"AskUserQuestion\",\"tool_use_id\":\"t1\","
                    + "\"input\":{\"questions\":[{\"question\":\"Which?\",\"header\":\""
                    + header
                    + "\",\"options\":[{\"label\":\""
                    + label
                    + "\"}]}]}}}");

    assertEquals(
        List.of(new Asked("Which?", header, List.of(new Choice(label, null, false)), false, false)),
        question.questions(),
        "an answer hands back the label chosen, so the step keeps it whole");
  }

  /**
   * A line past the frame ceiling is not parsed, but its start is read: a request the agent made
   * that long is too large to show a person, so it is refused, by its id when the start names it,
   * else the turn is stopped; any other such line says nothing, as before.
   */
  @Test
  void aRequestPastTheFrameCeilingIsRefusedNeverLostUnseen() {
    var padding = "a".repeat(LiveStream.MAX_LINE_CHARS);
    var asked =
        "{\"type\":\"control_request\",\"request_id\":\"%s\",\"request\":{\"subtype\":"
            + "\"%s\",\"tool_name\":\"Bash\",\"input\":{\"command\":\""
            + padding
            + "\"},\"tool_use_id\":\"t1\"}}";

    assertEquals(
        new Step.Unreadable("r1", true),
        LiveStream.read(asked.formatted("r1", "can_use_tool")),
        "its id is in the start read, so the host refuses it at once");
    assertEquals(
        new Step.Unreadable(null, true),
        LiveStream.read(asked.formatted("-p", "can_use_tool")),
        "an id that is not safe is never handed back: the turn is stopped");
    assertEquals(
        new Step.Unreadable(null, true),
        LiveStream.read(asked.formatted("r1", "elicitation")),
        "a request no deny answers: the turn is stopped");
    assertEquals(
        new Step.Unreadable(null, true),
        LiveStream.read(
            "{\"type\":\"control_request\",\"request\":{\"subtype\":\"can_use_tool\","
                + "\"input\":{\"command\":\""
                + padding
                + "\"},\"request_id\":\"r1\"}}"),
        "an id past the start read: the turn is stopped");
    assertEquals(
        new Step.Other(""),
        LiveStream.read("{\"type\":\"user\",\"message\":\"" + padding + "\"}"),
        "any other line says nothing, and its row is closed at the turn's end");
    assertEquals(
        new Step.Other(""),
        LiveStream.read("{\"x\":\"{\\\"type\\\":\\\"control_request\\\"," + padding + "\"}"),
        "only a line that starts as a request is one");
  }

  /**
   * A plan or a question with a field its card does not draw is asked as any other call, its whole
   * input shown: a person is shown everything they allow. Recorded: a plan's {@code plan} and
   * {@code planFilePath} (03b:28), a question's fields (03a:21).
   */
  @Test
  @SuppressWarnings("unchecked")
  void aPlanOrQuestionWithAFieldItsCardDoesNotDrawIsAskedAsACall() {
    var asked =
        "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{\"subtype\":"
            + "\"can_use_tool\",\"tool_name\":\"%s\",\"tool_use_id\":\"t1\",\"input\":%s}}";
    for (var call :
        List.of(
            List.of("ExitPlanMode", "{\"plan\":\"p\",\"allowedPrompts\":[{\"tool\":\"Bash\"}]}"),
            List.of("ExitPlanMode", "{\"plan\":7}"),
            List.of("AskUserQuestion", "{\"questions\":[],\"answers\":{\"q\":\"a\"}}"),
            List.of(
                "AskUserQuestion", "{\"questions\":[{\"question\":\"q\",\"options\":[],\"x\":1}]}"),
            List.of(
                "AskUserQuestion",
                "{\"questions\":[{\"question\":\"q\",\"options\":[{\"label\":\"a\",\"x\":1}]}]}"),
            List.of("AskUserQuestion", "{\"questions\":[\"q\"]}"))) {
      var line = asked.formatted(call.get(0), call.get(1));
      assertEquals(
          new Step.Permission(
              "r1",
              "t1",
              null,
              call.get(0),
              "",
              (Map<String, Object>)
                  ((Map<String, Object>) Recorded.parsed(line).get("request")).get("input"),
              null,
              null,
              List.of()),
          LiveStream.read(line),
          call.toString());
    }
    assertEquals(
        Step.Plan.class,
        LiveStream.read(
                asked.formatted("ExitPlanMode", "{\"plan\":\"p\",\"planFilePath\":\"/p.md\"}"))
            .getClass(),
        "the plan's file only describes it (03b:28)");
  }

  /**
   * A request line that cannot be parsed under the frame ceiling, malformed or nested past what a
   * reader takes, is unreadable and names no id to answer: the host stops the turn, so it never
   * waits on a request no person sees.
   */
  @Test
  void aRequestLineThatCannotBeParsedStopsTheTurn() {
    var depth = 5_000;
    var nested =
        "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{\"subtype\":"
            + "\"can_use_tool\",\"tool_name\":\"mcp__x__y\",\"tool_use_id\":\"t1\",\"input\":"
            + "{\"a\":"
            + "[".repeat(depth)
            + "]".repeat(depth)
            + "}}}";
    assertTrue(nested.length() < LiveStream.MAX_LINE_CHARS);

    assertEquals(new Step.Unreadable(null, false), LiveStream.read(nested));
    assertEquals(
        new Step.Unreadable(null, false),
        LiveStream.read("{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{"),
        "malformed");
    assertEquals(
        new Step.Other(""),
        LiveStream.read("{\"type\":\"user\",\"message\":{"),
        "any other line that cannot be parsed says nothing, as before");
  }

  @Test
  void whatTheAgentWroteIsReadWholeForItsItemToClip() {
    var longer = "y".repeat(ChatItem.CONTENT_CODE_POINTS + 5);

    assertEquals(
        new Step.ToolResults(List.of(new ToolResult("t1", longer, false))),
        LiveStream.read(
            "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\","
                + "\"tool_use_id\":\"t1\",\"content\":\""
                + longer
                + "\"}]}}"));
    assertEquals(
        new Step.Unreadable("r1", true),
        LiveStream.read(
            "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{\"subtype\":"
                + "\"can_use_tool\",\"tool_name\":\"ExitPlanMode\",\"tool_use_id\":\"t1\","
                + "\"input\":{\"plan\":\""
                + longer
                + "\"}}}"));
  }

  @Test
  void noLineIsFatalWhateverItHolds() {
    assertEquals(
        new Step.Permission("r1", "t1", null, "Bash", "", Map.of(), null, null, List.of()),
        LiveStream.read(
            "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{\"subtype\":"
                + "\"can_use_tool\",\"tool_name\":\"Bash\",\"tool_use_id\":\"t1\",\"input\":{},"
                + "\"permission_suggestions\":[{\"type\":\"setMode\",\"mode\":\"acceptEdits\","
                + "\"destination\":\"session\"},null]}}"),
        "a null where a suggestion was: the request is read, and offers nothing to keep");
    var depth = 200_000;
    var nested = "{\"type\":\"user\",\"message\":" + "[".repeat(depth) + "]".repeat(depth) + "}";
    assertTrue(nested.length() < LiveStream.MAX_LINE_CHARS);
    assertEquals(new Step.Other(""), LiveStream.read(nested), "nested past any stack");
  }

  @Test
  void anIdThatIsNotSafeToHandBackMakesItsLineSayNothing() {
    var unsafe = List.of("x".repeat(129), "-p", "a b", "../x", "");
    for (var id : unsafe) {
      assertEquals(
          new Step.Unreadable(null, false),
          LiveStream.read(
              "{\"type\":\"control_request\",\"request_id\":\""
                  + id
                  + "\",\"request\":{\"subtype\":\"can_use_tool\",\"tool_name\":\"Bash\","
                  + "\"input\":{},\"tool_use_id\":\"t1\"}}"),
          id);
      assertEquals(
          new Step.Unreadable("r1", false),
          LiveStream.read(
              "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{\"subtype\":"
                  + "\"can_use_tool\",\"tool_name\":\"Bash\",\"input\":{},\"tool_use_id\":\""
                  + id
                  + "\"}}"),
          id);
      assertEquals(
          new Step.Other("control_cancel_request"),
          LiveStream.read("{\"type\":\"control_cancel_request\",\"request_id\":\"" + id + "\"}"),
          id);
      assertEquals(
          new Step.Other("assistant"),
          LiveStream.read(
              "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\","
                  + "\"id\":\""
                  + id
                  + "\",\"name\":\"Bash\",\"input\":{}}]}}"),
          id);
      assertEquals(
          new Step.Other("user"),
          LiveStream.read(
              "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\","
                  + "\"tool_use_id\":\""
                  + id
                  + "\"}]}}"),
          id);
      assertEquals(
          new Step.Shown(new ChatItem.Refused(null, "", false)),
          LiveStream.read(
              "{\"type\":\"control_response\",\"response\":{\"subtype\":\"error\","
                  + "\"request_id\":\""
                  + id
                  + "\"}}"),
          id);
    }
    assertEquals(
        new Step.Shown(new ChatItem.Withdrawn("x".repeat(128))),
        LiveStream.read(
            "{\"type\":\"control_cancel_request\",\"request_id\":\"" + "x".repeat(128) + "\"}"),
        "the longest id it takes");
  }

  @Test
  void aModelThatNamesNoCostAddsItsTokensAndNoDollars() {
    assertEquals(
        new Step.Result(false, null, "ok", false, false, null, new TurnEnded(0.5, 7, 2, 0)),
        LiveStream.read(
            "{\"type\":\"result\",\"is_error\":false,\"result\":\"ok\","
                + "\"model_usage_since_last_result\":{\"a\":{\"inputTokens\":3,"
                + "\"outputTokens\":1},\"b\":{\"inputTokens\":4,\"outputTokens\":1,"
                + "\"costUSD\":0.5}}}"));
  }

  @Test
  void aRequestThatCannotBeReadWholeIsOneToRefuseAtOnce() {
    assertEquals(
        new Step.Unreadable(null, false),
        LiveStream.read(
            "{\"type\":\"control_request\",\"request\":{\"subtype\":\"can_use_tool\","
                + "\"tool_name\":\"Bash\",\"input\":{},\"tool_use_id\":\"t1\"}}"),
        "no request id: the turn is stopped, since nothing can answer it");
    assertEquals(
        new Step.Unreadable("r1", false),
        LiveStream.read(
            "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":"
                + "{\"subtype\":\"can_use_tool\",\"tool_name\":\"Bash\",\"input\":{}}}"),
        "no call");
    assertEquals(
        new Step.Unreadable("r1", false),
        LiveStream.read(
            "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":"
                + "{\"subtype\":\"can_use_tool\",\"tool_name\":\"Bash\",\"tool_use_id\":\"t1\"}}"),
        "no input to run or hand back");
    assertEquals(
        new Step.Unreadable("r1", false),
        LiveStream.read(
            "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":"
                + "{\"subtype\":\"can_use_tool\",\"input\":{},\"tool_use_id\":\"t1\"}}"),
        "no tool");
    assertEquals(
        new Step.Unreadable("r1", false),
        LiveStream.read(
            "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":"
                + "{\"subtype\":\"can_use_tool\",\"tool_name\":\"Bash\",\"input\":[],"
                + "\"tool_use_id\":\"t1\"}}"),
        "an input that is no object");
  }

  @Test
  void aRequestOfAnotherKindIsKeptNamingItsSubtype() {
    assertEquals(
        new Step.Other("control_request/hook_callback"),
        LiveStream.read(
            "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":"
                + "{\"subtype\":\"hook_callback\"}}"));
    assertEquals(
        new Step.Other("control_request/"),
        LiveStream.read("{\"type\":\"control_request\",\"request_id\":\"r1\"}"));
    assertEquals(
        new Step.Other("control_cancel_request"),
        LiveStream.read("{\"type\":\"control_cancel_request\",\"request_id\":7}"));
  }
}
