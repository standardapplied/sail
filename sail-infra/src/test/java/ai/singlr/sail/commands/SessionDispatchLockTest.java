/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The per-project claim lock: a second holder of the same project waits for the first release, the
 * lock is a real OS file lock (so a separate process is excluded, not just a thread), and projects
 * lock independently.
 */
class SessionDispatchLockTest {

  @Test
  void aSecondAcquireOfTheSameProjectWaitsForTheFirstRelease(@TempDir Path dir) throws Exception {
    var first = SessionDispatchLock.acquire(dir, "acme");
    var acquired = new CountDownLatch(1);
    var second =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (var hold = SessionDispatchLock.acquire(dir, "acme")) {
                    acquired.countDown();
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });

    awaitParked(second);
    assertEquals(1, acquired.getCount(), "the second holder waits while the first holds");

    first.close();
    assertTrue(acquired.await(30, TimeUnit.SECONDS), "the release admits the waiter");
    second.join();
  }

  @Test
  void theLockIsHeldOnDiskWhileHeldAndFreeOnceReleased(@TempDir Path dir) throws Exception {
    var file = dir.resolve("acme.lock");
    var hold = SessionDispatchLock.acquire(dir, "acme");
    try (var probe = FileChannel.open(file, StandardOpenOption.WRITE)) {
      assertThrows(
          OverlappingFileLockException.class,
          probe::tryLock,
          "the JVM holds the file lock the kernel shows every other process");
    }
    hold.close();
    try (var probe = FileChannel.open(file, StandardOpenOption.WRITE);
        var free = probe.tryLock()) {
      assertNotNull(free, "released on close");
    }
  }

  @Test
  void differentProjectsLockIndependently(@TempDir Path dir) throws Exception {
    try (var acme = SessionDispatchLock.acquire(dir, "acme");
        var beta = SessionDispatchLock.acquire(dir, "beta")) {
      assertNotNull(acme);
      assertNotNull(beta);
    }
    try (var again = SessionDispatchLock.acquire(dir, "acme")) {
      assertNotNull(again, "a released project lock is reacquirable");
    }
  }

  static void awaitParked(Thread thread) {
    while (thread.isAlive() && thread.getState() != Thread.State.WAITING) {
      Thread.onSpinWait();
    }
  }
}
