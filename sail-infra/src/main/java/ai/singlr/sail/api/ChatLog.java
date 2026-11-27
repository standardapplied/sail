/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ChatItem;
import ai.singlr.sail.engine.ChatWire;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.store.ChatStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One chat's log, as its host writes it: each entry, an agent's {@link ChatItem} or Sail's own
 * ({@link ChatWire.Own}), appended in order and its chat's waiting readers woken; the text of one
 * block gathered for a {@link LiveMembers#DELTA_WINDOW} into one entry; and what a turn left open
 * read back from the log ({@link #open}), so a turn's end and a cut turn close exactly that,
 * whether its process is still here or a restart took it. A turn's end and a cut are each logged
 * whole or not at all, with the turn closed in the same write: a crash never logs one twice.
 */
final class ChatLog {

  /** What closes a tool row whose result no line carried: one too long to read, or none. */
  static final String UNREAD = "Sail did not read what this call gave back.";

  private static final String TOOL = ChatWire.kind(ChatItem.Tool.class);
  private static final String DONE = ChatWire.kind(ChatItem.ToolDone.class);
  private static final String WITHDRAWN = ChatWire.kind(ChatItem.Withdrawn.class);
  private static final String TURN_ENDED = ChatWire.kind(ChatItem.TurnEnded.class);
  private static final String MESSAGE = ChatWire.kind(ChatWire.Message.class);

  /**
   * The kinds that open or close a row or a request: these three and every {@link
   * ChatItem.Request}.
   */
  private static final List<String> OPENING =
      List.of(
          TOOL,
          DONE,
          WITHDRAWN,
          ChatWire.kind(ChatItem.Permission.class),
          ChatWire.kind(ChatItem.Question.class),
          ChatWire.kind(ChatItem.Plan.class));

  /**
   * What a chat's open turn left open, as its log says since the turn before it ended: the tool
   * rows with no result ({@code rows}, a row's id to the subagent it is under) and the requests
   * nobody answered or withdrew ({@code requests}, each to when it was logged).
   */
  record Open(Map<String, String> rows, Map<String, Instant> requests) {}

  private final ChatStore store;
  private final String session;
  private final ChatTiming timing;
  private final Runnable arrived;
  private ChatItem.Text pending;
  private Instant pendingSince = Instant.MIN;

  /** Chat {@code session}'s log in {@code store}; {@code arrived} wakes a reader waiting. */
  ChatLog(ChatStore store, String session, ChatTiming timing, Runnable arrived) {
    this.store = Objects.requireNonNull(store, "store");
    this.session = Objects.requireNonNull(session, "session");
    this.timing = Objects.requireNonNull(timing, "timing");
    this.arrived = Objects.requireNonNull(arrived, "arrived");
  }

  /** {@code item} as a reader is given it: its seq, when, its type and its fields. */
  static Map<String, Object> wire(ChatStore.Item item) {
    var wire = new LinkedHashMap<String, Object>();
    wire.put("seq", item.seq());
    wire.put("at", DateTimeUtils.format(item.at()));
    wire.put(ChatWire.TYPE, item.kind());
    wire.putAll(fields(item));
    return wire;
  }

  /** What {@code item} says, read within the bound every item is logged within. */
  static Map<String, Object> fields(ChatStore.Item item) {
    return YamlUtil.parseJsonLine(item.payload(), ChatStore.MAX_PAYLOAD_CHARS);
  }

  /**
   * Logs {@code entry}, the text of one block gathered for a {@link LiveMembers#DELTA_WINDOW}
   * first; returns the number it was logged as, 0 for text still being gathered.
   */
  synchronized long log(Record entry) {
    var now = timing.clock().get();
    if (entry instanceof ChatItem.Text text
        && pending != null
        && !text.newParagraph()
        && Objects.equals(text.under(), pending.under())
        && now.isBefore(pendingSince.plus(LiveMembers.DELTA_WINDOW))) {
      pending =
          new ChatItem.Text(
              pending.under(),
              pending.text() + text.text(),
              pending.newParagraph(),
              pending.clipped() || text.clipped());
      return 0;
    }
    flush();
    if (entry instanceof ChatItem.Text text) {
      pending = text;
      pendingSince = now;
      Thread.ofVirtual().name("sail-chat-text-" + session).start(this::flushLater);
      return 0;
    }
    return append(entry);
  }

  /** Logs the text being gathered, if any, now. */
  synchronized void flush() {
    if (pending != null) {
      var text = pending;
      pending = null;
      append(text);
    }
  }

  private void flushLater() {
    try {
      timing.delay().pause(LiveMembers.DELTA_WINDOW);
    } catch (InterruptedException stopping) {
      Thread.currentThread().interrupt();
      return;
    }
    flush();
  }

  private long append(Record entry) {
    var seq =
        store.append(
            session,
            ChatWire.kind(entry.getClass()),
            YamlUtil.dumpJson(ChatWire.fields(entry)),
            timing.clock().get());
    arrived.run();
    return seq;
  }

  /**
   * The harness named its conversation, or its mode, or both; either null when it did not. A
   * conversation is kept only when a resume may name it.
   */
  void said(String conversation, String mode) {
    store.said(
        session,
        conversation != null && Harness.isSafeSessionId(conversation) ? conversation : null,
        mode);
  }

  /** A turn opened, or ended. */
  void turn(boolean open) {
    store.turn(session, open);
  }

  /** The person's message logged at {@code seq} went to the harness. */
  void handed(long seq) {
    store.handed(session, seq);
  }

  /** The person's messages not yet handed to the harness, oldest first. */
  List<ChatStore.Item> unhanded() {
    return store.unhanded(session, MESSAGE);
  }

  /** What the open turn left open, read from the log since the last turn ended. */
  Open open() {
    var rows = new LinkedHashMap<String, String>();
    var requests = new LinkedHashMap<String, Instant>();
    for (var item : store.since(session, TURN_ENDED, OPENING)) {
      var fields = fields(item);
      var id = (String) fields.get("id");
      if (item.kind().equals(TOOL)) {
        rows.put(id, (String) fields.get("under"));
      } else if (item.kind().equals(DONE)) {
        rows.remove(id);
      } else if (item.kind().equals(WITHDRAWN)) {
        requests.remove(id);
      } else {
        requests.put(id, item.at());
      }
    }
    return new Open(rows, requests);
  }

  /**
   * Logs the end of the open turn, {@code ended}, after closing what it left open: each tool row
   * with no result as unread, and each request the harness neither had answered nor withdrew,
   * withdrawn, since nothing can answer it once its turn is over.
   */
  synchronized void turnEnded(ChatItem.TurnEnded ended) {
    flush();
    store.together(
        () -> {
          closeOpen();
          append(ended);
          turn(false);
        });
  }

  /**
   * Cuts the open turn, whose process is gone, once: Sail says {@code why} (none when null), each
   * row with no result is closed as unread, each request withdrawn, since it died with the process,
   * and the turn ends interrupted. A turn already closed is left as it is.
   */
  synchronized void cut(String why) {
    flush();
    store.together(
        () -> {
          if (!store.find(session).map(ChatStore.Chat::turnOpen).orElse(false)) {
            return;
          }
          if (why != null) {
            append(new ChatWire.Told(why));
          }
          closeOpen();
          append(new ChatItem.Interrupted());
          turn(false);
        });
  }

  private void closeOpen() {
    var open = open();
    open.rows()
        .forEach((id, under) -> append(new ChatItem.ToolDone(under, id, UNREAD, false, false)));
    open.requests().keySet().forEach(id -> append(new ChatItem.Withdrawn(id)));
  }
}
