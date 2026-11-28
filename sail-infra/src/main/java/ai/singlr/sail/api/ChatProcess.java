/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Ids;
import ai.singlr.sail.engine.AgentProcess;
import ai.singlr.sail.engine.ChatItem;
import ai.singlr.sail.engine.ChatWire;
import ai.singlr.sail.harness.HarnessChat;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The one process that holds a chat's conversation, spoken with through its harness's wire alone
 * ({@link HarnessChat.Wire}): what it writes is read a line at a time on a thread of its own, each
 * line's items logged ({@link ChatLog}) before anyone is shown them; the person's messages handed
 * to it one a turn, in the order they were logged, a message said while a turn is open held in the
 * log until the turn ends. A message is handed at least once: it is marked handed after the harness
 * took it, so a crash between the two hands it again. What a line asks back is written at once, and
 * a turn it says waits on what no answer reaches is stopped ({@link #stopTurn}). At a turn's end
 * what it left open is closed, and when the process ends, however it ends, a turn it left open is
 * cut ({@link ChatLog#cut}): its requests die with it, and a resume replays none. However its
 * reading stops, the process is ended. Each request a person can answer is held, with how its wire
 * answers it, until it is answered once ({@link #answer}), withdrawn, its turn ends or the process
 * ends: nothing else ever answers one. Every field a line or a message moves is guarded by it,
 * which is also the guard a turn starts under; what it writes is decided under it and written by
 * its {@link ChatWriter}, never under it, so a harness that stops reading holds up no lock.
 */
final class ChatProcess {

  /**
   * A person's answer logged as {@code seq}, and its line's place in the writer ({@link #wrote}).
   */
  record Answered(long seq, long line) {}

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
  private final HarnessChat.Wire wire;
  private final AgentProcess.Handle handle;
  private final ChatTiming timing;
  private final Duration maxIdle;
  private final Host host;
  private final ChatEnd end;
  private final ChatWriter writer;
  private final String broken;
  private final Map<String, HarnessChat.Asked> asked = new HashMap<>();
  private ChatItem.Session lastSession;
  private Instant lastActivity;
  private Instant stopAsked;
  private boolean turnOpen;
  private boolean took;
  private boolean reading = true;
  private long handing;

  /**
   * The process {@code handle} of chat {@code session}, with {@code agent}, the harness's name as a
   * person reads it, that {@code chat} speaks for over {@code wire}; it ends after {@code maxIdle}
   * with no turn open.
   */
  ChatProcess(
      String session,
      String agent,
      ChatLog log,
      HarnessChat chat,
      HarnessChat.Wire wire,
      AgentProcess.Handle handle,
      ChatTiming timing,
      Duration maxIdle,
      Host host) {
    this.session = Objects.requireNonNull(session, "session");
    this.agent = Objects.requireNonNull(agent, "agent");
    this.log = Objects.requireNonNull(log, "log");
    this.chat = Objects.requireNonNull(chat, "chat");
    this.wire = Objects.requireNonNull(wire, "wire");
    this.handle = Objects.requireNonNull(handle, "handle");
    this.timing = Objects.requireNonNull(timing, "timing");
    this.maxIdle = Objects.requireNonNull(maxIdle, "maxIdle");
    this.host = Objects.requireNonNull(host, "host");
    this.writer = new ChatWriter(this, handle.stdin(), timing.clock(), timing.grace().afterInt());
    this.end = new ChatEnd(this, writer::close, agent, handle, timing);
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

  /**
   * Starts the process's writer and its reading, each on a thread of its own, its wire's opening
   * handed first.
   */
  void read() {
    writer.start("sail-chat-write-" + session);
    synchronized (this) {
      wire.opening().forEach(line -> hand(line, broken, () -> {}, () -> {}));
    }
    Thread.ofVirtual().name("sail-chat-" + session).start(this::readToTheEnd);
  }

  /**
   * Reads the process to its end, one line at a time, each line's own answers written before the
   * next is read, though never waited for past the writer's patience, so a write that blocks never
   * stops what the harness says from being read.
   */
  private void readToTheEnd() {
    try {
      String line;
      while ((line = handle.stdout().read()) != null) {
        writer.wrote(line(line));
      }
    } catch (IOException | RuntimeException broken) {
      System.err.println(
          "  [chat] chat " + session + " stopped being read: " + broken.getMessage());
    } finally {
      finished();
    }
  }

  /**
   * Takes {@code raw}, a line the process wrote, as its wire reads it: the conversation and mode it
   * named, a turn it began, a stop or an answer it needs at once, then its items. Returns the place
   * of the last line it handed the writer, 0 for none.
   */
  private synchronized long line(String raw) {
    lastActivity = timing.clock().get();
    var heard = wire.read(raw);
    if (heard.said() != null) {
      log.said(heard.said().conversation(), inForce(heard.said().mode()));
    }
    if (heard.began() && !turnOpen) {
      turnOpen = true;
      log.turn(true);
    }
    var last = heard.stop() ? stopTurn() : 0L;
    for (var reply : heard.replies()) {
      last = Math.max(last, hand(reply, broken, () -> {}, () -> {}));
    }
    for (var item : heard.items()) {
      if (item instanceof ChatItem.Request request) {
        asked.put(request.id(), heard.asked());
      }
      last = Math.max(last, take(item));
    }
    notifyAll();
    return last;
  }

  /**
   * Stops the open turn; the process stays, and takes the next message as a new turn. One that has
   * not stopped {@link ChatTiming#stopBound} after is ended ({@link #overBound}). Returns its
   * line's place.
   */
  private long stopTurn() {
    stopAsked = timing.clock().get();
    return hand(wire.interrupt(Ids.newId().toString()), broken, () -> {}, () -> {});
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
   * message waiting handed on. Returns the place of the line it handed the writer, 0 for none.
   */
  private long take(ChatItem item) {
    if (item instanceof ChatItem.Withdrawn withdrawn && asked.remove(withdrawn.id()) == null) {
      return 0;
    }
    if (item instanceof ChatItem.Session said) {
      if (said.equals(lastSession)) {
        return 0;
      }
      lastSession = said;
    }
    if (item instanceof ChatItem.TurnEnded ended) {
      asked.clear();
      log.turnEnded(ended);
      turnOpen = false;
      stopAsked = null;
      return handNext();
    }
    log.log((Record) item);
    return 0;
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
    var queued = !takes() || !log.unhanded(handing).isEmpty();
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
   * Answers the request {@code id} it holds with {@code reply}, the person {@code by}'s, once:
   * {@code answered} logged, the request taken out, then its answer handed to the writer, so the
   * log never says less than the agent was told, and an answer that could not be logged leaves the
   * request held for the next. Returns what it logged and handed, or nothing, logging and writing
   * nothing, when it holds no such request; whether the answer was taken is the writer's to say
   * ({@link #wrote}), and one not taken is said in the cut of the turn.
   *
   * @throws ApiException {@code withdrawn} when it holds it but takes no answer: it is ending, no
   *     longer read or its turn is being stopped
   * @throws IllegalArgumentException for a reply the request does not take, which leaves it held
   */
  synchronized Optional<Answered> answer(
      String id, ChatWire.Reply reply, String by, ApiException withdrawn) {
    var request = asked.get(id);
    if (request == null) {
      return Optional.empty();
    }
    if (!writable() || stopAsked != null) {
      throw withdrawn;
    }
    var line = request.answer(reply);
    var seq =
        log.log(new ChatWire.Answered(id, reply.decision(), reply.message(), reply.answers(), by));
    asked.remove(id);
    return Optional.of(
        new Answered(
            seq,
            hand(
                line,
                agent
                    + " did not get your answer: its process stopped taking what Sail wrote to it,"
                    + " so Sail ended it; your next message starts it again.",
                () -> {},
                () -> {})));
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
    hand(wire.mode(request, mode), broken, () -> {}, () -> log.said(null, mode));
    return seq;
  }

  /**
   * Hands the oldest message waiting to the process, its turn open from then on. Once the harness
   * took it, it is marked handed; one it did not take opens no turn, and waits for the next
   * process. Returns the place of its last line in the writer, 0 for none.
   */
  private long handNext() {
    var next = log.unhanded(handing).stream().findFirst();
    if (next.isEmpty()) {
      return 0;
    }
    var seq = next.get().seq();
    var message = ChatLog.fields(next.get());
    var queued = Boolean.TRUE.equals(message.get("queued"));
    handing = seq;
    turnOpen = true;
    Runnable taken =
        () -> {
          took = true;
          log.handed(seq);
          if (queued) {
            log.log(new ChatWire.Delivered(seq));
          }
          if (turnOpen) {
            log.turn(true);
          }
        };
    Runnable refused = () -> turnOpen = false;
    var lines = wire.message((String) message.get("text"));
    if (lines.isEmpty()) {
      taken.run();
      return 0;
    }
    var last = 0L;
    for (var index = 0; index < lines.size(); index++) {
      last = hand(lines.get(index), broken, index == lines.size() - 1 ? taken : () -> {}, refused);
    }
    return last;
  }

  /**
   * Hands {@code line} to the writer: once the harness took it, {@code taken} runs; once it did
   * not, {@code refused} runs and the process begins its end at once, so the turn it cuts says
   * {@code why} however soon its output ends, and is ended on a thread of its own. Each runs under
   * the monitor. Returns the line's place. Called under the monitor.
   */
  private long hand(String line, String why, Runnable taken, Runnable refused) {
    return writer.offer(
        line,
        why,
        () -> {
          lastActivity = timing.clock().get();
          taken.run();
        },
        () -> {
          if (!end.ending()) {
            end.begin(why);
          }
          refused.run();
          Thread.ofVirtual()
              .name("sail-chat-broken-" + session)
              .start(() -> end.end(timing.grace().afterInt(), why));
        });
  }

  /**
   * Whether the harness took the line at {@code place}: waited for holding no lock, at most the
   * writer's patience, one still waiting then taken for going.
   */
  boolean wrote(long place) {
    return writer.wrote(place);
  }

  /** Waits, holding no lock, at most the writer's patience, for every line handed so far. */
  void written() {
    writer.written();
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
        || !log.unhanded(handing).isEmpty()
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
   * Why the process must be ended at {@code now}, null when it need not: a line Sail wrote it has
   * not taken {@code bound} after, said as that line says its refusal, or its turn not stopped
   * {@code bound} after Sail asked it to stop. A request is never held to a time: only an answer,
   * or the harness withdrawing it, ends its wait, for days if need be.
   */
  synchronized String overBound(Instant now, Duration bound) {
    var stalled = writer.stalled(now, bound);
    if (stalled != null) {
      return stalled;
    }
    return turnOpen && stopAsked != null && !now.isBefore(stopAsked.plus(bound))
        ? agent
            + "'s turn could not be stopped, so Sail ended its process; your next message starts"
            + " it again."
        : null;
  }

  /**
   * The reading is done, however it stopped: the process takes no message from then on; one whose
   * exit is not then known is ended as any is, its stdin closed and signalled, so none outlives its
   * reader; what it was handed is told, within the writer's patience; a turn it left open is cut,
   * said why; and the host is told before the process is over, so an end waited for is done.
   */
  private void finished() {
    synchronized (this) {
      reading = false;
    }
    var exit = end.seenOut();
    writer.close();
    writer.written();
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
