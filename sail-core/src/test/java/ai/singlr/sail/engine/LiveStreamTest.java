/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.engine.LiveStream.Step;
import ai.singlr.sail.engine.LiveStream.ToolCall;
import ai.singlr.sail.engine.LiveStream.ToolResult;
import ai.singlr.sail.harness.LoginOutcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The live member's wire: the line that prompts it, pinned whole, and what each line Claude Code's
 * streaming mode writes says, with everything the agent controls bounded where it is read.
 */
class LiveStreamTest {

  @Test
  void aPromptIsOneUserMessageLine() {
    assertEquals(
        "{\"type\": \"user\", \"message\": {\"role\": \"user\", \"content\":"
            + " [{\"type\": \"text\", \"text\": \"first \\\"line\\\"\\nsecond\"}]}}",
        LiveStream.userMessage("first \"line\"\nsecond"));
  }

  @Test
  void initNamesTheSession() {
    assertEquals(
        new Step.Init("sess-1", null, null, List.of(), List.of(), List.of()),
        LiveStream.read("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"sess-1\"}"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"-p; rm -rf /\"}",
        "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":7}",
        "{\"type\":\"system\",\"subtype\":\"init\"}"
      })
  void anInitWithNoSafeSessionSaysNothing(String line) {
    assertEquals(new Step.Other("system/init"), LiveStream.read(line));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{\"no_type\":true}", "[1,2]", "not json {", ""})
  void aLineThatIsNoTypedObjectSaysNothing(String line) {
    assertEquals(new Step.Other(""), LiveStream.read(line));
  }

  @Test
  void aLineOfATypeTheReaderDoesNotKnowIsKeptNamingItsType() {
    assertEquals(
        new Step.Other("rate_limit_event"), LiveStream.read("{\"type\":\"rate_limit_event\"}"));
    assertEquals(
        new Step.Other("x".repeat(64)),
        LiveStream.read("{\"type\":\"" + "x".repeat(80) + "\"}"),
        "a type is the agent's, and clipped where it is read");
    assertEquals(
        new Step.Other("system/" + "y".repeat(64)),
        LiveStream.read("{\"type\":\"system\",\"subtype\":\"" + "y".repeat(80) + "\"}"));
  }

  @Test
  void aLinePastTheFrameCeilingIsSkippedUnparsed() {
    var huge = "{\"type\":\"result\",\"result\":\"" + "x".repeat(LiveStream.MAX_LINE_CHARS) + "\"}";

    assertEquals(new Step.Other(""), LiveStream.read(huge));
  }

  @Test
  void aTextDeltaIsTheMembersText() {
    assertEquals(
        new Step.Text("Hel"),
        LiveStream.read(
            "{\"type\":\"stream_event\",\"parent_tool_use_id\":null,\"event\":{\"type\":"
                + "\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\","
                + "\"text\":\"Hel\"}}}"));
  }

