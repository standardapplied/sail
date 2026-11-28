/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.engine.ChatItem;
import ai.singlr.sail.engine.ChatWire;
import ai.singlr.sail.engine.LiveChat;
import ai.singlr.sail.engine.LiveReply;
import ai.singlr.sail.engine.LiveStream;
import ai.singlr.sail.engine.Recorded;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Claude Code's wire is its stream as {@link LiveStream} reads it and {@link LiveChat} and {@link
 * LiveReply} write it, and nothing else: no opening; a turn begun at each {@code init}, which names
 * the conversation and mode, and a mode said in a {@code status}; a request it cannot be shown
 * whole refused at once by its id, or, naming none, its turn stopped; a request answered as {@link
 * LiveReply} answers its step; and a message, a stop and a change of mode as {@link LiveChat} and
 * {@link LiveStream} write them. A request is never heard without how it is answered.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ClaudeCodeWireTest {

  private static final HarnessChat CHAT = Harnesses.of("claude-code").chat().orElseThrow();
  private static final HarnessChat.Wire WIRE =
      CHAT.wire(new HarnessChat.Launch("default", null, null));

  private static HarnessChat.Heard heard(String recording, int line) {
    return WIRE.read(Recorded.line(recording, line));
  }

  private static List<ChatItem> items(String recording, int line) {
    return LiveChat.items(LiveStream.read(Recorded.line(recording, line)), CHAT);
  }

  @Test
  void aTurnBeginsAtEachInitWhichNamesItsConversationAndMode() {
    var init = heard("02b-relay-stdio", 3);

    assertEquals(
        new HarnessChat.Heard(
            items("02b-relay-stdio", 3),
            null,
            new HarnessChat.Said("76b07c5e-a474-4649-b932-53a66c477087", "default"),
            true,
            false,
            List.of()),
        init);
    assertEquals(
        new HarnessChat.Heard(
            items("02b-relay-stdio", 103),
            null,
            new HarnessChat.Said(null, "acceptEdits"),
            false,
            false,
            List.of()),
        heard("02b-relay-stdio", 103));
    assertEquals(List.of(), WIRE.opening(), "Claude Code needs no initialize");
  }

  @Test
  void aRequestItCannotShowWholeIsRefusedAtOnceOrItsTurnStopped() {
    var tooLarge =
        "{\"type\":\"control_request\",\"request_id\":\"big-1\",\"request\":{\"subtype\":"
            + "\"can_use_tool\",\"tool_name\":\"Bash\",\"display_name\":\"Bash\",\"input\":"
            + "{\"command\":\""
            + "a".repeat(ChatItem.CONTENT_CODE_POINTS + 1)
            + "\"},\"tool_use_id\":\"toolu_big\"}}";
    var malformed = "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{";

    var refused = WIRE.read(tooLarge);
    var stopped = WIRE.read(malformed);

    assertEquals(
        List.of(LiveChat.refuseUnreadable(new LiveStream.Step.Unreadable("big-1", true))),
        refused.replies());
    assertEquals(List.of(false, true), List.of(refused.stop(), stopped.stop()));
    assertEquals(List.of(), stopped.replies());
    assertEquals(List.of(new ChatItem.Unreadable(false, true)), stopped.items());
  }

  @Test
  void aRequestIsAnsweredAsItsStepIs() {
    var asked = heard("02b-relay-stdio", 16);
    var allow = new ChatWire.Reply(ChatWire.Decision.ALLOW, null, List.of());

    assertEquals(items("02b-relay-stdio", 16), asked.items());
    assertEquals(
        LiveReply.reply(LiveStream.read(Recorded.line("02b-relay-stdio", 16)), allow, CHAT),
        asked.asked().answer(allow));
  }

  @Test
  void aMessageAStopAndAModeAreClaudeCodesOwnLines() {
    assertEquals(List.of(LiveStream.userMessage("hi")), WIRE.message("hi"));
    assertEquals(LiveChat.interrupt("stop-1"), WIRE.interrupt("stop-1"));
    assertEquals(LiveChat.setMode("mode-1", "plan"), WIRE.mode("mode-1", "plan"));
  }

  @Test
  void aRequestIsNeverHeardWithoutHowItIsAnswered() {
    var request = items("02b-relay-stdio", 16);

    assertEquals(
        "A request is heard with how it is answered.",
        assertThrows(IllegalArgumentException.class, () -> HarnessChat.Heard.of(request))
            .getMessage());
  }
}
