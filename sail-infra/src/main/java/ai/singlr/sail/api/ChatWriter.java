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
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The one writer of a chat process's stdin: each line handed to it is written whole, in the order
 * handed, by a thread of its own, so no lock of the chat's or its process's is held while a harness
 * that stopped reading blocks a write. It is in one of three states: taking lines; closed, when it
 * writes those waiting and takes no new one ({@link #close}), stdin closed after them; or refusing
 * from a place on, when no line at or after it is written. At most {@link #MOST_LINES} lines and
 * {@link #MOST_BYTES} bytes wait at once: a line past either is refused at once, as one the harness
 * did not take, and so is every line after a refused one, unwritten. How each line went is told
 * under its process's guard once its write is over, never during it; a line whose taking cannot be
 * told (a store write that throws) is refused, the first, so its process ends at once and no line
 * after it is left waiting. Time is the host's: a line not taken within a bound of its write's
 * start is said ({@link #stalled}) for its host to end the process, and a caller waits for its line
 * at most {@code patience}, then goes on.
 */
final class ChatWriter {

  /** The most lines that wait to be written. */
  static final int MOST_LINES = 64;

  /**
   * The most bytes that wait to be written: 4 MiB, counted as the UTF-8 each line is written in,
   * never in characters, so a character outside ASCII counts two to four bytes.
   */
  static final int MOST_BYTES = 4 << 20;

  /**
   * A line handed: its place in the order, its text and size, what a turn its refusal ends says
   * ({@code why}), and what is told of it, under the guard.
   */
  private record Line(
      long place, String text, int bytes, String why, Runnable taken, Consumer<String> refused) {}

  private final Object guard;
  private final AgentProcess.LineWriter stdin;
  private final Supplier<Instant> clock;
  private final Duration patience;
  private final String unrecorded;
  private final Deque<Line> waiting = new ArrayDeque<>();
  private long handed;
  private long told;
  private long refusedFrom;
  private Instant writing;
  private int bytes;
  private boolean closed;

  /**
   * The writer of {@code stdin}, telling how each line went under {@code guard}, on {@code clock};
   * a caller waits for its line at most {@code patience}; a line whose taking cannot be told is
   * refused said {@code unrecorded}.
   */
  ChatWriter(
      Object guard,
      AgentProcess.LineWriter stdin,
      Supplier<Instant> clock,
      Duration patience,
      String unrecorded) {
    this.guard = Objects.requireNonNull(guard, "guard");
    this.stdin = Objects.requireNonNull(stdin, "stdin");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.patience = Objects.requireNonNull(patience, "patience");
    this.unrecorded = Objects.requireNonNull(unrecorded, "unrecorded");
  }

  /** Starts writing, on a thread of its own named {@code name}. */
  void start(String name) {
    Thread.ofVirtual().name(name).start(this::writeAll);
  }

  /**
   * Hands {@code text} to be written after every line handed before it: {@code taken} runs once it
   * is written, {@code refused} once it is not, given what the turn its refusal ends says, {@code
   * why} when the harness did not take it. One the writer holds no room for, or that follows a
   * refused one, is refused now. Returns its place. Called under the guard.
   */
  long offer(String text, String why, Runnable taken, Consumer<String> refused) {
    var size = text.getBytes(StandardCharsets.UTF_8).length;
    Line line;
    boolean now;
    synchronized (this) {
      line = new Line(++handed, text, size, why, taken, refused);
      now =
          closed
              || refusedFrom != 0
              || waiting.size() >= MOST_LINES
              || bytes + (long) size > MOST_BYTES;
      if (now) {
        refuseFrom(line.place());
      } else {
        waiting.addLast(line);
        bytes += size;
        notifyAll();
      }
    }
    if (now) {
      refused.accept(why);
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
      return !refuses(place);
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
   * What the line being written says of a turn its end cuts, when its write began {@code bound} or
   * more before {@code now} and is not over; null otherwise. A line waiting behind it is held to
   * the bound only from when its own write begins, never for the slowness of those ahead of it.
   */
  synchronized String stalled(Instant now, Duration bound) {
    return writing != null && !now.isBefore(writing.plus(bound)) ? waiting.getFirst().why() : null;
  }

  /**
   * No line is handed from now on: once those waiting are told, stdin is closed and the thread
   * ends, so a close never goes before a line handed ahead of it. Never waits.
   */
  synchronized void close() {
    closed = true;
    notifyAll();
  }

  private synchronized boolean refuses(long place) {
    return refusedFrom != 0 && place >= refusedFrom;
  }

  private synchronized void refuseFrom(long place) {
    if (refusedFrom == 0 || place < refusedFrom) {
      refusedFrom = place;
    }
  }

  private boolean settled(long place) {
    return told >= place || refuses(place);
  }

  private void writeAll() {
    try {
      Line line;
      while ((line = next()) != null) {
        tell(line, !refuses(line.place()) && took(line.text()));
      }
    } finally {
      stdin.close();
    }
  }

  /** The line to write next, its write begun now; null once closed with none left. */
  private synchronized Line next() {
    while (waiting.isEmpty() && !closed) {
      try {
        wait();
      } catch (InterruptedException stopping) {
        Thread.currentThread().interrupt();
        closed = true;
      }
    }
    writing = waiting.isEmpty() ? null : clock.get();
    return waiting.peekFirst();
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

  /**
   * Tells how {@code line} went, {@code written} or not, under the guard, then counts it told
   * however its telling went: one whose taking cannot be told is refused from its place on, said
   * {@link #unrecorded}.
   */
  private void tell(Line line, boolean written) {
    try {
      synchronized (guard) {
        if (written && ran(line.taken())) {
          return;
        }
        refuseFrom(line.place());
        ran(() -> line.refused().accept(written ? unrecorded : line.why()));
      }
    } finally {
      settle(line);
    }
  }

  private synchronized void settle(Line line) {
    waiting.pollFirst();
    bytes -= line.bytes();
    told = line.place();
    writing = null;
    notifyAll();
  }

  /** Runs {@code callback}; whether it ran through, what stopped it said. */
  private static boolean ran(Runnable callback) {
    try {
      callback.run();
      return true;
    } catch (RuntimeException unrecorded) {
      System.err.println(
          "  [chat] Sail could not record how a line it wrote went: " + unrecorded.getMessage());
      return false;
    }
  }
}
