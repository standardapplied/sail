/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Ids;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentProcess;
import ai.singlr.sail.engine.ChatItem;
import ai.singlr.sail.engine.ChatWire;
import ai.singlr.sail.engine.LiveChat;
import ai.singlr.sail.engine.LiveStream;
import ai.singlr.sail.engine.LiveStream.Step;
import ai.singlr.sail.harness.Harness;
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
 * until the turn ends. A request it cannot read whole is refused at once, or, naming nothing to
 * answer, its turn is stopped. What a turn left open is read from the log ({@link #open}): at the
 * turn's end its rows with no result are closed, and when the process ends, however it ends, a turn
 * it left open is cut ({@link #cut}): its requests die with it, and a resume replays none. Every
 * field a line or a message moves is guarded by it.
 */
final class ChatProcess {

  /** How long a process whose output ended is given to report its exit code. */
  private static final Duration EXIT_GRACE = Duration.ofSeconds(5);

  /** What closes a tool row whose result no line carried: one too long to read, or none. */
  static final String UNREAD = "Sail did not read what this call gave back.";

  private static final String TOOL = ChatWire.kind(ChatItem.Tool.class);
  private static final String DONE = ChatWire.kind(ChatItem.ToolDone.class);
  private static final String WITHDRAWN = ChatWire.kind(ChatItem.Withdrawn.class);
  private static final String TURN_ENDED = ChatWire.kind(ChatItem.TurnEnded.class);

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

  /**
   * What a chat's open turn left open, as its log says since the turn before it ended: the tool
   * rows with no result ({@code rows}, a row's id to the subagent it is under) and the requests
   * nobody answered or withdrew ({@code requests}, each to when it was logged).
   */
  record Open(Map<String, String> rows, Map<String, Instant> requests) {}

  private final String session;
  private final String agent;
  private final ChatStore store;
  private final HarnessChat chat;
  private final AgentProcess.Handle handle;
  private final ChatHost.Timing timing;
  private final Duration maxIdle;
  private final Host host;
  private ChatItem.Session lastSession;
  private ChatItem.Text pending;
  private Instant pendingSince = Instant.MIN;
  private Instant lastActivity;
  private boolean turnOpen;
  private boolean ending;
  private String endReason;
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
    switch (step) {
      case Step.Init init -> {
        store.said(session, conversation(init.sessionId()), inForce(init.mode()));
        if (!turnOpen) {
          turnOpen = true;
          store.turn(session, true);
        }
      }
      case Step.Status status when status.mode() != null ->
          store.said(session, null, inForce(status.mode()));
      case Step.Unreadable unreadable -> refuseNow(unreadable.requestId());
      default -> {}
    }
    for (var item : LiveChat.items(step, chat)) {
      take(item);
    }
  }

  /**
   * Refuses the request {@code requestId} at once, no person asked, so its turn goes on; when it
   * named no id to answer, stops the turn ({@link #stopTurn}), which would otherwise wait on it.
   */
  private void refuseNow(String requestId) {
    if (requestId == null) {
      stopTurn();
    } else {
      write(LiveChat.refuseUnreadable(requestId));
    }
  }

  /** Stops the open turn; the process stays, and takes the next message as a new turn. */
  private void stopTurn() {
    write(LiveChat.interrupt(Ids.newId().toString()));
  }

  /** The conversation the harness named, when it is one a resume may name; else none. */
  private static String conversation(String said) {
    return said != null && Harness.isSafeSessionId(said) ? said : null;
  }

  /**
   * The mode the harness said it is in, which a resume launches in; its first chat mode when what
   * it said is no mode's name a launch may pass on.
   */
  private String inForce(String said) {
    try {
      return new HarnessChat.Launch(said, null, null).mode();
    } catch (IllegalArgumentException unsafe) {
      return chat.modes().getFirst().name();
    }
  }

  /**
   * Logs {@code item}: a turn's session only when it says something the last did not, since the
   * harness says it whole at every turn; a turn's end after the rows it left open are closed, then
   * the next message waiting handed on.
   */
  private void take(ChatItem item) {
    if (item instanceof ChatItem.Session said) {
      if (said.equals(lastSession)) {
        return;
      }
      lastSession = said;
    }
    if (item instanceof ChatItem.TurnEnded) {
      flush();
      closeRows(store, session, open(store, session).rows(), timing.clock().get(), host::arrived);
    }
    log((Record) item);
    if (item instanceof ChatItem.TurnEnded) {
      turnOpen = false;
      store.turn(session, false);
      handNext(0, List.of());
    }
  }

  /**
   * Logs what the person said, and hands it to the process at once unless a turn is open or an
   * earlier message still waits, as each turn's end hands the next; {@code images} are its
   * attachments, read as it was sent.
   */
  synchronized ChatHost.Sent send(
      String text, List<String> attachments, List<LiveStream.Image> images) {
    var queued = turnOpen || ending || !unhanded().isEmpty();
    var seq = log(new ChatWire.Message(text, attachments, queued));
    if (!turnOpen && !ending) {
      handNext(seq, images);
    }
    return new ChatHost.Sent(seq, queued);
  }

  private List<ChatStore.Item> unhanded() {
    return store.unhanded(session, ChatWire.kind(ChatWire.Message.class));
  }

  /**
   * Hands the oldest message not yet handed to the process: {@code images} when it is {@code
   * fresh}, the one just sent, else its attachments read again, any no longer readable said and
   * left out. A process that no longer reads is ended, and the message waits for the next.
   */
  private void handNext(long fresh, List<LiveStream.Image> images) {
    var next = unhanded().stream().findFirst();
    if (next.isEmpty()) {
      return;
    }
    var message = YamlUtil.parseJsonLine(next.get().payload(), LiveStream.MAX_LINE_CHARS);
    var attachments =
        ((List<?>) message.get("attachments")).stream().map(String.class::cast).toList();
    var shown = next.get().seq() == fresh ? images : again(attachments);
    var text = (String) message.get("text");
    if (!write(LiveStream.userMessage(text, shown.toArray(LiveStream.Image[]::new)))) {
      return;
    }
    store.handed(session, next.get().seq());
    if (Boolean.TRUE.equals(message.get("queued"))) {
      log(new ChatWire.Delivered(next.get().seq()));
    }
    lastActivity = timing.clock().get();
    turnOpen = true;
    store.turn(session, true);
  }

  /**
   * Writes {@code line} on the process's stdin; returns whether it took it. A process that no
   * longer reads is ended, on a thread of its own.
   */
  private boolean write(String line) {
    try {
      handle.stdin().write(line);
      return true;
    } catch (IOException broken) {
      Thread.ofVirtual()
          .name("sail-chat-broken-" + session)
          .start(() -> end(timing.grace().afterInt(), null));
      return false;
    }
  }

  /**
   * The images {@code attachments} name, read again; those no longer readable said and left out.
   */
  private List<LiveStream.Image> again(List<String> attachments) {
    try {
      return host.images(attachments);
    } catch (ApiException unreadable) {
      log(new ChatWire.Told(unreadable.getMessage() + " The message went without its images."));
      return List.of();
    }
  }

  /**
   * Logs {@code entry}, the text of one block gathered for a {@link LiveMembers#DELTA_WINDOW}
   * first; returns the number it was logged as, 0 for text still being gathered.
   */
  private long log(Record entry) {
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
    return append(store, session, entry, now, host::arrived);
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
      append(store, session, text, timing.clock().get(), host::arrived);
    }
  }

  /** Whether the process is over: nothing reads it any more. */
  synchronized boolean over() {
    return over;
  }

  /** Whether the process has sat with no turn open and nothing waiting for its idle while. */
  synchronized boolean idle(Instant now) {
    return !turnOpen
        && !ending
        && unhanded().isEmpty()
        && !now.isBefore(lastActivity.plus(maxIdle));
  }

  /**
   * Whether its open turn holds a request that has waited {@code bound} unanswered at {@code now}.
   */
  synchronized boolean unanswered(Instant now, Duration bound) {
    return turnOpen
        && !ending
        && open(store, session).requests().values().stream()
            .anyMatch(since -> !now.isBefore(since.plus(bound)));
  }

  /**
   * Ends the process as {@link ProcessEnd} does, given {@code first} to end on its own; a turn it
   * leaves open is cut, Sail saying why in {@code why} (null when the person ended it, which needs
   * no saying). Returns whether it is over.
   */
  boolean end(Duration first, String why) {
    synchronized (this) {
      if (over) {
        return true;
      }
      if (!ending) {
        ending = true;
        endReason = why;
        ProcessEnd.closeInput(handle, "sail-chat-end-" + session);
      }
    }
    return ProcessEnd.signalled(handle, timing.grace(), first, false, this::settled);
  }

  /** The process is done: a turn it left open is cut, said why. */
  private void finished() {
    synchronized (this) {
      try {
        flush();
        if (turnOpen) {
          cut(
              store,
              session,
              ending ? endReason : died(exitCode()),
              timing.clock().get(),
              host::arrived);
          turnOpen = false;
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
    ProcessEnd.await(this, within, () -> over);
    return over;
  }

  /**
   * Cuts the open turn of chat {@code session}, whose process is gone: Sail says {@code why} (none
   * when null), each row with no result is closed as unread, each request withdrawn, since it died
   * with the process, and the turn ends interrupted.
   */
  static void cut(ChatStore store, String session, String why, Instant at, Runnable arrived) {
    if (why != null) {
      append(store, session, new ChatWire.Told(why), at, arrived);
    }
    var open = open(store, session);
    closeRows(store, session, open.rows(), at, arrived);
    open.requests()
        .keySet()
        .forEach(id -> append(store, session, new ChatItem.Withdrawn(id), at, arrived));
    append(store, session, new ChatItem.Interrupted(), at, arrived);
    store.turn(session, false);
  }

  /** What chat {@code session}'s open turn left open, read from its log. */
  static Open open(ChatStore store, String session) {
    var rows = new LinkedHashMap<String, String>();
    var requests = new LinkedHashMap<String, Instant>();
    for (var item : store.since(session, TURN_ENDED, OPENING)) {
      var fields = YamlUtil.parseJsonLine(item.payload(), LiveStream.MAX_LINE_CHARS);
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

  private static void closeRows(
      ChatStore store, String session, Map<String, String> rows, Instant at, Runnable arrived) {
    rows.forEach(
        (id, under) ->
            append(
                store,
                session,
                new ChatItem.ToolDone(under, id, UNREAD, false, false),
                at,
                arrived));
  }

  /** Logs {@code entry} in chat {@code session} at {@code at}; returns its number. */
  private static long append(
      ChatStore store, String session, Record entry, Instant at, Runnable arrived) {
    var seq =
        store.append(
            session,
            ChatWire.kind(entry.getClass()),
            YamlUtil.dumpJson(ChatWire.fields(entry)),
            at);
    arrived.run();
    return seq;
  }
}
