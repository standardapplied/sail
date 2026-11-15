/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A lock file: a second holder of the same file waits for the first release, the lock is a real OS
 * file lock (so a separate process is excluded, not just a thread), and files lock independently.
 */
public class FileMutexTest {

  @Test
  void aDatabaseNotYetCreatedHasNoRoundsToHoldOff(@TempDir Path dir) throws Exception {
    var database = dir.resolve("data").resolve("sail.db");

    assertNull(FileMutex.holdRounds(database));
    assertNull(FileMutex.holdRounds(null));
    assertFalse(Files.exists(database.getParent()), "nothing is created for a missing database");
  }

  @Test
  void aSecondAcquireOfTheSameFileWaitsForTheFirstRelease(@TempDir Path dir) throws Exception {
    var first = FileMutex.acquire(dir.resolve("acme.lock"));
    var acquired = new CountDownLatch(1);
    var second =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (var hold = FileMutex.acquire(dir.resolve("acme.lock"))) {
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
  void aLockFileItCreatesIsWritableByItsGroupWhateverTheUmask(@TempDir Path dir) throws Exception {
    var file = dir.resolve("sail.db.rounds.lock");

    try (var hold = FileMutex.acquire(file)) {
      assertEquals(
          PosixFilePermissions.fromString("rw-rw----"), Files.getPosixFilePermissions(file));
    }
  }

  @Test
  void aLockFileAnotherMadeIsTakenAsItIs(@TempDir Path dir) throws Exception {
    var file = Files.createFile(dir.resolve("acme.lock"));
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));

    try (var hold = FileMutex.acquire(file)) {
      assertEquals(
          PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
    }
  }

  @Test
  void theLockIsHeldOnDiskWhileHeldAndFreeOnceReleased(@TempDir Path dir) throws Exception {
    var file = dir.resolve("acme.lock");
    var hold = FileMutex.acquire(dir.resolve("acme.lock"));
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
  void theHolderTakesItsFileAgainAndTheLockDropsWithTheOutermostHold(@TempDir Path dir)
      throws Exception {
    var file = dir.resolve("acme.lock");
    var outer = FileMutex.acquire(file);

    FileMutex.acquire(file).close();

    try (var probe = FileChannel.open(file, StandardOpenOption.WRITE)) {
      assertThrows(
          OverlappingFileLockException.class,
          probe::tryLock,
          "the inner release leaves the outer hold on disk");
    }
    outer.close();
    try (var probe = FileChannel.open(file, StandardOpenOption.WRITE);
        var free = probe.tryLock()) {
      assertNotNull(free, "released with the outermost hold");
    }
  }

  @Test
  void differentFilesLockIndependently(@TempDir Path dir) throws Exception {
    try (var acme = FileMutex.acquire(dir.resolve("acme.lock"));
        var beta = FileMutex.acquire(dir.resolve("beta.lock"))) {
      assertNotNull(acme);
      assertNotNull(beta);
    }
    try (var again = FileMutex.acquire(dir.resolve("acme.lock"))) {
      assertNotNull(again, "a released lock is reacquirable");
    }
  }

  @Test
  void aBoundedAcquireOfAFreeFileTakesItAtOnceWithoutReadingTheClock(@TempDir Path dir)
      throws Exception {
    var file = dir.resolve("workspace-create.lock");

    try (var held = FileMutex.acquireUntil(file, 0, clock()).orElseThrow()) {
      try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
        assertThrows(OverlappingFileLockException.class, channel::tryLock);
      }
    }
  }

  @Test
  void aBoundedAcquireGivesUpAtItsDeadlineWhileAnotherThreadHoldsAndTakesItOnceFree(
      @TempDir Path dir) throws Exception {
    var file = dir.resolve("workspace-create.lock");
    var holding = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var holder =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (var hold = FileMutex.acquire(file)) {
                    holding.countDown();
                    release.await();
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                });
    assertTrue(holding.await(30, TimeUnit.SECONDS));

    var deadline = Duration.ofSeconds(60).toNanos();
    assertTrue(
        FileMutex.acquireUntil(file, deadline, clock(0, deadline)).isEmpty(),
        "a waiter past its deadline gives up");

    release.countDown();
    holder.join();
    try (var taken = FileMutex.acquireUntil(file, deadline, clock()).orElseThrow()) {
      assertNotNull(taken);
    }
  }

