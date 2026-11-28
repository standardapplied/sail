/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static ai.singlr.sail.api.ChatBench.as;
import static ai.singlr.sail.api.ChatBench.line;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Recorded;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every line a chat writes on Claude Code's stdin over a recorded turn, byte for byte: the message,
 * an allow, an always, a question answered, a denial with its words, a refusal Sail makes at once,
 * a stop, the waiting message handed at the turn's end, and a change of mode. Each line Claude Code
 * was recorded taking is the recording's own, in Sail's spacing; a request id Sail mints is the
 * only part read as a shape.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ChatLinesTest {

  private static final String SAID =
      "Use the Bash tool to run exactly: date > stamp.txt   Then reply with just: done";

  private static final String TOO_LARGE =
      "{\"type\":\"control_request\",\"request_id\":\"big-1\",\"request\":{\"subtype\":"
          + "\"can_use_tool\",\"tool_name\":\"Bash\",\"display_name\":\"Bash\",\"input\":"
          + "{\"command\":\""
          + "a".repeat((1 << 16) + 1)
          + "\"},\"tool_use_id\":\"toolu_big\"}}";

  private static final String MINTED = "\"request_id\": \"MINTED\"";

  @TempDir Path dir;
  private ChatBench bench;

  @BeforeEach
  void setUp() {
    bench = new ChatBench(dir);
  }

  @AfterEach
  void tearDown() {
    bench.close();
  }

  private void answer(String session, String request, Map<String, Object> body) {
    as("sam", () -> bench.host.answers().answer(session, request, body));
  }

  @Test
  void aRecordedTurnWritesTheSameLinesByteForByte() {
    var session = bench.samsChat();
    bench.send(session, SAID);
    bench.says(line("02b-relay-stdio", 3), line("02b-relay-stdio", 16));
    answer(session, "a871f652-0656-42bf-8cc8-860ad0a34b79", Map.of("decision", "allow"));
    bench.says(line("02b-relay-stdio", 99));
    answer(session, "d3b09c29-b79d-4151-b490-d70fdf7adb18", Map.of("decision", "allow_always"));
    bench.says(line("02-question-multi-select-and-own-words", 32));
    answer(
        session,
        "82da6f15-368c-4201-9a85-f925427a7c03",
        Map.of(
            "decision",
            "answer",
            "answers",
            Map.of(
                "Which colors?",
                Map.of("chosen", List.of("Red", "Blue")),
                "Which size?",
                Map.of("own", "teal, but only on weekends"))));
    bench.says(line("05-answer-twice", 16));
    answer(
        session,
        "bda0b4cf-c4f6-4219-bd84-0f0617292889",
        Map.of("decision", "deny", "message", "DENIED-tamarind-4417"));
    bench.says(TOO_LARGE);
    bench.send(session, "second");
    as("sam", () -> bench.host.answers().interrupt(session));
    var fake = bench.says(line("02b-relay-stdio", 138));
    bench.says(line("02b-relay-stdio", 3), line("02b-relay-stdio", 138));
    as("sam", () -> bench.host.answers().mode(session, Map.of("mode", "plan")));

    assertEquals(
        List.of(
            taken("02b-relay-stdio", 2),
            taken("02b-relay-stdio", 17),
            taken("02b-relay-stdio", 100),
            taken("02-question-multi-select-and-own-words", 34),
            taken("05-answer-twice", 17),
            "{\"type\": \"control_response\", \"response\": {\"subtype\": \"success\","
                + " \"request_id\": \"big-1\", \"response\": {\"behavior\": \"deny\","
                + " \"message\": \"This is too large to show a person whole, so no person was"
                + " asked and it is refused. Split it into smaller steps, such as a shorter command"
                + " or a large file written in parts.\"}}}",
            "{\"type\": \"control_request\", "
                + MINTED
                + ", \"request\": {\"subtype\": \"interrupt\"}}",
            "{\"type\": \"user\", \"message\": {\"role\": \"user\", \"content\":"
                + " [{\"type\": \"text\", \"text\": \"second\"}]}}",
            "{\"type\": \"control_request\", "
                + MINTED
                + ", \"request\": {\"subtype\": \"set_permission_mode\", \"mode\":"
                + " \"plan\"}}"),
        fake.written.stream().map(ChatLinesTest::minted).toList());
  }

  /**
   * Line {@code number} of {@code recording}, which Claude Code was recorded taking, as Sail writes
   * every line: its keys in their order, a space after each colon and comma.
   */
  private static String taken(String recording, int number) {
    return YamlUtil.dumpJson(Recorded.parsed(recording, number));
  }

  /** {@code line} with the id of a request Sail itself made read as its shape alone. */
  private static String minted(String line) {
    return line.startsWith("{\"type\": \"control_request\"")
        ? line.replaceFirst(
            "\"request_id\": \"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"",
            MINTED)
        : line;
  }
}
