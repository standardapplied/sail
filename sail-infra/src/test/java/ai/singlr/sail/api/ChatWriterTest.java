/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.AgentProcess;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A chat process's one writer: lines go in the order handed, on a thread of its own; it holds at
 * most {@link ChatWriter#MOST_LINES} lines and {@link ChatWriter#MOST_BYTES} bytes waiting and
 * refuses the next at once; a refused line refuses every one after it unwritten; a line whose
 * taking cannot be recorded is the first refused; and a line not taken a bound after its write
 * began is said, on the host's clock.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ChatWriterTest {

  private static final Instant NOW = Instant.parse("2026-11-28T09:00:00Z");
  private static final String UNRECORDED = "Sail could not record what it wrote.";

  private final Object guard = new Object();
  private final Stdin stdin = new Stdin();
  private final List<String> told = new CopyOnWriteArrayList<>();
  private final AtomicReference<Instant> clock = new AtomicReference<>(NOW);
  private final ChatWriter writer =
      new ChatWriter(guard, stdin, clock::get, Duration.ofSeconds(20), UNRECORDED);

  @AfterEach
  void tearDown() {
    stdin.release.release(ChatWriter.MOST_LINES);
    writer.close();
  }

  /** Hands {@code text}, telling {@link #told} how it went. Under the guard, as a process does. */
  private long offer(String text) {
    return offer(text, () -> told.add("taken " + text));
  }

  private long offer(String text, Runnable taken) {
    synchronized (guard) {
      return writer.offer(
          text, "why " + text, taken, said -> told.add("refused " + text + ": " + said));
    }
  }

  @Test
  void aWriterHoldsAtMostItsLinesAndRefusesTheNextAtOnce() throws Exception {
    stdin.holds(1);
    writer.start("sail-test-writer");
    var places = new ArrayList<Long>();
    for (var line = 0; line < ChatWriter.MOST_LINES; line++) {
      places.add(offer("line " + line));
    }
    stdin.awaitHeld();

    var refused = offer("one too many");

    assertEquals(
        List.of("refused one too many: why one too many"),
        told,
        "refused at once, nothing else told");
    assertFalse(writer.wrote(refused));
    stdin.release.release();
    assertTrue(writer.wrote(places.getLast()));
    assertEquals(
        places.stream().map(place -> "line " + (place - 1)).toList(), stdin.written, "in order");
  }

  @Test
  void aWriterHoldsAtMostItsBytesAndRefusesTheNextAtOnce() throws Exception {
    stdin.holds(1);
    writer.start("sail-test-writer");
    var first = offer("a".repeat(ChatWriter.MOST_BYTES - 2));
    stdin.awaitHeld();

    var fits = offer("é");
    var over = offer("b");

    assertEquals(List.of("refused b: why b"), told);
    stdin.release.release();
    assertTrue(writer.wrote(fits));
    assertTrue(writer.wrote(first));
    assertFalse(writer.wrote(over));
    assertEquals(List.of("é"), stdin.written.subList(1, 2));
  }

  @Test
  void aLineRefusedRefusesEveryOneAfterItUnwritten() {
    stdin.refusesTheFirst();
    writer.start("sail-test-writer");

    var first = offer("first");
    var second = offer("second");
    var third = offer("third");

    assertFalse(writer.wrote(third));
    assertFalse(writer.wrote(first));
    assertFalse(writer.wrote(second));
    assertEquals(
        List.of(
            "refused first: why first", "refused second: why second", "refused third: why third"),
        told);
    assertEquals(List.of(), stdin.written, "nothing after the refused line reached the harness");
    assertFalse(writer.wrote(offer("later")), "nor anything handed later");
  }

  /**
   * A line whose taking Sail cannot record (its store write throws) is the first refused, in words
   * that say so: the writer goes on, every later line is refused, and stdin is closed.
   */
  @Test
  void aLineWhoseTakingCannotBeRecordedIsTheFirstRefused() throws Exception {
    writer.start("sail-test-writer");

    var first =
        offer(
            "first",
            () -> {
              throw new IllegalStateException("database is locked");
            });
    var second = offer("second");
    var refused = writer.wrote(second);
    writer.close();

    assertFalse(writer.wrote(first));
    assertFalse(refused, "a line after it is refused, not taken for going");
    stdin.awaitClosed();
    assertEquals(List.of("first"), stdin.written, "the second was never written");
    assertEquals(List.of("refused first: " + UNRECORDED, "refused second: why second"), told);
  }

  @Test
  void aLineNotTakenItsBoundAfterItsWriteBeganIsSaid() throws Exception {
    stdin.holds(1);
    writer.start("sail-test-writer");
    var bound = Duration.ofMinutes(5);

    offer("stuck");
    stdin.awaitHeld();

    assertNull(writer.stalled(NOW.plus(bound).minusNanos(1), bound));
    assertEquals("why stuck", writer.stalled(NOW.plus(bound), bound));
    stdin.release.release();
    writer.written();
    assertNull(writer.stalled(NOW.plus(bound), bound), "once taken, nothing is stalled");
  }

  /** A line waiting behind a slow one has its whole bound from when its own write began. */
  @Test
  void aLineWaitingBehindASlowOneIsNotStalledForItsSlowness() throws Exception {
    stdin.holds(2);
    writer.start("sail-test-writer");
    var bound = Duration.ofMinutes(5);
    offer("slow");
    offer("behind");
    stdin.awaitHeld();

    clock.set(NOW.plus(Duration.ofMinutes(4)));
    stdin.release.release();
    stdin.awaitHeld();

    assertNull(writer.stalled(NOW.plus(bound), bound), "its own write began a minute ago");
    assertEquals("why behind", writer.stalled(NOW.plus(Duration.ofMinutes(4)).plus(bound), bound));
  }

  @Test
  void aWriterInterruptedClosesItsStdinAndTakesNoNewLine() throws Exception {
    writer.start("sail-test-writer");
    var writing = new CompletableFuture<Thread>();
    offer("first", () -> writing.complete(Thread.currentThread()));
    var thread = writing.get(20, TimeUnit.SECONDS);

    thread.interrupt();

    assertTrue(thread.join(Duration.ofSeconds(20)), "the writer ended");
    assertTrue(stdin.closed, "its stdin closed");
    assertFalse(writer.wrote(offer("late")));
    assertEquals(List.of("refused late: why late"), told);
  }

  /** A harness's stdin that can hold its first writes, or refuse the first. */
  private static final class Stdin implements AgentProcess.LineWriter {

    final List<String> written = new CopyOnWriteArrayList<>();
    final Semaphore release = new Semaphore(0);
    private final Semaphore held = new Semaphore(0);
    private final CountDownLatch shut = new CountDownLatch(1);
    private volatile int holding;
    private volatile boolean refuses;
    private volatile boolean closed;

    void holds(int writes) {
      holding = writes;
    }

    void refusesTheFirst() {
      refuses = true;
    }

    void awaitHeld() throws InterruptedException {
      assertTrue(held.tryAcquire(20, TimeUnit.SECONDS), "nothing was written");
    }

    void awaitClosed() throws InterruptedException {
      assertTrue(shut.await(20, TimeUnit.SECONDS), "its stdin was never closed");
    }

    @Override
    public void write(String line) throws IOException {
      if (refuses) {
        refuses = false;
        throw new IOException("Broken pipe");
      }
      if (holding > 0) {
        holding--;
        held.release();
        try {
          if (!release.tryAcquire(20, TimeUnit.SECONDS)) {
            throw new IOException("the test never let the line go");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException(interrupted);
        }
      }
      written.add(line);
    }

    @Override
    public void close() {
      closed = true;
      shut.countDown();
    }
  }
}
