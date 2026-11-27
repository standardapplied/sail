/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.engine.ChatItem;
import ai.singlr.sail.engine.ChatWire;
import ai.singlr.sail.harness.SessionForm;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChatStore;
import ai.singlr.sail.store.FdeStore;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The caller's own chats in a workspace, as its sessions listing says them beside its terminals:
 * open ones, and ended ones for {@link #ENDED_KEPT}, newest first, at most {@link #MOST}; an older
 * one's log is still read by its id. Each one's state is read from its log alone ({@link #state}):
 * no process is asked or started and no lock taken, so whether a process holds it is never a state.
 */
final class ChatListing {

  /** How long an ended chat stays listed, as an ended terminal does. */
  static final Duration ENDED_KEPT = Duration.ofMinutes(10);

  /** The most chats one listing says. */
  static final int MOST = 200;

  private static final String FAILED = ChatWire.kind(ChatItem.Failed.class);
  private static final String TURN_ENDED = ChatWire.kind(ChatItem.TurnEnded.class);
  private static final List<String> ENDINGS =
      List.of(FAILED, ChatWire.kind(ChatItem.Interrupted.class), TURN_ENDED);

  /**
   * What a log the listing only reads wakes when it logs, which it never does: a hint and nothing
   * more, so no reader is woken.
   */
  private static final Runnable WAKES_NO_ONE = Thread::onSpinWait;

  /** Where a chat stands, as a person is shown it. */
  enum State {
    ENDED,
    NEEDS_YOU,
    WORKING,
    ERROR,
    YOUR_TURN,
    IDLE;

    String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** One chat as its workspace's sessions listing says it. */
  record Row(ChatStore.Chat chat, String opener, State state)
      implements WorkspaceSessionsResponse.Listed {

    @Override
    public String name() {
      return chat.id();
    }

    @Override
    public Instant createdAt() {
      return chat.createdAt();
    }

    @Override
    public Map<String, Object> toMap() {
      var m = new LinkedHashMap<String, Object>();
      m.put("name", chat.id());
      m.put("form", SessionForm.CHAT.wire());
      m.put("live", !chat.ended());
      m.put("state", state.wire());
      m.put("opener", opener);
      m.put("agent", WorkspaceSessionsResponse.agent(chat.harness(), chat.mode(), chat.inside()));
      m.put("created_at", DateTimeUtils.format(chat.createdAt()));
      return m;
    }
  }

  private final ChatStore store;
  private final FdeStore fdes;
  private final ChatTiming timing;

  ChatListing(ChatStore store, FdeStore fdes, ChatTiming timing) {
    this.store = Objects.requireNonNull(store, "store");
    this.fdes = Objects.requireNonNull(fdes, "fdes");
    this.timing = Objects.requireNonNull(timing, "timing");
  }

  /**
   * The bound caller's chats in workspace {@code workspace} of {@code project}: only a person's
   * own, an admin's too, and none for a credential that acts for no person.
   */
  List<Row> of(String project, String workspace) {
    var person = Access.attendee(Actor.current()).flatMap(fdes::byHandle);
    if (person.isEmpty()) {
      return List.of();
    }
    var since = timing.clock().get().minus(ENDED_KEPT);
    return store.listed(person.get().id(), project, workspace, since, MOST).stream()
        .map(chat -> new Row(chat, person.get().handle(), state(chat)))
        .toList();
  }

  /**
   * Where {@code chat} stands, by the first that holds: ended; a turn open with a request {@link
   * ChatLog#open} holds, or with none; its last turn failed ({@code failed} just before its {@code
   * turn_ended}); a turn ended otherwise, stopped or cut; no turn yet.
   */
  private State state(ChatStore.Chat chat) {
    if (chat.ended()) {
      return State.ENDED;
    }
    if (chat.turnOpen()) {
      return new ChatLog(store, chat.id(), timing, WAKES_NO_ONE).open().requests().isEmpty()
          ? State.WORKING
          : State.NEEDS_YOU;
    }
    var last = store.lastKinds(chat.id(), ENDINGS, 2);
    if (last.isEmpty()) {
      return State.IDLE;
    }
    return last.equals(List.of(TURN_ENDED, FAILED)) ? State.ERROR : State.YOUR_TURN;
  }
}
