/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Actor;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
  void aThrowingPassEvenAnErrorIsLoggedAndDoesNotPoisonLaterRuns() {
    var runs = new AtomicInteger();
    try (var pass =
        new PeriodicPass(
            "test",
            () -> {
              switch (runs.incrementAndGet()) {
                case 1 -> throw new IllegalStateException("boom");
                case 2 -> throw new AssertionError("an Error must not cancel the schedule");
                default -> {}
              }
            })) {

      assertTrue(pass.runIfIdle());
      assertTrue(pass.runIfIdle());
      assertTrue(pass.runIfIdle());
      assertEquals(3, runs.get());
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

  @Test
  void aPassRunsAsThisBoxsMachinery() {
    var actor = new AtomicReference<Actor>();
    try (var pass = new PeriodicPass("test", () -> actor.set(Actor.current()))) {
      assertTrue(pass.runIfIdle());
    }
    assertEquals(Actor.system(), actor.get());
  }
}
