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
import ai.singlr.sail.harness.HarnessChat;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * The one process that holds a chat's conversation: what it writes is read a line at a time on a
 * thread of its own, each line's items logged ({@link ChatLog}) before anyone is shown them; the
 * person's messages handed to it one a turn, in the order they were logged, a message said while a
 * turn is open held in the log until the turn ends. A request it cannot read whole is refused at
 * once ({@link #refuseNow}), or, naming nothing to answer, its turn is stopped ({@link #stopTurn}).
 * At a turn's end the rows it left open are closed, and when the process ends, however it ends, a
 * turn it left open is cut ({@link ChatLog#cut}): its requests die with it, and a resume replays
 * none. Every field a line or a message moves is guarded by it.
 */
final class ChatProcess {

  /** How long a process whose output ended is given to report its exit code. */
  private static final Duration EXIT_GRACE = Duration.ofSeconds(5);

  /** What the host does for a process beside driving it. */
  interface Host {

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
  private final ChatLog log;
  private final HarnessChat chat;
  private final AgentProcess.Handle handle;
  private final ChatTiming timing;
  private final Duration maxIdle;
  private final Host host;
  private ChatItem.Session lastSession;
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
      ChatLog log,
      HarnessChat chat,
      AgentProcess.Handle handle,
      ChatTiming timing,
      Duration maxIdle,
      Host host) {
    this.session = Objects.requireNonNull(session, "session");
    this.agent = Objects.requireNonNull(agent, "agent");
    this.log = Objects.requireNonNull(log, "log");
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
        log.said(init.sessionId(), inForce(init.mode()));
        if (!turnOpen) {
          turnOpen = true;
          log.turn(true);
        }
      }
      case Step.Status status when status.mode() != null -> log.said(null, inForce(status.mode()));
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
      log.closeRows();
    }
    log.log((Record) item);
    if (item instanceof ChatItem.TurnEnded) {
      turnOpen = false;
      log.turn(false);
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
    var queued = turnOpen || ending || !log.unhanded().isEmpty();
    var seq = log.log(new ChatWire.Message(text, attachments, queued));
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
    var next = log.unhanded().stream().findFirst();
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
    log.handed(next.get().seq());
    if (Boolean.TRUE.equals(message.get("queued"))) {
      log.log(new ChatWire.Delivered(next.get().seq()));
    }
    lastActivity = timing.clock().get();
    turnOpen = true;
    log.turn(true);
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
      log.log(new ChatWire.Told(unreadable.getMessage() + " The message went without its images."));
      return List.of();
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
        && log.unhanded().isEmpty()
        && !now.isBefore(lastActivity.plus(maxIdle));
  }

  /**
   * Whether its open turn holds a request that has waited {@code bound} unanswered at {@code now}.
   */
  synchronized boolean unanswered(Instant now, Duration bound) {
    return turnOpen
        && !ending
        && log.open().requests().values().stream()
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
        log.flush();
        if (turnOpen) {
          log.cut(ending ? endReason : died(exitCode()));
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
}
