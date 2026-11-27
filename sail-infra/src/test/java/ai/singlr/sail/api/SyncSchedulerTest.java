/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.engine.FileMutex;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SyncSchedulerTest {

  private static final Duration DEBOUNCE = Duration.ofSeconds(2);
  private static final Duration TTL = Duration.ofSeconds(15);

  private final AtomicLong nanos = new AtomicLong();
  private final AtomicInteger rounds = new AtomicInteger();

  private SyncScheduler scheduler(
      QueueExecutor executor, SyncScheduler.Reconcile reconcile, SyncScheduler.Sleeper sleeper) {
    return new SyncScheduler(reconcile, DEBOUNCE, TTL, executor, nanos::get, sleeper);
  }

  private SyncScheduler scheduler(QueueExecutor executor, SyncScheduler.Reconcile reconcile) {
    return scheduler(executor, reconcile, this::advanceBy);
  }

  private void advanceBy(Duration duration) {
    nanos.addAndGet(duration.toNanos());
  }

  @Test
  void aWritesRoundPendingWhenTheBoxStopsBeingANodeIsNotRun() {
    var executor = new QueueExecutor();
    var rounds = new AtomicInteger();
    var active = new AtomicBoolean(true);
    var scheduler = scheduler(executor, rounds::incrementAndGet).onlyWhile(active::get);

    scheduler.afterWrite();
    active.set(false);
    executor.runAll();

    assertEquals(0, rounds.get(), "the round asks again as it starts");
  }

  @Test
  void anInactiveSchedulersBeatReadsNoHealthAndRunsNoRoundUntilItIsActive() {
    var rounds = new AtomicInteger();
    var healthReads = new AtomicInteger();
    var active = new AtomicBoolean(false);
    var scheduler = scheduler(new QueueExecutor(), rounds::incrementAndGet).onlyWhile(active::get);
    scheduler.useHealth(
        () -> {
          healthReads.incrementAndGet();
          return SyncStatus.unattempted("node", "sail@main");
        });

    scheduler.tick();
    assertEquals(0, healthReads.get(), "an inactive beat asks nothing");
    assertEquals(0, rounds.get());

    active.set(true);
    scheduler.tick();
    assertEquals(1, rounds.get(), "once active, the idle beat checks in");
  }

  @Test
  void manualRoundsRefreshTheFreshnessWindowAndAnInFlightRoundIsNotProbedAgain() {
    var scheduler = scheduler(new QueueExecutor(), rounds::incrementAndGet);
    var status =
        new java.util.concurrent.atomic.AtomicReference<>(
            new SyncStatus(
                "node",
                "main",
                null,
                "in_sync",
                java.time.Instant.EPOCH,
                java.time.Instant.EPOCH,
                0,
                null,
                null,
                null));
    scheduler.useHealth(status::get);
    scheduler.tick();
    assertEquals(0, rounds.get());
    advanceBy(SyncScheduler.IDLE_POLL);
    scheduler.tick();
    assertEquals(1, rounds.get());
    status.set(
        new SyncStatus(
            "node",
            "main",
            null,
            "syncing",
            java.time.Instant.EPOCH.plus(SyncScheduler.IDLE_POLL),
            java.time.Instant.EPOCH,
            0,
            null,
            null,
            null));
    advanceBy(TTL);
    scheduler.tick();
    assertEquals(1, rounds.get());
    advanceBy(Backoff.HALF_OPEN_DELAY);
    scheduler.tick();
    assertEquals(
        2, rounds.get(), "an abandoned round does not leave the node stuck syncing forever");
  }

  @Test
  void theTimerChecksInOnAnIdleNodeOnceAMinuteNotEveryReadWindow() {
    var scheduler = scheduler(new QueueExecutor(), rounds::incrementAndGet);
    scheduler.tick();
    assertEquals(1, rounds.get(), "a node that never synced checks in at once");
    scheduler.tick();
    assertEquals(1, rounds.get());
    advanceBy(TTL);
    scheduler.tick();
    assertEquals(1, rounds.get(), "the read window is a tolerance, not a poll");
    advanceBy(SyncScheduler.IDLE_POLL.minus(TTL));
    scheduler.tick();
    assertEquals(2, rounds.get(), "idle for a minute: check in");
    SyncScheduler.disabled().tick();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 5})
  void automaticProbesResumeAfterATransientHealthReadFailure(int failures) throws Exception {
    var recovered = new CountDownLatch(1);
    var reads = new AtomicInteger();
    try (var scheduler = new SyncScheduler(recovered::countDown, DEBOUNCE, TTL)) {
      scheduler.useHealth(
          () -> {
            if (reads.getAndIncrement() == 0) {
              throw new IllegalStateException("health store temporarily unavailable");
            }
            return new SyncStatus(
                "node",
                "main",
                null,
                failures == 0 ? "in_sync" : "stale",
                Instant.EPOCH,
                Instant.EPOCH,
                failures,
                null,
                null,
                null);
          });

      assertTrue(recovered.await(5, TimeUnit.SECONDS), "the timer must survive a failed tick");
      assertTrue(reads.get() >= 2);
    }
  }

  @ParameterizedTest
  @CsvSource({"1, false", "1, true", "2, false", "2, true"})
  void writesResumeAfterAHealthReadFailureAndPreserveFollowUps(int failedRead, boolean followUp) {
    var executor = new QueueExecutor();
    var scheduler = scheduler(executor, rounds::incrementAndGet);
    var reads = new AtomicInteger();
    scheduler.useHealth(
        () -> {
          if (reads.incrementAndGet() == failedRead) {
            if (followUp) {
              scheduler.afterWrite();
              scheduler.afterWrite();
            }
            throw new IllegalStateException("health store temporarily unavailable");
          }
          return SyncStatus.unattempted("node", "main");
        });

    scheduler.afterWrite();
    assertThrows(IllegalStateException.class, executor::runOne);
    assertEquals(failedRead - 1, rounds.get());
    assertEquals(followUp ? 1 : 0, executor.pending());
    executor.runAll();
    assertEquals(failedRead - 1 + (followUp ? 1 : 0), rounds.get());

    scheduler.tick();
    var beforeWrite = rounds.get();
    scheduler.afterWrite();
    assertEquals(1, executor.pending());
    executor.runAll();
    assertEquals(beforeWrite + 1, rounds.get());
    assertEquals(0, executor.pending());
  }

  @Test
  void aWriteDuringAnOpenCircuitProbesOnceAndAStoredManualSuccessClosesIt() {
    var executor = new QueueExecutor();
    var scheduler = scheduler(executor, rounds::incrementAndGet);
    var current =
        new java.util.concurrent.atomic.AtomicReference<>(
            new SyncStatus(
                "node",
                "main",
                null,
                "stale",
                java.time.Instant.EPOCH,
                null,
                5,
                "unreachable",
                Remedy.of("offline"),
                java.time.Instant.EPOCH));
    scheduler.useHealth(current::get);
    scheduler.freshenRead();
    assertEquals(0, rounds.get());
    scheduler.afterWrite();
    scheduler.afterWrite();
    executor.runAll();
    assertEquals(1, rounds.get());
    current.set(SyncStatus.unattempted("node", "main"));
    advanceBy(TTL);
    scheduler.freshenRead();
    assertEquals(2, rounds.get());
  }

  @Test
  @Timeout(value = 1, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void anInterruptedBackoffRunsNoRoundAndKeepsTheInterrupt() {
    var executor = new QueueExecutor();
    var scheduler =
        scheduler(
            executor,
            () -> {
              rounds.incrementAndGet();
              throw new IllegalStateException("offline");
            },
            duration -> {
              if (!duration.equals(DEBOUNCE)) {
                throw new InterruptedException("shutdown");
              }
              advanceBy(duration);
            });
    scheduler.syncNow();
    scheduler.afterWrite();
    executor.runAll();
    assertEquals(1, rounds.get(), "only the failed round before the backoff ran");
    assertTrue(Thread.interrupted());
  }

  @Test
  void aWriteTriggersExactlyOneReconcileAfterTheDebounce() {
    var executor = new QueueExecutor();
    var scheduler = scheduler(executor, rounds::incrementAndGet);

    scheduler.afterWrite();

    assertEquals(0, rounds.get());
    executor.runAll();
    assertEquals(1, rounds.get());
    assertEquals(0, executor.pending());
  }

  @Test
  void aBurstOfWritesCoalescesIntoOneReconcile() {
    var executor = new QueueExecutor();
    var scheduler = scheduler(executor, rounds::incrementAndGet);

    for (var i = 0; i < 5; i++) {
      scheduler.afterWrite();
    }

    assertEquals(1, executor.pending());
    executor.runAll();
    assertEquals(1, rounds.get());
  }

  @Test
  void writesDuringAReconcileQueueExactlyOneFollowUp() {
    var executor = new QueueExecutor();
    var midFlightWrites = new AtomicInteger(2);
    SyncScheduler[] hole = new SyncScheduler[1];
    var scheduler =
        scheduler(
            executor,
            () -> {
              rounds.incrementAndGet();
              while (midFlightWrites.getAndDecrement() > 0) {
                hole[0].afterWrite();
              }
              midFlightWrites.set(0);
            });
    hole[0] = scheduler;

    scheduler.afterWrite();
    executor.runOne();

    assertEquals(1, rounds.get());
    assertEquals(1, executor.pending());
    executor.runOne();
    assertEquals(2, rounds.get());
    assertEquals(0, executor.pending());
  }

  @Test
  void aFailedReconcileIsSwallowedAndTheNextWriteRetriggers() {
    var executor = new QueueExecutor();
    var scheduler =
        scheduler(
            executor,
            () -> {
              rounds.incrementAndGet();
              throw new IllegalStateException("main unreachable");
            });

    scheduler.afterWrite();
    executor.runAll();
    scheduler.afterWrite();
    executor.runAll();

    assertEquals(2, rounds.get());
  }

  @Test
  void anInterruptedReconcileRestoresTheFlagAndDoesNotPropagate() {
    var executor = new QueueExecutor();
    var scheduler =
        scheduler(
            executor,
            () -> {
              rounds.incrementAndGet();
              throw new InterruptedException("shutdown");
            });

    scheduler.afterWrite();
    executor.runAll();

    assertEquals(1, rounds.get());
    assertTrue(Thread.interrupted());
  }

  @Test
  void anInterruptedDebounceWaitRunsNoRoundAndKeepsTheInterrupt() {
    var executor = new QueueExecutor();
    var scheduler =
        scheduler(
            executor,
            rounds::incrementAndGet,
            duration -> {
              throw new InterruptedException("shutdown");
            });

    scheduler.afterWrite();
    executor.runAll();

    assertEquals(0, rounds.get(), "a round's first step fails on an interrupted thread");
    assertTrue(Thread.interrupted());
  }

  @Test
  void theDebounceWaitLoopsUntilTheDeadlineStopsMoving() {
    var executor = new QueueExecutor();
    var sleeps = new AtomicInteger();
    var scheduler =
        scheduler(
            executor,
            rounds::incrementAndGet,
            duration -> {
              if (sleeps.getAndIncrement() == 0) {
                advanceBy(duration.dividedBy(2));
              } else {
                advanceBy(duration);
              }
            });

    scheduler.afterWrite();
    executor.runAll();

    assertEquals(1, rounds.get());
    assertTrue(sleeps.get() >= 2);
  }

  @Test
  void freshenReconcilesOnceThenServesLocalWithinTheTtl() {
    var scheduler = scheduler(new QueueExecutor(), rounds::incrementAndGet);

    scheduler.freshenRead();
    scheduler.freshenRead();

    assertEquals(1, rounds.get());
    nanos.addAndGet(TTL.toNanos());
    scheduler.freshenRead();
    assertEquals(2, rounds.get());
  }

  @Test
  void aWriteTriggeredRoundRefreshesTheReadTtl() {
    var executor = new QueueExecutor();
    var scheduler = scheduler(executor, rounds::incrementAndGet);

    scheduler.afterWrite();
    executor.runAll();
    scheduler.freshenRead();

    assertEquals(1, rounds.get());
  }

  @Test
  void aFailedFreshenAlsoWaitsOutTheTtlBeforeRetrying() {
    var scheduler =
        scheduler(
            new QueueExecutor(),
            () -> {
              rounds.incrementAndGet();
              throw new IllegalStateException("main unreachable");
            });

    scheduler.freshenRead();
    scheduler.freshenRead();

    assertEquals(1, rounds.get());
  }

  @Test
  void syncNowRunsARoundSynchronouslyEveryTime() {
    var scheduler = scheduler(new QueueExecutor(), rounds::incrementAndGet);

    scheduler.syncNow();
    scheduler.syncNow();

    assertEquals(2, rounds.get());
  }

  @Test
  void anOwnedHealthSourceClosesWithTheSchedulerAndAFailedCloseIsAWarningThatNamesItsCause()
      throws Throwable {
    var closed = new AtomicBoolean();
    var scheduler = scheduler(new QueueExecutor(), rounds::incrementAndGet);
    scheduler.useHealth(() -> SyncStatus.unattempted("node", "main"), () -> closed.set(true));
    scheduler.close();
    assertTrue(closed.get(), "the source the scheduler owns closes with it");

    var broken = scheduler(new QueueExecutor(), rounds::incrementAndGet);
    broken.useHealth(
        () -> SyncStatus.unattempted("node", "main"),
        () -> {
          throw new IOException("store already gone");
        });

    assertEquals(
        List.of("WARNING Closing the sync health source failed: store already gone"),
        warnings(broken::close));

    var wrapped = scheduler(new QueueExecutor(), rounds::incrementAndGet);
    wrapped.useHealth(
        () -> SyncStatus.unattempted("node", "main"),
        () -> {
          throw new IllegalStateException(
              "Failed to close database",
              new IllegalStateException("Session is acquired by 1 clients"));
        });
    assertEquals(
        List.of(
            "WARNING Closing the sync health source failed: Failed to close database: Session is"
                + " acquired by 1 clients"),
        warnings(wrapped::close),
        "the warning says every cause, as a failure line does");
  }

  @Test
  @Timeout(value = 1, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void aCloseEndsADrainsBackoffWithNoRoundAndTheNextStartsRoundOffersTheWrite(@TempDir Path dir)
      throws Throwable {
    var lock = dir.resolve("rounds.lock");
    var told = new CopyOnWriteArrayList<String>();
    var backingOff = new CountDownLatch(1);
    var never = new CountDownLatch(1);
    var scheduler =
        new SyncScheduler(
            journaledRound(lock, told),
            Duration.ZERO,
            TTL,
            Executors.newVirtualThreadPerTaskExecutor(),
            nanos::get,
            wait -> {
              backingOff.countDown();
              never.await();
            });
    scheduler.useHealth(SyncSchedulerTest::failedOnce);

    var warned =
        warnings(
            () -> {
              scheduler.afterWrite();
              backingOff.await();
              scheduler.close();
            });

    assertEquals(List.of(), told, "a round began after the close woke the drain from its backoff");
    assertEquals(List.of(), warned, "the stop was said as a failed sync");

    var nextStart = scheduler(new QueueExecutor(), journaledRound(lock, told));
    nextStart.useHealth(SyncSchedulerTest::failedOnce);
    nextStart.syncNow();
    assertEquals(List.of("began", "offered [spec ada-1]"), told);
  }

  @Test
  @Timeout(value = 1, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void aCloseInADrainsDebounceSendsTheWriteBeforeItReturnsAndInterruptsNoRound(@TempDir Path dir)
      throws Throwable {
    var told = new CopyOnWriteArrayList<String>();
    var inDebounce = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var scheduler =
        new SyncScheduler(
            journaledRound(dir.resolve("rounds.lock"), told),
            DEBOUNCE,
            TTL,
            Executors.newVirtualThreadPerTaskExecutor(),
            nanos::get,
            wait -> {
              inDebounce.countDown();
              release.await();
              advanceBy(wait);
            });
    scheduler.useHealth(() -> SyncStatus.unattempted("node", "main"));

    var warned =
        warnings(
            () -> {
              scheduler.afterWrite();
              inDebounce.await();
              closeWhileHeld(scheduler, release);
            });

    assertEquals(
        List.of("began", "offered [spec ada-1]"),
        told,
        "the write went to main before the close returned");
    assertEquals(List.of(), warned);
  }

  /** A node whose last round failed just now, so its next round waits out a backoff. */
  private static SyncStatus failedOnce() {
    return new SyncStatus(
        "node",
        "main",
        null,
        "stale",
        Instant.EPOCH,
        null,
        1,
        "unreachable",
        Remedy.of("offline"),
        Instant.EPOCH);
  }

  /**
   * A round as production runs one: it says it began, as its health row's begin does, then takes
   * the box's round lock, which refuses a thread whose interrupt is set as {@code FileChannel.lock}
   * does, and offers the write the journal holds; a failure is said and thrown, as the round
   * records it.
   */
  private static SyncScheduler.Reconcile journaledRound(Path lock, List<String> told) {
    return () -> {
      told.add("began");
      try (var held = FileMutex.acquire(lock)) {
        told.add("offered [spec ada-1]");
      } catch (IOException e) {
        told.add("failed " + e.getClass().getSimpleName());
        throw e;
      }
    };
  }

  @Test
  @Timeout(value = 1, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void closeWaitsForTheTimersRoundAndReturnsOnceItEnds() throws Exception {
    var inRound = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var ended = new AtomicBoolean();
    var endedWhenClosed = new AtomicBoolean();
    var scheduler =
        new SyncScheduler(
            () -> {
              inRound.countDown();
              awaitIgnoringInterrupts(release);
              ended.set(true);
            },
            DEBOUNCE,
            TTL);
    inRound.await();

    var closer =
        Thread.ofPlatform()
            .start(
                () -> {
                  scheduler.close();
                  endedWhenClosed.set(ended.get());
                });
    while (closer.getState() == Thread.State.RUNNABLE) {
      Thread.onSpinWait();
    }
    release.countDown();
    closer.join();

    assertTrue(endedWhenClosed.get(), "the close returned under the timer's round");
  }

  @Test
  @Timeout(value = 1, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void closeWaitsForTheTimersHealthReadBeforeItClosesTheSource() throws Exception {
    var reading = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var reads = new AtomicInteger();
    var inRead = new AtomicBoolean();
    var closedUnderARead = new AtomicBoolean();
    var scheduler = new SyncScheduler(rounds::incrementAndGet, DEBOUNCE, TTL);
    scheduler.useHealth(
        heldFirstRead(reads, inRead, reading, release),
        closingFailsUnder(inRead, closedUnderARead, release));

    reading.await();
    closeWhileHeld(scheduler, release);

    assertFalse(closedUnderARead.get(), "the source closed under the timer's read");
  }

  @Test
  @Timeout(value = 1, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void closeWaitsForAWritesRoundBeforeItClosesTheSourceTheRoundReads() throws Exception {
    var reading = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var reads = new AtomicInteger();
    var inRead = new AtomicBoolean();
    var closedUnderARead = new AtomicBoolean();
    var scheduler =
        new SyncScheduler(
            rounds::incrementAndGet,
            DEBOUNCE,
            TTL,
            Executors.newVirtualThreadPerTaskExecutor(),
            nanos::get,
            this::advanceBy);
    scheduler.useHealth(
        heldFirstRead(reads, inRead, reading, release),
        closingFailsUnder(inRead, closedUnderARead, release));

    scheduler.afterWrite();
    reading.await();
    closeWhileHeld(scheduler, release);

    assertFalse(closedUnderARead.get(), "the source closed under the round's read");
    assertEquals(1, rounds.get(), "the round the write asked for ran whole");
  }

  /**
   * A health read whose first call holds until {@code release}, ignoring interrupts as a thread
   * inside a native call does.
   */
  private static Supplier<SyncStatus> heldFirstRead(
      AtomicInteger reads, AtomicBoolean inRead, CountDownLatch reading, CountDownLatch release) {
    return () -> {
      if (reads.getAndIncrement() == 0) {
        inRead.set(true);
        reading.countDown();
        awaitIgnoringInterrupts(release);
        inRead.set(false);
      }
      return SyncStatus.unattempted("node", "main");
    };
  }

  /** A source whose close fails as the arena's does while a read is in flight, freeing it. */
  private static AutoCloseable closingFailsUnder(
      AtomicBoolean inRead, AtomicBoolean closedUnderARead, CountDownLatch release) {
    return () -> {
      if (inRead.get()) {
        closedUnderARead.set(true);
        release.countDown();
        throw new IllegalStateException("Session is acquired by 1 clients");
      }
    };
  }

  /**
   * Closes {@code scheduler} on a thread of its own and lets the held read go only once that thread
   * waits (or has ended), so a close that does not wait closes the source under the read.
   */
  private static void closeWhileHeld(SyncScheduler scheduler, CountDownLatch release)
      throws InterruptedException {
    var closer = Thread.ofPlatform().start(scheduler::close);
    while (closer.getState() == Thread.State.RUNNABLE) {
      Thread.onSpinWait();
    }
    release.countDown();
    closer.join();
  }

  /** The warnings the scheduler logs while {@code work} runs, each as its level and message. */
  private static List<String> warnings(Executable work) throws Throwable {
    var log = Logger.getLogger(SyncScheduler.class.getName());
    var said = new CopyOnWriteArrayList<String>();
    var listening =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            said.add(record.getLevel() + " " + record.getMessage());
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    log.addHandler(listening);
    try {
      work.execute();
    } finally {
      log.removeHandler(listening);
    }
    return said;
  }

  private static void awaitIgnoringInterrupts(CountDownLatch latch) {
    var interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void disabledSchedulerNeverReconciles() {
    var scheduler = SyncScheduler.disabled();

    scheduler.afterWrite();
    scheduler.freshenRead();
    scheduler.syncNow();
    scheduler.close();
  }

  @Test
  void productionConstructorWiresRealSeams() {
    try (var scheduler = new SyncScheduler(rounds::incrementAndGet, DEBOUNCE, TTL)) {
      scheduler.syncNow();
    }
    assertEquals(1, rounds.get());
  }

  private static final class QueueExecutor extends AbstractExecutorService {

    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    int pending() {
      return tasks.size();
    }

    void runOne() {
      tasks.poll().run();
    }

    void runAll() {
      Runnable task;
      while ((task = tasks.poll()) != null) {
        task.run();
      }
    }

    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }
  }
}
