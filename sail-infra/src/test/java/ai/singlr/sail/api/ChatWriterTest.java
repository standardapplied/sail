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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A chat process's one writer: lines go in the order handed, on a thread of its own; it holds at
 * most {@link ChatWriter#MOST_LINES} lines and {@link ChatWriter#MOST_BYTES} bytes waiting and
 * refuses the next at once; a refused line refuses every one after it unwritten; and a line not
 * taken a bound after it was handed is said, on the host's clock.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ChatWriterTest {

  private static final Instant NOW = Instant.parse("2026-11-28T09:00:00Z");

  private final Object guard = new Object();
  private final Stdin stdin = new Stdin();
  private final List<String> told = new CopyOnWriteArrayList<>();
  private final ChatWriter writer = new ChatWriter(guard, stdin, () -> NOW, Duration.ofSeconds(20));

  @AfterEach
  void tearDown() {
    stdin.release.countDown();
    writer.close();
  }

  /** Hands {@code text}, telling {@link #told} how it went. Under the guard, as a process does. */
  private long offer(String text) {
    synchronized (guard) {
      return writer.offer(
          text, "why " + text, () -> told.add("taken " + text), () -> told.add("refused " + text));
    }
  }

  @Test
  void aWriterHoldsAtMostItsLinesAndRefusesTheNextAtOnce() throws Exception {
    stdin.holdsTheFirst();
    writer.start("sail-test-writer");
    var places = new ArrayList<Long>();
    for (var line = 0; line < ChatWriter.MOST_LINES; line++) {
      places.add(offer("line " + line));
    }
    stdin.awaitHeld();

    var refused = offer("one too many");

    assertEquals(List.of("refused one too many"), told, "refused at once, nothing else told");
    assertFalse(writer.wrote(refused));
    stdin.release.countDown();
    assertTrue(writer.wrote(places.getLast()));
    assertEquals(
        places.stream().map(place -> "line " + (place - 1)).toList(), stdin.written, "in order");
  }

  @Test
  void aWriterHoldsAtMostItsBytesAndRefusesTheNextAtOnce() throws Exception {
    stdin.holdsTheFirst();
    writer.start("sail-test-writer");
    var first = offer("a".repeat(ChatWriter.MOST_BYTES - 2));
    stdin.awaitHeld();

    var fits = offer("é");
    var over = offer("b");

    assertEquals(List.of("refused b"), told);
    stdin.release.countDown();
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
    assertEquals(List.of("refused first", "refused second", "refused third"), told);
    assertEquals(List.of(), stdin.written, "nothing after the refused line reached the harness");
    assertFalse(writer.wrote(offer("later")), "nor anything handed later");
  }

  @Test
  void aLineNotTakenItsBoundAfterItWasHandedIsSaid() throws Exception {
    stdin.holdsTheFirst();
    writer.start("sail-test-writer");
    var bound = Duration.ofMinutes(5);

    offer("stuck");
    stdin.awaitHeld();

    assertNull(writer.stalled(NOW.plus(bound).minusNanos(1), bound));
    assertEquals("why stuck", writer.stalled(NOW.plus(bound), bound));
    stdin.release.countDown();
    writer.written();
    assertNull(writer.stalled(NOW.plus(bound), bound), "once taken, nothing is stalled");
  }

  @Test
  void aWriterInterruptedClosesItsStdinAndTakesNoLine() throws Exception {
    var thread = writer.start("sail-test-writer");

    thread.interrupt();

    assertTrue(thread.join(Duration.ofSeconds(20)), "the writer ended");
    assertTrue(stdin.closed, "its stdin closed");
    assertFalse(writer.wrote(offer("late")));
    assertEquals(List.of("refused late"), told);
  }

  /** A harness's stdin that can hold its first write, or refuse it. */
  private static final class Stdin implements AgentProcess.LineWriter {

    final List<String> written = new CopyOnWriteArrayList<>();
    final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch held = new CountDownLatch(1);
    private volatile boolean holds;
    private volatile boolean refuses;
    private volatile boolean closed;

    void holdsTheFirst() {
      holds = true;
    }

    void refusesTheFirst() {
      refuses = true;
    }

    void awaitHeld() throws InterruptedException {
      assertTrue(held.await(20, TimeUnit.SECONDS), "nothing was written");
    }

    @Override
    public void write(String line) throws IOException {
      if (refuses) {
        refuses = false;
        throw new IOException("Broken pipe");
      }
      if (holds) {
        holds = false;
        held.countDown();
        try {
          if (!release.await(20, TimeUnit.SECONDS)) {
            throw new IOException("the test never let the first line go");
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
    }
  }
}
