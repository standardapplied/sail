/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.AgentProcess;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The one writer of a chat process's stdin: each line handed to it is written whole, in the order
 * handed, by a thread of its own, so no lock of the chat's or its process's is held while a harness
 * that stopped reading blocks a write. At most {@link #MOST_LINES} lines and {@link #MOST_BYTES}
 * bytes wait at once: a line past either is refused at once, as one the harness did not take, and
 * so is every line after a refused one, unwritten. How each line went is told under its process's
 * guard once its write is over, never during it; stdin is closed only after the lines handed before
 * the close ({@link #close}). Time is the host's: a line not taken within a bound is said ({@link
 * #stalled}) for its host to end the process, and a caller waits for its line at most {@code
 * patience}, then goes on.
 */
final class ChatWriter {

  /** The most lines that wait to be written. */
  static final int MOST_LINES = 64;

  /**
   * The most bytes that wait to be written: four times the longest line Sail writes, an answer that
   * hands back a call's whole input or a message at the most a route takes.
   */
  static final int MOST_BYTES = 4 << 20;

  /**
   * A line handed: its place in the order, its text and size, when it was handed, what a turn its
   * refusal ends says ({@code why}), and what is told of it, under the guard.
   */
  private record Line(
      long place,
      String text,
      int bytes,
      Instant handed,
      String why,
      Runnable taken,
      Runnable refused) {}

  private final Object guard;
  private final AgentProcess.LineWriter stdin;
  private final Supplier<Instant> clock;
  private final Duration patience;
  private final Deque<Line> waiting = new ArrayDeque<>();
  private long handed;
  private long told;
  private long failed;
  private long refusing;
  private int bytes;
  private boolean closed;

  /**
   * The writer of {@code stdin}, telling how each line went under {@code guard}, on {@code clock};
   * a caller waits for its line at most {@code patience}.
   */
  ChatWriter(
      Object guard, AgentProcess.LineWriter stdin, Supplier<Instant> clock, Duration patience) {
    this.guard = Objects.requireNonNull(guard, "guard");
    this.stdin = Objects.requireNonNull(stdin, "stdin");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.patience = Objects.requireNonNull(patience, "patience");
  }

  /** Starts writing, on a thread of its own named {@code name}. */
  void start(String name) {
    Thread.ofVirtual().name(name).start(this::writeAll);
  }

  /**
   * Hands {@code text} to be written after every line handed before it: {@code taken} runs once it
   * is written, {@code refused} once it is not, said {@code why}. One the writer holds no room for,
   * or that follows a refused one, is refused now. Returns its place. Called under the guard.
   */
  long offer(String text, String why, Runnable taken, Runnable refused) {
    var size = text.getBytes(StandardCharsets.UTF_8).length;
    Line line;
    boolean now;
    synchronized (this) {
      line = new Line(++handed, text, size, clock.get(), why, taken, refused);
      now =
          closed
              || refusing != 0
              || failed != 0
              || waiting.size() >= MOST_LINES
              || bytes + (long) size > MOST_BYTES;
      if (now) {
        refusing = refusing == 0 ? line.place() : refusing;
      } else {
        waiting.addLast(line);
        bytes += size;
        notifyAll();
      }
    }
    if (now) {
      refused.run();
    }
    return line.place();
  }

  /**
   * Whether the line at {@code place} was taken: waits for it, never under the guard, at most the
   * patience; one still waiting then is taken for going.
   */
  boolean wrote(long place) {
    synchronized (this) {
      ProcessEnd.await(this, patience, () -> settled(place));
      return !(failed != 0 && place >= failed) && !(refusing != 0 && place >= refusing);
    }
  }

  /** Waits, never under the guard, at most the patience, for every line handed so far. */
  void written() {
    long last;
    synchronized (this) {
      last = handed;
    }
    wrote(last);
  }

  /**
   * What the line being written says of a turn its end cuts, when it was handed {@code bound} or
   * more before {@code now} and is not yet written; null otherwise.
   */
  synchronized String stalled(Instant now, Duration bound) {
    var first = waiting.peekFirst();
    return first != null && !now.isBefore(first.handed().plus(bound)) ? first.why() : null;
  }

  /**
   * No line is handed from now on: once those waiting are told, stdin is closed and the thread
   * ends, so a close never goes before a line handed ahead of it. Never waits.
   */
  synchronized void close() {
    closed = true;
    notifyAll();
  }

  private boolean settled(long place) {
    return told >= place || (refusing != 0 && place >= refusing);
  }

  private void writeAll() {
    while (true) {
      Line line;
      synchronized (this) {
        while (waiting.isEmpty() && !closed) {
          try {
            wait();
          } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            closed = true;
          }
        }
        line = waiting.peekFirst();
      }
      if (line == null) {
        stdin.close();
        return;
      }
      tell(line, failed() == 0 && took(line.text()));
    }
  }

  private synchronized long failed() {
    return failed;
  }

  /** Writes {@code text}, holding no lock; whether the harness took it. */
  private boolean took(String text) {
    try {
      stdin.write(text);
      return true;
    } catch (IOException | RuntimeException refused) {
      return false;
    }
  }

  /** Tells how {@code line} went, under the guard, then counts it told. */
  private void tell(Line line, boolean taken) {
    synchronized (guard) {
      synchronized (this) {
        if (!taken && failed == 0) {
          failed = line.place();
        }
      }
      (taken ? line.taken() : line.refused()).run();
    }
    synchronized (this) {
      waiting.pollFirst();
      bytes -= line.bytes();
      told = line.place();
      notifyAll();
    }
  }
}
