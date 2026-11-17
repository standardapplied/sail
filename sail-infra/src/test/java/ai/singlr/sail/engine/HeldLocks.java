/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Another change holding a lock while a test acts, with no sleep: latches and bounded waits. */
final class HeldLocks {

  private HeldLocks() {}

  /** Runs {@code act} while another thread holds {@code lock}, and lets it go after. */
  static void whileHeld(Path lock, Callable<Void> act) throws Exception {
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var holder = Executors.newSingleThreadExecutor();
    try {
      var holding =
          holder.submit(
              () -> {
                try (var mutex = FileMutex.acquire(lock)) {
                  held.countDown();
                  release.await();
                }
                return null;
              });
      assertTrue(held.await(10, TimeUnit.SECONDS), "the other change took the lock");
      try {
        act.call();
      } finally {
        release.countDown();
      }
      holding.get(10, TimeUnit.SECONDS);
    } finally {
      holder.shutdownNow();
    }
  }

  /**
   * A clock past {@link Workspaces#CREATE_WAIT} at each reading: a wait for a held lock gives up.
   */
  static LongSupplier pastEveryWait() {
    var now = new AtomicLong();
    return () -> now.addAndGet(Workspaces.CREATE_WAIT.toNanos() + 1);
  }
}
