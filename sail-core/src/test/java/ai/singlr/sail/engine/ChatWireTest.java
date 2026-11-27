/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.harness.LoginOutcome;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A chat item as its log keeps it and a client reads it: its type, the snake case of its record's
 * name, beside each field by its snake-case name, read off the record as it is, a nested record the
 * same way with its own type when it is one of a sealed set's, an enum in lower case, and nothing
 * for a field left unsaid. Each item here is one Claude Code wrote in the S0 probe.
 */
class ChatWireTest {

  private static final ai.singlr.sail.harness.HarnessChat CLAUDE =
      Harnesses.of("claude-code").chat().orElseThrow();

  private static ChatItem shown(String recording, int line) {
    return LiveChat.items(LiveStream.read(Recorded.line(recording, line)), CLAUDE).getFirst();
  }

  @Test
  void anItemsTypeIsItsRecordsNameInSnakeCase() {
    assertEquals(
        List.of("turn_ended", "tool_done", "self_started"),
        List.of(
            ChatWire.kind(ChatItem.TurnEnded.class),
            ChatWire.kind(ChatItem.ToolDone.class),
            ChatWire.kind(ChatItem.SelfStarted.class)));
    assertEquals(
        List.of("message", "delivered", "told"),
        List.of(
            ChatWire.kind(ChatWire.Message.class),
            ChatWire.kind(ChatWire.Delivered.class),
            ChatWire.kind(ChatWire.Told.class)));
  }

  @Test
  void sailsOwnEntriesSayTheirFieldsAsAnItemDoes() {
    assertEquals(
        Map.of("text", "look", "queued", true),
        ChatWire.fields(new ChatWire.Message("look", true)));
    assertEquals(Map.of("message", 7L), ChatWire.fields(new ChatWire.Delivered(7)));
    assertEquals(
        Map.of("words", "Sail restarted."), ChatWire.fields(new ChatWire.Told("Sail restarted.")));
  }

  @Test
  void aCallsRowCarriesItsKindInLowerCaseAndItsDetailWithItsOwnType() {
    var row = ChatWire.fields((Record) shown("01-baseline-live-flags", 13));

    var expected = new LinkedHashMap<String, Object>();
    expected.put("id", "toolu_011VvCwXcvkemp321CMEyur6");
    expected.put("kind", "command");
    expected.put("words", "Print probe-ok");
    expected.put(
        "detail",
        Map.of("type", "command", "line", "echo probe-ok", "background", false, "clipped", false));
    expected.put("clipped", false);
    assertEquals(expected, row, "no under: the agent's own row");
  }

  @Test
  void aFailedTurnSaysItsLoginOutcomeByItsType() {
    var failed =
        ChatWire.fields(new ChatItem.Failed("Invalid API key", new LoginOutcome.Refused(), false));
    var limited =
        ChatWire.fields(new ChatItem.Failed("limit", new LoginOutcome.Limited("3pm (UTC)"), false));

    assertEquals(
        Map.of("words", "Invalid API key", "login", Map.of("type", "refused"), "clipped", false),
        failed);
    assertEquals(
        Map.of(
            "words",
            "limit",
            "login",
            Map.of("type", "limited", "resets", "3pm (UTC)"),
            "clipped",
            false),
        limited);
  }

  @Test
  void aQuestionsChoicesAreMapsOfTheirFieldsAndListsStayLists() {
    var question =
        ChatWire.fields(
            new ChatItem.Question(
                "r1",
                "t1",
                List.of(
                    new ChatItem.Asked(
                        "Which color?",
                        "Color",
                        List.of(new ChatItem.Choice("Red", "Choose red", false)),
                        false,
                        false))));

    assertEquals(
        Map.of(
            "id",
            "r1",
            "call",
            "t1",
            "questions",
            List.of(
                Map.of(
                    "question",
                    "Which color?",
                    "header",
                    "Color",
                    "choices",
                    List.of(Map.of("label", "Red", "about", "Choose red", "clipped", false)),
                    "multi_select",
                    false,
                    "clipped",
                    false))),
        question);
  }

  @Test
  void aTurnsCostIsANumberAndAnUnsaidOneIsLeftOut() {
    assertEquals(
        Map.of(
            "cost_usd", 0.000301485, "tokens_in", 23708L, "tokens_out", 86L, "duration_ms", 940L),
        ChatWire.fields((Record) shown("02b-relay-stdio", 138)));
    assertEquals(
        Map.of("tokens_in", 0L, "tokens_out", 0L, "duration_ms", 286L),
        ChatWire.fields(new ChatItem.TurnEnded(null, 0, 0, 286)));
  }
}
