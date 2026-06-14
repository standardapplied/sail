/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedVirtualExecutorTest {

  @Test
  void rejectsNonPositiveCap() {
    assertThrows(IllegalArgumentException.class, () -> new BoundedVirtualExecutor(0));
    assertThrows(IllegalArgumentException.class, () -> new BoundedVirtualExecutor(-1));
  }

  @Test
  void exposesConfiguredCap() {
    try (var ex = new BoundedVirtualExecutor(4)) {
      assertEquals(4, ex.maxConcurrent());
      assertEquals(0, ex.inFlight());
      assertEquals(0L, ex.rejectedCount());
    }
  }

  @Test
  void acceptsTasksBelowCap() throws Exception {
    try (var ex = new BoundedVirtualExecutor(2)) {
      var ran = new AtomicInteger();
      var done = new CountDownLatch(2);
      ex.tryRun(
          () -> {
            ran.incrementAndGet();
            done.countDown();
          });
      ex.tryRun(
          () -> {
            ran.incrementAndGet();
            done.countDown();
          });
      assertTrue(done.await(5, TimeUnit.SECONDS));
      assertEquals(2, ran.get());
      assertEquals(0L, ex.rejectedCount());
    }
  }

  @Test
  void rejectsWhenCapReached() throws Exception {
    try (var ex = new BoundedVirtualExecutor(1)) {
      var blocking = new CountDownLatch(1);
      var started = new CountDownLatch(1);
      assertTrue(
          ex.tryRun(
              () -> {
                started.countDown();
                try {
                  blocking.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }));
      assertTrue(started.await(5, TimeUnit.SECONDS));
      assertEquals(1, ex.inFlight());

      assertFalse(ex.tryRun(() -> {}));
      assertEquals(1L, ex.rejectedCount());

      blocking.countDown();
    }
  }

  @Test
  void permitReleasedWhenTaskFails() throws Exception {
    try (var ex = new BoundedVirtualExecutor(1)) {
      var seen = new CountDownLatch(1);
      ex.tryRun(
          () -> {
            seen.countDown();
            throw new RuntimeException("boom");
          });
      assertTrue(seen.await(5, TimeUnit.SECONDS));

      Thread.sleep(50);
      var ran = new CountDownLatch(1);
      assertTrue(ex.tryRun(ran::countDown), "permit should have been released on failure");
      assertTrue(ran.await(5, TimeUnit.SECONDS));
    }
  }
}
