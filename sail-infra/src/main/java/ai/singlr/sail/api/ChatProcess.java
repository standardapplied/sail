/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Ids;
import ai.singlr.sail.engine.AgentProcess;
import ai.singlr.sail.engine.ChatItem;
import ai.singlr.sail.engine.ChatWire;
import ai.singlr.sail.engine.LiveChat;
import ai.singlr.sail.engine.LiveReply;
import ai.singlr.sail.engine.LiveStream;
import ai.singlr.sail.engine.LiveStream.Step;
import ai.singlr.sail.harness.HarnessChat;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The one process that holds a chat's conversation: what it writes is read a line at a time on a
 * thread of its own, each line's items logged ({@link ChatLog}) before anyone is shown them; the
 * person's messages handed to it one a turn, in the order they were logged, a message said while a
 * turn is open held in the log until the turn ends. A message is handed at least once: it is marked
 * handed after the harness took it, so a crash between the two hands it again. A request it cannot
 * read whole is refused at once ({@link #refuseNow}), or, naming nothing to answer, its turn is
 * stopped ({@link #stopTurn}). At a turn's end what it left open is closed, and when the process
 * ends, however it ends, a turn it left open is cut ({@link ChatLog#cut}): its requests die with
 * it, and a resume replays none. However its reading stops, the process is ended. Each request a
 * person can answer is held, its step whole, until it is answered once ({@link #answer}),
 * withdrawn, its turn ends or the process ends: nothing else ever answers one. Every field a line
 * or a message moves is guarded by it, which is also the guard a turn starts under.
 */
final class ChatProcess {

  /** What the host does once a process ended. */
  @FunctionalInterface
  interface Host {

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
  private final ChatEnd end;
  private final String broken;
  private final Map<String, Step> asked = new HashMap<>();
  private ChatItem.Session lastSession;
  private Instant lastActivity;
  private Instant stopAsked;
  private boolean turnOpen;
  private boolean took;
  private boolean reading = true;

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
    this.end = new ChatEnd(this, session, agent, handle, timing);
    this.broken =
        agent
            + "'s process stopped taking what Sail wrote to it, so Sail ended it; your next message"
            + " starts it again.";
    this.lastActivity = timing.clock().get();
  }

  String session() {
    return session;
  }

  /** The harness's name as a person reads it. */
  String agent() {
    return agent;
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
    } catch (IOException | RuntimeException broken) {
      System.err.println(
          "  [chat] chat " + session + " stopped being read: " + broken.getMessage());
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
      case Step.Unreadable unreadable -> refuseNow(unreadable);
      default -> {}
    }
    for (var item : LiveChat.items(step, chat)) {
      if (item instanceof ChatItem.Request request) {
        asked.put(request.id(), step);
      }
      take(item);
    }
    notifyAll();
  }

  /**
   * Refuses the request {@code unreadable} names at once, no person asked: one that could not be
   * read whole, or one a person could not be shown whole, which the agent is told to split; its
   * turn goes on. One that names no id to answer stops the turn ({@link #stopTurn}), which would
   * otherwise wait on it.
   */
  private void refuseNow(Step.Unreadable unreadable) {
    if (unreadable.requestId() == null) {
      stopTurn();
    } else {
      write(LiveChat.refuseUnreadable(unreadable), broken);
    }
  }

  /**
   * Stops the open turn; the process stays, and takes the next message as a new turn. One that has
   * not stopped {@link ChatTiming#stopBound} after is ended ({@link #overBound}).
   */
  private void stopTurn() {
    stopAsked = timing.clock().get();
    write(LiveChat.interrupt(Ids.newId().toString()), broken);
  }

  /**
   * The mode the harness said it is in, which a resume launches in; its first chat mode when what
   * it said is no mode's name a launch may pass on; none while the process ends, whose word no
   * longer sets the mode the next one starts in.
   */
  private String inForce(String said) {
    if (end.ending()) {
      return null;
    }
    try {
      return new HarnessChat.Launch(said, null, null).mode();
    } catch (IllegalArgumentException unsafe) {
      return chat.modes().getFirst().name();
    }
  }

  /**
   * Logs {@code item}: a turn's session only when it says something the last did not, since the
   * harness says it whole at every turn; a turn's end with what it left open closed, then the next
   * message waiting handed on.
   */
  private void take(ChatItem item) {
    if (item instanceof ChatItem.Withdrawn withdrawn && asked.remove(withdrawn.id()) == null) {
      return;
    }
    if (item instanceof ChatItem.Session said) {
      if (said.equals(lastSession)) {
        return;
      }
      lastSession = said;
    }
    if (item instanceof ChatItem.TurnEnded ended) {
      asked.clear();
      log.turnEnded(ended);
      turnOpen = false;
      stopAsked = null;
      handNext();
      return;
    }
    log.log((Record) item);
  }

  /**
   * Logs what the person said, and hands it to the process at once unless a turn is open, the
   * process is ending or no longer read, or an earlier message still waits, as each turn's end
   * hands the next; one the process is not handed waits for the turn's end or the next process.
   *
   * @throws ApiException {@code ended}, when the person ended the chat
   */
  synchronized ChatHost.Sent send(String text, ApiException ended) {
    if (end.chatEnded()) {
      throw ended;
    }
    var queued = !takes() || !log.unhanded().isEmpty();
    var seq = log.log(new ChatWire.Message(text, queued));
    if (takes()) {
      handNext();
    }
    return new ChatHost.Sent(seq, queued);
  }

  /** Hands the oldest message waiting, when no turn is open: what a process started for it does. */
  synchronized void resume() {
    if (takes()) {
      handNext();
    }
  }

  /**
   * Whether the process takes a message now: no turn is open, it is not ending, and it is still
   * read, so nothing it answers goes unread. Called under the monitor.
   */
  private boolean takes() {
    return !turnOpen && writable();
  }

  /** Whether the process takes a line now: it is not ending, and it is still read. */
  private boolean writable() {
    return !end.ending() && reading;
  }

  /**
   * Answers the request {@code id} it holds with {@code reply}, the person {@code by}'s, once: the
   * step is taken out, {@code answered} logged, then the answer written, so the log never says less
   * than the agent was told. Returns the seq it was logged as, or 0, logging and writing nothing,
   * when it holds no such request.
   *
   * @throws ApiException {@code withdrawn} when it holds it but takes no answer: it is ending, no
   *     longer read or its turn is being stopped, or the answer was not taken, said in the cut of
   *     the turn
   * @throws IllegalArgumentException for a reply the request does not take, which leaves it held
   */
  synchronized long answer(String id, LiveReply.Reply reply, String by, ApiException withdrawn) {
    var step = asked.get(id);
    if (step == null) {
      return 0;
    }
    if (!writable() || stopAsked != null) {
      throw withdrawn;
    }
    var line = LiveReply.reply(step, reply, chat);
    asked.remove(id);
    var seq =
        log.log(new ChatWire.Answered(id, reply.decision(), reply.message(), reply.answers(), by));
    if (!write(
        line,
        agent
            + " did not get your answer: its process stopped taking what Sail wrote to it, so Sail"
            + " ended it; your next message starts it again.")) {
      throw withdrawn;
    }
    return seq;
  }

  /**
   * Stops the open turn, once: an interrupt is written only when none is in flight and the process
   * takes it. Returns whether a turn is stopping, one ending with its process included.
   */
  synchronized boolean stop() {
    if (!turnOpen) {
      return false;
    }
    if (stopAsked == null && writable()) {
      stopTurn();
    }
    return true;
  }

  /**
   * Puts the process in {@code mode}, the person {@code by}'s choice, between turns: logged, then
   * asked of the harness under an id of its own, which its answer names; one that takes no line, or
   * does not take this one, keeps it as the mode the next process starts in. Returns the seq it was
   * logged as.
   *
   * @throws ApiException {@code turnOpen} while a turn is open
   */
  synchronized long setMode(String mode, String by, ApiException turnOpen) {
    if (this.turnOpen) {
      throw turnOpen;
    }
    if (!writable()) {
      log.said(null, mode);
      return log.log(new ChatWire.ModeSet(mode, null, by));
    }
    var request = Ids.newId().toString();
    var seq = log.log(new ChatWire.ModeSet(mode, request, by));
    if (!write(LiveChat.setMode(request, mode), broken)) {
      log.said(null, mode);
    }
    return seq;
  }

  /**
   * Hands the oldest message not yet handed to the process. A process that no longer reads is
   * ended, and the message waits for the next.
   */
  private void handNext() {
    var next = log.unhanded().stream().findFirst();
    if (next.isEmpty()) {
      return;
    }
    var message = ChatLog.fields(next.get());
    if (!write(LiveStream.userMessage((String) message.get("text")), broken)) {
      return;
    }
    took = true;
    log.handed(next.get().seq());
    if (Boolean.TRUE.equals(message.get("queued"))) {
      log.log(new ChatWire.Delivered(next.get().seq()));
    }
    turnOpen = true;
    log.turn(true);
  }

  /**
   * Writes {@code line} on the process's stdin; returns whether it took it. A process that no
   * longer reads begins its end at once, so the turn it cuts says {@code why} however soon its
   * output ends, and is ended on a thread of its own. Called under the monitor.
   */
  private boolean write(String line, String why) {
    try {
      handle.stdin().write(line);
      lastActivity = timing.clock().get();
      return true;
    } catch (IOException refused) {
      if (!end.ending()) {
        end.begin(why);
      }
      Thread.ofVirtual()
          .name("sail-chat-broken-" + session)
          .start(() -> end.end(timing.grace().afterInt(), why));
      return false;
    }
  }

  /** How the process ends, and whether it is over. */
  ChatEnd end() {
    return end;
  }

  /** Whether the process was handed a message. */
  synchronized boolean took() {
    return took;
  }

  /**
   * Ends the process, in one step with deciding it, when it has sat with no turn open and nothing
   * waiting for its idle while at {@code now}: its stdin is closed, so no message is handed to it
   * from then on and a turn it begins itself after is its last. Returns whether it is ending so;
   * {@link #awaitIdleEnd} then sees it out.
   */
  synchronized boolean endIfIdle(Instant now) {
    if (turnOpen
        || end.ending()
        || !log.unhanded().isEmpty()
        || now.isBefore(lastActivity.plus(maxIdle))) {
      return false;
    }
    end.begin(
        "Sail ended "
            + agent
            + "'s process after "
            + maxIdle.toMinutes()
            + " minutes with nothing to do; your next message starts it again.");
    return true;
  }

  /**
   * Sees out an idle end ({@link #endIfIdle}): the process is given its idle grace to exit, a turn
   * it began itself is never cut, and only then is it signalled. Returns whether it is over.
   */
  boolean awaitIdleEnd() {
    return end.awaitIdle(() -> turnOpen);
  }

  /**
   * Whether the process must be ended at {@code now}: its turn not stopped {@code bound} after Sail
   * asked it to stop. A request is never held to a time: only an answer, or the harness withdrawing
   * it, ends its wait, for days if need be.
   */
  synchronized boolean overBound(Instant now, Duration bound) {
    return turnOpen && stopAsked != null && !now.isBefore(stopAsked.plus(bound));
  }

  /**
   * The reading is done, however it stopped: the process takes no message from then on; one whose
   * exit is not then known is ended as any is, its stdin closed and signalled, so none outlives its
   * reader; a turn it left open is cut, said why; and the host is told before the process is over,
   * so an end waited for is done.
   */
  private void finished() {
    synchronized (this) {
      reading = false;
    }
    var exit = end.seenOut();
    try {
      synchronized (this) {
        try {
          log.flush();
          if (turnOpen) {
            log.cut(end.why(exit));
          }
        } finally {
          turnOpen = false;
          end.exited(exit);
        }
      }
    } finally {
      try {
        host.gone(this);
      } finally {
        end.done();
      }
    }
  }
}
