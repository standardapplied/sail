/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
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
                "offline",
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
  void anInterruptedBackoffStillFlushesThePendingWrite() {
    var executor = new QueueExecutor();
    var scheduler =
        scheduler(
            executor,
            () -> {
              rounds.incrementAndGet();
              throw new IllegalStateException("offline");
            },
            duration -> {
              throw new InterruptedException("shutdown");
            });
    scheduler.syncNow();
    scheduler.afterWrite();
    executor.runAll();
    assertEquals(2, rounds.get());
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
  void anInterruptedDebounceWaitStillFlushesTheRound() {
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

    assertEquals(1, rounds.get());
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
  void anOwnedHealthSourceClosesWithTheSchedulerAndItsFailureIsLoud() {
    var closed = new java.util.concurrent.atomic.AtomicBoolean();
    var scheduler = scheduler(new QueueExecutor(), rounds::incrementAndGet);
    scheduler.useHealth(() -> SyncStatus.unattempted("node", "main"), () -> closed.set(true));
    scheduler.close();
    assertTrue(closed.get(), "the source the scheduler owns closes with it");

    var broken = scheduler(new QueueExecutor(), rounds::incrementAndGet);
    broken.useHealth(
        () -> SyncStatus.unattempted("node", "main"),
        () -> {
          throw new java.io.IOException("store already gone");
        });
    var failure = assertThrows(IllegalStateException.class, broken::close);
    assertEquals("store already gone", failure.getCause().getMessage());
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
