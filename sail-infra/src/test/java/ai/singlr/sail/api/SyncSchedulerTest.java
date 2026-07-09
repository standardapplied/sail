/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

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