  @Test
  void aBoundedAcquireGivesUpAtItsDeadlineWhileAnotherProcessHoldsTheFile(@TempDir Path dir)
      throws Exception {
    var file = dir.resolve("workspace-create.lock");
    Files.createFile(file);
    var deadline = Duration.ofSeconds(60).toNanos();
    try (var other = FileChannel.open(file, StandardOpenOption.WRITE);
        var lock = other.lock()) {
      assertTrue(lock.isValid());
      assertTrue(FileMutex.acquireUntil(file, deadline, clock(deadline)).isEmpty());
    }
    try (var taken = FileMutex.acquireUntil(file, deadline, clock()).orElseThrow()) {
      assertNotNull(taken, "the in-process hold was let go when it gave up");
    }
  }

  /**
   * D7 across processes: a create that finds the lock held by another process asks again until it
   * is let go, and then takes it, before its deadline.
   */
  @Test
  void aBoundedAcquireTakesTheFileOnceAnotherProcessLetsItGoBeforeItsDeadline(@TempDir Path dir)
      throws Exception {
    var file = dir.resolve("workspace-create.lock");
    Files.createFile(file);
    var deadline = Duration.ofSeconds(60).toNanos();
    try (var other = FileChannel.open(file, StandardOpenOption.WRITE)) {
      var lock = other.lock();
      var reads = new AtomicInteger();
      LongSupplier releasing =
          () -> {
            if (reads.getAndIncrement() == 0) {
              try {
                lock.release();
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            }
            return 0;
          };

      try (var taken = FileMutex.acquireUntil(file, deadline, releasing).orElseThrow()) {
        assertNotNull(taken);
        assertEquals(1, reads.get(), "asked once while the other held it");
      }
    }
  }

  @Test
  void aBoundedAcquireByTheHolderTakesItsFileAgain(@TempDir Path dir) throws Exception {
    var file = dir.resolve("workspace-create.lock");

    try (var outer = FileMutex.acquire(file);
        var inner = FileMutex.acquireUntil(file, 0, clock()).orElseThrow()) {
      assertNotNull(inner);
    }
    try (var channel = FileChannel.open(file, StandardOpenOption.WRITE);
        var free = channel.tryLock()) {
      assertNotNull(free, "both holds let it go");
    }
  }

  /**
   * A wait cut short, by an interrupt or a lock file that cannot be made, holds nothing after it:
   * another thread takes the file at once.
   */
  @Test
  void aBoundedAcquireThatFailsLetsGoOfWhatItHeld(@TempDir Path dir) throws Exception {
    var file = dir.resolve("workspace-create.lock");
    Files.createFile(file);
    var deadline = Duration.ofSeconds(60).toNanos();
    try (var other = FileChannel.open(file, StandardOpenOption.WRITE)) {
      var lock = other.lock();
      LongSupplier interrupting =
          () -> {
            Thread.currentThread().interrupt();
            return 0;
          };

      assertThrows(
          InterruptedException.class, () -> FileMutex.acquireUntil(file, deadline, interrupting));
      assertFalse(Thread.interrupted(), "the interrupt was the failure's");
      lock.release();
    }
    var plain = Files.createFile(dir.resolve("plain"));
    var unmakeable = plain.resolve("x.lock");
    assertThrows(IOException.class, () -> FileMutex.acquireUntil(unmakeable, deadline, clock()));

    for (var path : List.of(file, unmakeable)) {
      var taken = new AtomicReference<Object>();
      var other =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (var held =
                        FileMutex.acquireUntil(path, deadline, clock(deadline)).orElse(null)) {
                      taken.set(held == null ? "busy" : "taken");
                    } catch (IOException | InterruptedException e) {
                      taken.set("failed: " + e.getClass().getSimpleName());
                    }
                  });
      other.join(Duration.ofSeconds(30));
      assertFalse(other.isAlive());
      assertTrue(
          !"busy".equals(taken.get()), path + ": no in-process hold was left: " + taken.get());
    }
  }

  /**
   * A clock that reads {@code readings} in turn, then the last one, never past a fifth read more.
   */
  private static LongSupplier clock(long... readings) {
    var at = new AtomicInteger();
    return () -> {
      var read = at.getAndIncrement();
      if (read >= readings.length + 5) {
        throw new IllegalStateException("the clock was read past its horizon");
      }
      return readings[Math.min(read, readings.length - 1)];
    };
  }

  public static void awaitParked(Thread thread) {
    while (thread.isAlive() && thread.getState() != Thread.State.WAITING) {
      Thread.onSpinWait();
    }
  }
}
