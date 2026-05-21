/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;

/**
 * A virtual-thread executor capped by a semaphore so a buggy client (or hot path) can't spawn an
 * unbounded number of vthreads and exhaust file descriptors / native memory / pinned carriers.
 *
 * <p>{@link #tryRun(Runnable)} returns {@code false} immediately when the cap is hit — callers
 * decide whether to reject (HTTP 503), drop (event-fan-out), or buffer. There is no queueing; the
 * semaphore IS the admission control. Permits are released when the task finishes (success or
 * failure).
 */
public final class BoundedVirtualExecutor implements AutoCloseable {

  private final Semaphore permits;
  private final ExecutorService executor;
  private final LongAdder rejected = new LongAdder();
  private final int maxConcurrent;

  public BoundedVirtualExecutor(int maxConcurrent) {
    this(maxConcurrent, Executors.newVirtualThreadPerTaskExecutor());
  }

  BoundedVirtualExecutor(int maxConcurrent, ExecutorService executor) {
    if (maxConcurrent <= 0) {
      throw new IllegalArgumentException("maxConcurrent must be positive, got " + maxConcurrent);
    }
    this.maxConcurrent = maxConcurrent;
    this.permits = new Semaphore(maxConcurrent);
    this.executor = executor;
  }

  /**
   * Submits the task on a virtual thread if a permit is available; otherwise rejects immediately.
   *
   * @return {@code true} if the task was accepted; {@code false} if the cap was hit
   */
  public boolean tryRun(Runnable task) {
    if (!permits.tryAcquire()) {
      rejected.increment();
      return false;
    }
    try {
      executor.execute(
          () -> {
            try {
              task.run();
            } finally {
              permits.release();
            }
          });
    } catch (Throwable submitFailure) {
      permits.release();
      throw submitFailure;
    }
    return true;
  }

  /** Total tasks rejected since startup. */
  public long rejectedCount() {
    return rejected.sum();
  }

  /** Tasks currently in flight. */
  public int inFlight() {
    return maxConcurrent - permits.availablePermits();
  }

  /** Maximum concurrent tasks. */
  public int maxConcurrent() {
    return maxConcurrent;
  }

  @Override
  public void close() {
    executor.close();
  }
}