  @Test
  void aTextBlockStartingIsToldApartFromItsText() {
    assertEquals(
        new Step.TextStarted(),
        LiveStream.read(
            "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\","
                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}}"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"type\":\"stream_event\",\"event\":{\"type\":\"message_start\"}}",
        "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\","
            + "\"content_block\":{\"type\":\"tool_use\"}}}",
        "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
            + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hm\"}}}",
        "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
            + "\"delta\":{\"type\":\"text_delta\",\"text\":\"\"}}}",
        "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
            + "\"delta\":{\"type\":\"text_delta\",\"text\":7}}}",
        "{\"type\":\"stream_event\",\"event\":{\"type\":\"message_stop\","
            + "\"delta\":{\"type\":\"text_delta\",\"text\":\"late\"}}}",
        "{\"type\":\"stream_event\"}",
        "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}",
        "{\"type\":\"assistant\",\"message\":{\"content\":\"hi\"}}",
        "{\"type\":\"assistant\"}"
      })
  void workWithNothingToShowIsStillWork(String line) {
    assertEquals(new Step.Working(), LiveStream.read(line));
  }

  @Test
  void toolCallsCarryTheirFirstStringArgumentClipped() {
    var command = "x".repeat(119) + "\uD83D\uDE00" + "tail";

    assertEquals(
        new Step.ToolCalls(
            List.of(
                new ToolCall(
                    "toolu_1",
                    "Bash",
                    "x".repeat(119) + "\uD83D\uDE00",
                    Map.of("timeout", 5, "command", command, "description", "second")),
                new ToolCall("toolu_2", "T".repeat(80), "", Map.of("limit", 3)),
                new ToolCall("toolu_3", "", "", Map.of()))),
        LiveStream.read(
            "{\"type\":\"assistant\",\"message\":{\"content\":["
                + "{\"type\":\"text\",\"text\":\"checking\"},"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"Bash\",\"input\":"
                + "{\"timeout\":5,\"command\":\""
                + command
                + "\",\"description\":\"second\"}},"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\""
                + "T".repeat(80)
                + "\",\"input\":{\"limit\":3}},"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_3\"}]}}"));
    assertEquals(
        new Step.Other("assistant"),
        LiveStream.read(
            "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\"}]}}"),
        "a call that names no id is no call");
  }

  @Test
  void toolResultsNameTheCallsThatCameBack() {
    assertEquals(
        new Step.ToolResults(
            List.of(new ToolResult("toolu_1", "ok", false), new ToolResult("toolu_2", "", false))),
        LiveStream.read(
            "{\"type\":\"user\",\"message\":{\"content\":["
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_1\",\"content\":\"ok\"},"
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_2\"}]}}"));
    assertEquals(
        new Step.Other("user"),
        LiveStream.read(
            "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\"}]}}"),
        "a result that names no call is no result");
  }

  @Test
  void aUserLineWithNoToolResultSaysNothing() {
    assertEquals(
        new Step.Other("user"),
        LiveStream.read(
            "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}"));
  }

  @Test
  void aSubagentsLinesAreItsOwnNeverTheMembers() {
    var inner =
        Map.of(
            "stream_event",
            new Step.Other("stream_event"),
            "assistant",
            new Step.ToolCalls(List.of(new ToolCall("t", "", "", Map.of()))),
            "user",
            new Step.ToolResults(List.of(new ToolResult("t", "", false))));
    inner.forEach(
        (type, step) ->
            assertEquals(
                new Step.Subagent("toolu_9", null, null, null, step),
                LiveStream.read(
                    "{\"type\":\""
                        + type
                        + "\",\"parent_tool_use_id\":\"toolu_9\",\"event\":{\"type\":"
                        + "\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"sub\"}},"
                        + "\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"t\"},"
                        + "{\"type\":\"tool_result\",\"tool_use_id\":\"t\"}]}}"),
                type));
    assertEquals(
        new Step.Other("assistant"),
        LiveStream.read("{\"type\":\"assistant\",\"parent_tool_use_id\":7}"),
        "a line naming its parent as no call's id is no one's");
  }

  @Test
  void aResultEndsTheTurnWithItsTextAndWhyItFailed() {
    assertEquals(
        ended(false, null, "Done.", null),
        LiveStream.read(
            "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                + "\"result\":\"Done.\"}"));
    assertEquals(
        ended(true, "error_max_turns", "", null),
        LiveStream.read("{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true}"));
    assertEquals(
        ended(true, null, "", null),
        LiveStream.read("{\"type\":\"result\",\"is_error\":true,\"result\":7}"),
        "a failure that names no reason is told with none");
  }

  @Test
  void aResultTheHarnessFlagsAnErrorFailedWhateverItsSubtypeSays() {
    assertEquals(
        ended(true, null, "Not logged in · Please run /login", new LoginOutcome.Undelivered()),
        LiveStream.read(
            "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,"
                + "\"result\":\"Not logged in · Please run /login\"}"),
        "failed, in its own words, and never for the reason 'success'");
    assertEquals(
        ended(true, null, "", null),
        LiveStream.read("{\"type\":\"result\",\"subtype\":\" \",\"is_error\":true}"));
  }

  private static Step.Result ended(boolean error, String reason, String text, LoginOutcome login) {
    return new Step.Result(
        error, reason, text, false, false, login, new ChatItem.TurnEnded(null, 0, 0, 0));
  }

  /**
   * Recorded from Claude Code 2.1.322 in the live member's streaming mode: one prompt, then two
   * more written while it was answering the first (after its first text delta). It finished the
   * first turn as asked, then began one more turn (a second {@code init}) and answered there; its
   * transcript shows both later messages dequeued together into that turn's one user message. So a
   * message written mid-turn is never folded into the turn in progress: it waits for the next turn,
   * and every message waiting then is taken into it.
   */
  @Test
  void followUpsWrittenMidTurnAreAnsweredInOneLaterTurn() throws IOException {
    String recorded;
    try (var in =
        LiveStreamTest.class.getResourceAsStream("claude-code-live-follow-ups-queued.jsonl")) {
      recorded = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    var steps = recorded.lines().map(LiveStream::read).toList();

    var turns = steps.stream().filter(step -> step instanceof Step.Init).count();
    var results =
        steps.stream().filter(Step.Result.class::isInstance).map(Step.Result.class::cast).toList();
    assertEquals(2, turns, "the first turn, then one more for both follow-ups");
    assertEquals(2, results.size());
    assertEquals(
        true, results.getFirst().text().endsWith("ALPHA-DONE"), "the first is answered whole");
    assertEquals("BRAVO", results.getLast().text());
  }
}
