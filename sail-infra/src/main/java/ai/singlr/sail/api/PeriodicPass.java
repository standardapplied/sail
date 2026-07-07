/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One recurring maintenance pass on its own single-threaded scheduler: passes never overlap (a slow
 * pass makes the next tick a no-op rather than stacking), a throwing pass is logged and never
 * cancels the schedule, and {@link #close()} stops the timer. Shared by the reconciliation loops so
 * their scheduling discipline cannot drift apart.
 */
final class PeriodicPass implements AutoCloseable {

  private final String name;
  private final Runnable pass;
  private final ScheduledExecutorService scheduler;
  private final AtomicBoolean running = new AtomicBoolean();

  PeriodicPass(String name, Runnable pass) {
    this.name = name;
    this.pass = pass;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("sail-" + name + "-", 0).factory());
  }

  void start(Duration interval) {
    scheduler.scheduleAtFixedRate(
        this::runIfIdle, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** Runs one pass unless another is still in flight. Returns whether the pass ran. */
  boolean runIfIdle() {
    if (!running.compareAndSet(false, true)) {
      return false;
    }
    try {
      pass.run();
    } catch (RuntimeException e) {
      System.err.println("  [" + name + "] pass failed: " + e.getMessage());
    } finally {
      running.set(false);
    }
    return true;
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }
}
