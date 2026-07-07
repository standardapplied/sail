/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PeriodicPassTest {

  @Test
  void aPassInFlightMakesTheNextTickANoOpInsteadOfStacking() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var pass =
        new PeriodicPass(
            "test",
            () -> {
              entered.countDown();
              await(release);
            })) {
      var first = Thread.ofVirtual().start(pass::runIfIdle);
      assertTrue(entered.await(5, TimeUnit.SECONDS));

      assertFalse(pass.runIfIdle());

      release.countDown();
      first.join();
      assertTrue(pass.runIfIdle());
    }
  }

  @Test
  void aThrowingPassIsLoggedAndDoesNotPoisonLaterRuns() {
    var runs = new AtomicInteger();
    try (var pass =
        new PeriodicPass(
            "test",
            () -> {
              if (runs.incrementAndGet() == 1) {
                throw new IllegalStateException("boom");
              }
            })) {

      assertTrue(pass.runIfIdle());
      assertTrue(pass.runIfIdle());
      assertEquals(2, runs.get());
    }
  }

  @Test
  void startSchedulesOnItsOwnSchedulerAndCloseStopsIt() {
    var pass = new PeriodicPass("test", () -> {});
    pass.start(Duration.ofHours(1));

    pass.close();

    assertTrue(pass.runIfIdle());
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
