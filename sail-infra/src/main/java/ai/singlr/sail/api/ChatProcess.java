/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentProcess;
import ai.singlr.sail.engine.ChatItem;
import ai.singlr.sail.engine.ChatWire;
import ai.singlr.sail.engine.LiveChat;
import ai.singlr.sail.engine.LiveStream;
import ai.singlr.sail.harness.HarnessChat;
import ai.singlr.sail.store.ChatStore;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * The one process that holds a chat's conversation: what it writes is read a line at a time on a
 * thread of its own, each line's items logged before anyone is shown them, the text of one block
 * gathered for a {@link LiveMembers#DELTA_WINDOW} into one item; the person's messages handed to it
 * one a turn, in the order they were logged, a message said while a turn is open held in the log
 * until the turn ends. When it ends, however it ends, a turn it left open is closed as cut, its
 * open tool rows closed and the requests it held withdrawn: they die with it, and a resume replays
 * none. Every field a line or a message moves is guarded by it.
 */
final class ChatProcess {

  /** How long a process whose output ended is given to report its exit code. */
  private static final Duration EXIT_GRACE = Duration.ofSeconds(5);

  /** What closes a tool row whose result no line carried: one too long to read, or none. */
  static final String UNREAD = "Sail did not read what this call gave back.";

  /** What the host does for a process beside driving it. */
  interface Host {

    /** An item was logged in a chat: a reader waiting for one goes on. */
    void arrived();

    /**
     * The images {@code attachments} name, read again for a message handed after it was logged.
     *
     * @throws ApiException when one is gone, or no longer readable
     */
    List<LiveStream.Image> images(List<String> attachments);

    /** {@code process} ended: nothing holds its conversation now. */
    void gone(ChatProcess process);
  }

  private final String session;
  private final String agent;
  private final ChatStore store;
  private final HarnessChat chat;
  private final AgentProcess.Handle handle;
  private final ChatHost.Timing timing;
  private final Duration maxIdle;
  private final Host host;
  private final Map<String, String> open = new LinkedHashMap<>();
  private final Map<String, Instant> held = new LinkedHashMap<>();
  private ChatItem.Session lastSession;
  private ChatItem.Text pending;
  private Instant pendingSince = Instant.MIN;
  private Instant lastActivity;
  private boolean turnOpen;
  private boolean ending;
  private boolean over;

  /**
   * The process {@code handle} of chat {@code session}, with {@code agent}, the harness's name as a
   * person reads it, that {@code chat} speaks for; it ends after {@code maxIdle} with no turn open.
   */
  ChatProcess(
      String session,
      String agent,
      ChatStore store,
      HarnessChat chat,
      AgentProcess.Handle handle,
      ChatHost.Timing timing,
      Duration maxIdle,
      Host host) {
    this.session = Objects.requireNonNull(session, "session");
    this.agent = Objects.requireNonNull(agent, "agent");
    this.store = Objects.requireNonNull(store, "store");
    this.chat = Objects.requireNonNull(chat, "chat");
    this.handle = Objects.requireNonNull(handle, "handle");
    this.timing = Objects.requireNonNull(timing, "timing");
    this.maxIdle = Objects.requireNonNull(maxIdle, "maxIdle");
    this.host = Objects.requireNonNull(host, "host");
    this.lastActivity = timing.clock().get();
  }

  String session() {
    return session;
  }

  /** Starts reading the process, on a thread of its own. */
  void read() {
    Thread.ofVirtual().name("sail-chat-" + session).start(this::readToTheEnd);
  }

  private void readToTheEnd() {
    try {
      String line;
      while ((line = handle.stdout().read()) != null) {
        line(line);
      }
    } catch (IOException broken) {
      System.err.println("  [chat] chat " + session + " stopped answering: " + broken.getMessage());
    } finally {
      finished();
    }
  }

  private synchronized void line(String raw) {
    lastActivity = timing.clock().get();
    var step = LiveStream.read(raw);
    var conversation = step instanceof LiveStream.Step.Init init ? init.sessionId() : null;
    for (var item : LiveChat.items(step, chat)) {
      take(item, conversation);
    }
  }

  /**
   * Keeps what {@code item} changes of the chat, then logs it: a turn's session only when it says
   * something the last did not, since the harness says it whole at every turn.
   */
  private void take(ChatItem item, String conversation) {
    var shown = true;
    switch (item) {
      case ChatItem.Session said -> {
        store.said(session, conversation, said.mode());
        opened();
        shown = !said.equals(lastSession);
        lastSession = said;
      }
      case ChatItem.ModeChanged changed -> store.said(session, null, changed.mode());
      case ChatItem.Tool tool -> open.put(tool.id(), tool.under());
      case ChatItem.ToolDone done -> open.remove(done.id());
      case ChatItem.Request asked -> held.put(asked.id(), timing.clock().get());
      case ChatItem.Withdrawn withdrawn -> held.remove(withdrawn.id());
      case ChatItem.TurnEnded ended -> closeRows();
      default -> {}
    }
    if (shown) {
      log(item);
    }
    if (item instanceof ChatItem.TurnEnded) {
      turnOpen = false;
      store.turn(session, false);
      handNext(0, List.of());
    }
  }

  /** A turn is open: one the person's message began, or the agent began itself. */
  private void opened() {
    if (!turnOpen) {
      turnOpen = true;
      store.turn(session, true);
    }
  }

  /** Closes each tool row still open as one whose result was not read. */
  private void closeRows() {
    open.forEach((id, under) -> log(new ChatItem.ToolDone(under, id, UNREAD, false)));
    open.clear();
  }

  /**
   * Logs what the person said, and hands it to the process at once unless a turn is open or an
   * earlier message still waits, as each turn's end hands the next; {@code images} are its
   * attachments, read as it was sent.
   */
  synchronized ChatHost.Sent send(
      String text, List<String> attachments, List<LiveStream.Image> images) {
    var queued = turnOpen || ending || !store.unhanded(session, ChatHost.MESSAGE).isEmpty();
    var seq = log(new ChatItem.Message(text, attachments, queued));
    if (!turnOpen && !ending) {
      handNext(seq, images);
    }
    return new ChatHost.Sent(seq, queued);
  }

  /**
   * Hands the oldest message not yet handed to the process: {@code images} when it is {@code
   * fresh}, the one just sent, else its attachments read again, any no longer readable said and
   * left out. A process that no longer reads is ended, and the message waits for the next.
   */
  private void handNext(long fresh, List<LiveStream.Image> images) {
    var next = store.unhanded(session, ChatHost.MESSAGE).stream().findFirst();
    if (next.isEmpty()) {
      return;
    }
    var message = YamlUtil.parseJsonLine(next.get().payload(), LiveStream.MAX_LINE_CHARS);
    var text = (String) message.get("text");
    var attachments =
        ((List<?>) message.getOrDefault("attachments", List.of()))
            .stream().map(String.class::cast).toList();
    var shown = next.get().seq() == fresh ? images : again(attachments);
    try {
      handle.stdin().write(LiveStream.userMessage(text, shown.toArray(LiveStream.Image[]::new)));
    } catch (IOException broken) {
      Thread.ofVirtual()
          .name("sail-chat-broken-" + session)
          .start(() -> end(timing.grace().afterInt(), null));
      return;
    }
    store.handed(session, next.get().seq());
    if (Boolean.TRUE.equals(message.get("queued"))) {
      log(new ChatItem.Delivered(next.get().seq()));
    }
    lastActivity = timing.clock().get();
    opened();
  }

  /**
   * The images {@code attachments} name, read again; those no longer readable said and left out.
   */
  private List<LiveStream.Image> again(List<String> attachments) {
    try {
      return host.images(attachments);
    } catch (ApiException unreadable) {
      log(new ChatItem.Told(unreadable.getMessage() + " The message went without its images."));
      return List.of();
    }
  }

  /**
   * Logs {@code item}, the text of one block gathered for a {@link LiveMembers#DELTA_WINDOW} first;
   * returns the number it was logged as, 0 for text still being gathered.
   */
  private long log(ChatItem item) {
    var now = timing.clock().get();
    if (item instanceof ChatItem.Text text
        && pending != null
        && !text.newParagraph()
        && Objects.equals(text.under(), pending.under())
        && now.isBefore(pendingSince.plus(LiveMembers.DELTA_WINDOW))) {
      pending =
          new ChatItem.Text(pending.under(), pending.text() + text.text(), pending.newParagraph());
      return 0;
    }
    flush();
    if (item instanceof ChatItem.Text text) {
      pending = text;
      pendingSince = now;
      Thread.ofVirtual().name("sail-chat-text-" + session).start(this::flushLater);
      return 0;
    }
    return append(item);
  }

  private void flushLater() {
    try {
      timing.delay().pause(LiveMembers.DELTA_WINDOW);
    } catch (InterruptedException stopping) {
      Thread.currentThread().interrupt();
      return;
    }
    synchronized (this) {
      flush();
    }
  }

  private void flush() {
    if (pending != null) {
      var text = pending;
      pending = null;
      append(text);
    }
  }

  private long append(ChatItem item) {
    var seq =
        store.append(
            session,
            ChatWire.kind(item),
            YamlUtil.dumpJson(ChatWire.fields(item)),
            timing.clock().get());
    host.arrived();
    return seq;
  }

  /** Whether the process is over: nothing reads it any more. */
  synchronized boolean over() {
    return over;
  }

  /** Whether the process has sat with no turn open and nothing waiting for its idle while. */
  synchronized boolean idle(Instant now) {
    return !turnOpen
        && !ending
        && store.unhanded(session, ChatHost.MESSAGE).isEmpty()
        && !now.isBefore(lastActivity.plus(maxIdle));
  }

  /** Whether a request it holds has waited {@code bound} unanswered at {@code now}. */
  synchronized boolean unanswered(Instant now, Duration bound) {
    return !ending && held.values().stream().anyMatch(since -> !now.isBefore(since.plus(bound)));
  }

  /**
   * Ends the process as {@link ProcessEnd} does, given {@code first} to end on its own, having said
   * why in the chat when {@code told} says (null for nothing to say). Returns whether it is over.
   */
  boolean end(Duration first, String told) {
    synchronized (this) {
      if (over) {
        return true;
      }
      if (!ending) {
        ending = true;
        if (told != null) {
          log(new ChatItem.Told(told));
        }
        ProcessEnd.closeInput(handle, "sail-chat-end-" + session);
      }
    }
    return ProcessEnd.signalled(handle, timing.grace(), first, false, this::settled);
  }

  /**
   * The process is done: a turn it left open is cut, said as such when nobody asked it to end, its
   * open rows closed and its held requests withdrawn.
   */
  private void finished() {
    synchronized (this) {
      try {
        flush();
        if (turnOpen && !ending) {
          log(new ChatItem.Told(died(exitCode())));
        }
        closeRows();
        held.keySet().forEach(id -> log(new ChatItem.Withdrawn(id)));
        held.clear();
        if (turnOpen) {
          log(new ChatItem.Interrupted());
          turnOpen = false;
          store.turn(session, false);
        }
      } finally {
        over = true;
        notifyAll();
      }
    }
    host.gone(this);
  }

  private String died(OptionalInt exit) {
    return agent
        + "'s process ended before its turn did"
        + (exit.isPresent() ? " (exit code " + exit.getAsInt() + ")" : "")
        + "; your next message starts it again.";
  }

  private OptionalInt exitCode() {
    try {
      return handle.waitFor(EXIT_GRACE);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return OptionalInt.empty();
    }
  }

  /** Waits up to {@code within} for the process to be over; returns whether it is. */
  private synchronized boolean settled(Duration within, boolean answering) {
    var deadline = System.nanoTime() + within.toNanos();
    while (!over) {
      var left = deadline - System.nanoTime();
      if (left <= 0) {
        break;
      }
      try {
        wait(Math.max(1, left / 1_000_000));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return over;
  }
}
