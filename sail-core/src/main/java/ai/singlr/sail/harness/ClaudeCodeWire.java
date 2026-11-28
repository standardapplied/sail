/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.engine.LiveChat;
import ai.singlr.sail.engine.LiveReply;
import ai.singlr.sail.engine.LiveStream;
import ai.singlr.sail.engine.LiveStream.Step;
import java.util.List;

/**
 * Claude Code's side of a chat process's pipes: its streaming mode as {@link LiveStream} reads it,
 * and the lines {@link LiveChat} and {@link LiveReply} write it. It opens with nothing, since
 * Claude Code needs no {@code initialize}; a turn begins at each {@code init} it says, and ends at
 * its {@code result}; a request it cannot be shown whole is refused at once, or, naming no id to
 * refuse, stops its turn.
 */
final class ClaudeCodeWire implements HarnessChat.Wire {

  private final HarnessChat chat;

  /** The wire of a process of Claude Code's chat {@code chat}, whose words its items are in. */
  ClaudeCodeWire(HarnessChat chat) {
    this.chat = chat;
  }

  @Override
  public List<String> opening() {
    return List.of();
  }

  @Override
  public HarnessChat.Heard read(String line) {
    var step = LiveStream.read(line);
    var items = LiveChat.items(step, chat);
    return switch (step) {
      case Step.Init init ->
          new HarnessChat.Heard(
              items,
              null,
              new HarnessChat.Said(init.sessionId(), init.mode()),
              true,
              false,
              List.of());
      case Step.Status status when status.mode() != null ->
          new HarnessChat.Heard(
              items, null, new HarnessChat.Said(null, status.mode()), false, false, List.of());
      case Step.Unreadable unreadable ->
          new HarnessChat.Heard(
              items,
              null,
              null,
              false,
              unreadable.requestId() == null,
              unreadable.requestId() == null
                  ? List.of()
                  : List.of(LiveChat.refuseUnreadable(unreadable)));
      default ->
          new HarnessChat.Heard(
              items, reply -> LiveReply.reply(step, reply, chat), null, false, false, List.of());
    };
  }

  @Override
  public List<String> message(String text) {
    return List.of(LiveStream.userMessage(text));
  }

  @Override
  public String interrupt(String id) {
    return LiveChat.interrupt(id);
  }

  @Override
  public String mode(String id, String mode) {
    return LiveChat.setMode(id, mode);
  }
}
