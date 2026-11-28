/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.AgentProcess;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** How a chat's process is seen out once it sat idle, its stdin closed. */
class ChatEndTest {

  /**
   * I7, M4: a turn the process begins itself after its idle end closed its stdin is never cut:
   * however short its grace, the end waits on that turn, and signals only once it has ended.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void anIdleEndSignalsOnlyOnceATurnTheProcessBeganItselfHasEnded() throws Exception {
    var guard = new Object();
    var open = new AtomicBoolean(true);
    var signals = new CopyOnWriteArrayList<String>();
    var end =
        new ChatEnd(
            guard,
            () -> {},
            "Claude Code",
            handle(signal -> signals.add(signal + (open.get() ? " in the turn" : " after it"))),
            timing());
    var asked = new CountDownLatch(3);

    var idle =
        Thread.ofVirtual()
            .start(
                () ->
                    end.awaitIdle(
                        () -> {
                          asked.countDown();
                          return open.get();
                        }));
    assertTrue(asked.await(20, TimeUnit.SECONDS), "it waits on the turn the process began");
    synchronized (guard) {
      open.set(false);
      guard.notifyAll();
    }
    idle.join(Duration.ofSeconds(20));

    assertEquals(List.of("INT after it", "TERM after it"), signals);
  }

  /** Time whose every grace is a millisecond: one that waits on nothing would signal at once. */
  private static ChatTiming timing() {
    var instant = Duration.ofMillis(1);
    return new ChatTiming(
        Instant::now,
        window -> {},
        (arrivals, within) -> {},
        Duration.ofSeconds(25),
        Duration.ofMinutes(5),
        new ProcessEnd.Grace(instant, instant, instant, instant));
  }

  /** A process that never exits on its own, each signal it is sent told to {@code signalled}. */
  private static AgentProcess.Handle handle(Consumer<AgentProcess.Signal> signalled) {
    return new AgentProcess.Handle() {
      @Override
      public AgentProcess.LineWriter stdin() {
        return new AgentProcess.LineWriter() {
          @Override
          public void write(String line) {}

          @Override
          public void close() {}
        };
      }

      @Override
      public AgentProcess.LineReader stdout() {
        return () -> null;
      }

      @Override
      public OptionalInt pid() {
        return OptionalInt.empty();
      }

      @Override
      public void signal(AgentProcess.Signal signal) {
        signalled.accept(signal);
      }

      @Override
      public OptionalInt waitFor(Duration timeout) {
        return OptionalInt.empty();
      }
    };
  }
}
