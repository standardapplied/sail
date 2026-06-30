/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GracefulShutdownTest {

  @Test
  void closesEveryResourceInReverseRegistrationOrder() {
    var order = new ArrayList<String>();
    var shutdown =
        new GracefulShutdown()
            .register(() -> order.add("db"))
            .register(() -> order.add("server"))
            .register(() -> order.add("sweeper"));

    shutdown.shutdown();

    assertEquals(
        List.of("sweeper", "server", "db"),
        order,
        "resources must close newest-first, mirroring try-with-resources");
  }

  @Test
  void isIdempotentSoTheHookAndTheNormalPathCannotDoubleClose() {
    var closes = new AtomicInteger();
    var shutdown = new GracefulShutdown().register(closes::incrementAndGet);

    shutdown.shutdown();
    shutdown.shutdown();

    assertEquals(1, closes.get(), "the close body must run exactly once across repeated shutdowns");
  }

  @Test
  void isolatesAFailingCloseSoTheRemainingResourcesStillClose() {
    var order = new ArrayList<String>();
    var shutdown =
        new GracefulShutdown()
            .register(() -> order.add("db"))
            .register(
                () -> {
                  throw new IllegalStateException("boom");
                })
            .register(() -> order.add("sweeper"));

    shutdown.shutdown();

    assertEquals(
        List.of("sweeper", "db"),
        order,
        "a single failing close must not strand the other resources");
  }

  @Test
  void concurrentShutdownsCloseEachResourceExactlyOnce() throws Exception {
    var closes = new AtomicInteger();
    var shutdown = new GracefulShutdown().register(closes::incrementAndGet);
    var gate = new CyclicBarrier(2);
    Runnable racer =
        () -> {
          try {
            gate.await();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          shutdown.shutdown();
        };
    var a = new Thread(racer);
    var b = new Thread(racer);

    a.start();
    b.start();
    a.join();
    b.join();

    assertEquals(1, closes.get(), "a SIGTERM hook racing the finally must still close once");
  }

  @Test
  void announcesCleanCompletionExactlyOnceSoAStopIsObservable() {
    var shutdown = new GracefulShutdown().register(() -> {}).register(() -> {});
    var captured = new ByteArrayOutputStream();
    var originalOut = System.out;
    System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      shutdown.shutdown();
      shutdown.shutdown();
    } finally {
      System.setOut(originalOut);
    }

    var output = captured.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("shutdown] closed 2"), "a clean stop must be observable: " + output);
    assertEquals(
        1,
        output.lines().filter(l -> l.contains("shutdown] closed")).count(),
        "the completion line must print exactly once");
  }
}
