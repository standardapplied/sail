/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.engine.LiveStream.Step;
import ai.singlr.sail.engine.LiveStream.ToolCall;
import java.util.List;
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
        new Step.Init("sess-1"),
        LiveStream.read("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"sess-1\"}"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"-p; rm -rf /\"}",
        "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":7}",
        "{\"type\":\"system\",\"subtype\":\"init\"}",
        "{\"type\":\"system\",\"subtype\":\"status\",\"session_id\":\"sess-1\"}",
        "{\"type\":\"rate_limit_event\"}",
        "{\"no_type\":true}",
        "[1,2]",
        "not json {",
        ""
      })
  void aLineThatSaysNothingIsIgnored(String line) {
    assertEquals(new Step.Other(), LiveStream.read(line));
  }

  @Test
  void aLinePastTheFrameCeilingIsSkippedUnparsed() {
    var huge = "{\"type\":\"result\",\"result\":\"" + "x".repeat(LiveStream.MAX_LINE_CHARS) + "\"}";

    assertEquals(new Step.Other(), LiveStream.read(huge));
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
                new ToolCall("toolu_1", "Bash", "x".repeat(119) + "\uD83D\uDE00"),
                new ToolCall("toolu_2", "T".repeat(64), ""),
                new ToolCall("", "", ""))),
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
                + "{\"type\":\"tool_use\"}]}}"));
  }

  @Test
  void toolResultsNameTheCallsThatCameBack() {
    assertEquals(
        new Step.ToolResults(List.of("toolu_1", "")),
        LiveStream.read(
            "{\"type\":\"user\",\"message\":{\"content\":["
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_1\",\"content\":\"ok\"},"
                + "{\"type\":\"tool_result\"}]}}"));
  }

  @Test
  void aUserLineWithNoToolResultSaysNothing() {
    assertEquals(
        new Step.Other(),
        LiveStream.read(
            "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}"));
  }

  @Test
  void aSubagentsLinesAreNotTheMembers() {
    for (var type : List.of("stream_event", "assistant", "user")) {
      assertEquals(
          new Step.Other(),
          LiveStream.read(
              "{\"type\":\""
                  + type
                  + "\",\"parent_tool_use_id\":\"toolu_9\",\"event\":{\"type\":"
                  + "\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"sub\"}},"
                  + "\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"t\"},"
                  + "{\"type\":\"tool_result\",\"tool_use_id\":\"t\"}]}}"),
          type);
    }
  }

  @Test
  void aResultEndsTheTurnWithItsTextAndWhyItFailed() {
    assertEquals(
        new Step.Result(false, null, "Done."),
        LiveStream.read(
            "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                + "\"result\":\"Done.\"}"));
    assertEquals(
        new Step.Result(true, "error_max_turns", ""),
        LiveStream.read("{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true}"));
    assertEquals(
        new Step.Result(true, null, ""),
        LiveStream.read("{\"type\":\"result\",\"is_error\":true,\"result\":7}"),
        "a failure that names no reason is told with none");
  }

  @Test
  void aResultTheHarnessFlagsAnErrorFailedWhateverItsSubtypeSays() {
    assertEquals(
        new Step.Result(true, null, "Not logged in · Please run /login"),
        LiveStream.read(
            "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,"
                + "\"result\":\"Not logged in · Please run /login\"}"),
        "failed, in its own words, and never for the reason 'success'");
    assertEquals(
        new Step.Result(true, null, ""),
        LiveStream.read("{\"type\":\"result\",\"subtype\":\" \",\"is_error\":true}"));
  }
}
