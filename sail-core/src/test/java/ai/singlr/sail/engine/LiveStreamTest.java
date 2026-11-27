/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.LiveStream.Step;
import ai.singlr.sail.engine.LiveStream.ToolCall;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
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
    var steps = recorded("claude-code-live-follow-ups-queued.jsonl").map(LiveStream::read).toList();

    var turns = steps.stream().filter(step -> step instanceof Step.Init).count();
    var results =
        steps.stream().filter(Step.Result.class::isInstance).map(Step.Result.class::cast).toList();
    assertEquals(2, turns, "the first turn, then one more for both follow-ups");
    assertEquals(2, results.size());
    assertEquals(
        true, results.getFirst().text().endsWith("ALPHA-DONE"), "the first is answered whole");
    assertEquals("BRAVO", results.getLast().text());
  }

  /**
   * Cut from probe 3's recordings on Claude Code 2.1.332, three one-turn sessions in the live
   * member's streaming mode, each asked to read {@code ~/.ssh/probe-secret.txt} once: with Sail's
   * {@code //} deny rules in the full lane ({@code --dangerously-skip-permissions}), a Read refused
   * by the rule ({@code 03b-full-dbl-ssh-Read}), which writes no {@code system/permission_denied}
   * line, only an {@code is_error} tool result, and a {@code cat} refused by the same rule ({@code
   * 03b-full-dbl-ssh-cat}), which writes one first; and in the read-only lane with the single-slash
   * rules a release before wrote, a Read refused only because nobody can answer a prompt ({@code
   * 03c-ro-single-ssh-Read}), which writes one too. Each refusal reads as the call coming back and
   * its turn ends in the agent's own words, not in error.
   */
  @Test
  void eachRecordedRefusalOfARuleOrAPromptReadsAsItsCallComingBack() throws IOException {
    var lines = recorded("claude-code-live-refusals.jsonl").toList();
    var steps =
        lines.stream()
            .map(LiveStream::read)
            .filter(
                step ->
                    step instanceof Step.Init
                        || step instanceof Step.ToolCalls
                        || step instanceof Step.ToolResults
                        || step instanceof Step.Result)
            .toList();
    var probe = "/home/dev/.ssh/probe-secret.txt";

    assertEquals(
        List.of(
            new Step.Init("49ff6e2d-1095-47c5-a9b1-beb62ec250d3"),
            new Step.ToolCalls(
                List.of(new ToolCall("toolu_01ABBt4q2CayEtxGGs4w2ejN", "Read", probe))),
            new Step.ToolResults(List.of("toolu_01ABBt4q2CayEtxGGs4w2ejN")),
            new Step.Result(false, null, "refused"),
            new Step.Init("f3eff3e4-0054-424b-b01c-86b2bfef0077"),
            new Step.ToolCalls(
                List.of(new ToolCall("toolu_01G3SHnTanJtnPd7Xmf1MZQP", "Bash", "cat " + probe))),
            new Step.ToolResults(List.of("toolu_01G3SHnTanJtnPd7Xmf1MZQP")),
            new Step.Result(false, null, "done"),
            new Step.Init("aea4d9dd-afe0-4685-8f23-23642aa14a9a"),
            new Step.ToolCalls(
                List.of(new ToolCall("toolu_01RRsFyFXe9qk2tuwBpNKyfX", "Read", probe))),
            new Step.ToolResults(List.of("toolu_01RRsFyFXe9qk2tuwBpNKyfX")),
            new Step.Result(false, null, "refused")),
        steps);
    var denied =
        lines.stream().filter(line -> line.contains("\"subtype\":\"permission_denied\"")).toList();
    assertEquals(
        List.of("toolu_01G3SHnTanJtnPd7Xmf1MZQP", "toolu_01RRsFyFXe9qk2tuwBpNKyfX"),
        denied.stream().map(line -> YamlUtil.parseMap(line).get("tool_use_id")).toList(),
        "the cat and the unanswerable prompt say permission_denied; the Read the rule refused does"
            + " not");
    denied.forEach(line -> assertEquals(new Step.Other(), LiveStream.read(line)));
  }

  private static Stream<String> recorded(String name) throws IOException {
    try (var in = LiveStreamTest.class.getResourceAsStream(name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines();
    }
  }
}
