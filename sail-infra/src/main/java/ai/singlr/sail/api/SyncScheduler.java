/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/**
 * The sync-on-write trigger and read-freshness gate for a node's replica, shared by every lane that
 * mutates or serves specs (HTTP API, Unix-socket API, CLI dispatch). It is a trigger around the
 * existing {@code sail sync} reconcile — not a second sync engine: the injected {@link Reconcile}
 * runs one concurrent-safe CAS round against main.
 *
 * <p>{@link #afterWrite()} schedules a debounced, coalesced, single-flight reconcile: a burst of
 * writes produces one round shortly after the last write; a write landing while a round is in
 * flight queues exactly one follow-up round. {@link #freshenRead()} reconciles before a read only
 * when the last attempt is older than the freshen TTL, so board polling costs at most one round per
 * TTL window. {@link #syncNow()} runs one round synchronously for short-lived processes that cannot
 * wait for a debounce. All of it is best-effort: a failed round logs a warning and the write or
 * read proceeds on the local replica — local-first is never blocked by sync.
 *
 * <p>On main and on a standalone box there is no peer to reconcile with, so the wiring installs
 * {@link #disabled()} and every method is a no-op. Time is injected ({@code nanoTime} for elapsed,
 * a {@link Sleeper} for the debounce wait) so tests drive the schedule deterministically.
 */
public final class SyncScheduler implements AutoCloseable {

  /** One reconcile round with main; throwing marks the round failed. */
  @FunctionalInterface
  public interface Reconcile {
    void run() throws Exception;
  }

  /** The debounce wait; production sleeps, tests advance a fake clock instead. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  public static final Duration DEFAULT_DEBOUNCE = Duration.ofSeconds(2);
  public static final Duration DEFAULT_FRESHEN_TTL = Duration.ofSeconds(15);

  private final boolean enabled;
  private final Reconcile reconcile;
  private final Duration debounce;
  private final Duration freshenTtl;
  private final ExecutorService executor;
  private final LongSupplier nanoTime;
  private final Sleeper sleeper;

  private final Object state = new Object();
  private final Object freshenLock = new Object();
  private long quietAtNanos;
  private boolean scheduled;
  private boolean running;
  private boolean followUp;
  private boolean attempted;
  private long attemptedAtNanos;

  public SyncScheduler(Reconcile reconcile, Duration debounce, Duration freshenTtl) {
    this(
        reconcile,
        debounce,
        freshenTtl,
        Executors.newVirtualThreadPerTaskExecutor(),
        System::nanoTime,
        Thread::sleep);
  }

  SyncScheduler(
      Reconcile reconcile,
      Duration debounce,
      Duration freshenTtl,
      ExecutorService executor,
      LongSupplier nanoTime,
      Sleeper sleeper) {
    this.enabled = reconcile != null;
    this.reconcile = reconcile;
    this.debounce = debounce;
    this.freshenTtl = freshenTtl;
    this.executor = executor;
    this.nanoTime = nanoTime;
    this.sleeper = sleeper;
  }

  /** The no-op scheduler for main and standalone boxes — no peer, nothing to reconcile. */
  public static SyncScheduler disabled() {
    return new SyncScheduler(null, null, null, null, null, null);
  }

  /**
   * Records a successful local write and schedules the debounced propagation round. Never blocks
   * the caller: the round runs on this scheduler's executor after the burst goes quiet.
   */
  public void afterWrite() {
    if (!enabled) {
      return;
    }
    synchronized (state) {
      quietAtNanos = nanoTime.getAsLong() + debounce.toNanos();
      if (running) {
        followUp = true;
        return;
      }
      if (scheduled) {
        return;
      }
      scheduled = true;
    }
    executor.execute(this::drain);
  }

  /**
   * Reconciles before serving a read when the last attempt is older than the freshen TTL, otherwise
   * returns immediately and the caller serves the local replica. Gated on the last attempt rather
   * than the last success so an unreachable main costs one failed round per TTL window instead of
   * one per read.
   */
  public void freshenRead() {
    if (!enabled) {
      return;
    }
    synchronized (freshenLock) {
      if (withinFreshenTtl()) {
        return;
      }
      runRound();
    }
  }

  /** Runs one round synchronously; for short-lived processes that cannot await a debounce. */
  public void syncNow() {
    if (!enabled) {
      return;
    }
    runRound();
  }

  @Override
  public void close() {
    if (executor != null) {
      executor.close();
    }
  }

  private boolean withinFreshenTtl() {
    synchronized (state) {
      return attempted && nanoTime.getAsLong() - attemptedAtNanos < freshenTtl.toNanos();
    }
  }

  private void drain() {
    awaitQuiet();
    synchronized (state) {
      scheduled = false;
      running = true;
    }
    runRound();
    boolean rerun;
    synchronized (state) {
      running = false;
      rerun = followUp;
      followUp = false;
      if (rerun) {
        scheduled = true;
      }
    }
    if (rerun) {
      executor.execute(this::drain);
    }
  }

  private void awaitQuiet() {
    while (true) {
      long wait;
      synchronized (state) {
        wait = quietAtNanos - nanoTime.getAsLong();
      }
      if (wait <= 0) {
        return;
      }
      try {
        sleeper.sleep(Duration.ofNanos(wait));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void runRound() {
    try {
      reconcile.run();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      warn(e);
    } catch (Exception e) {
      warn(e);
    } finally {
      synchronized (state) {
        attempted = true;
        attemptedAtNanos = nanoTime.getAsLong();
      }
    }
  }

  private static void warn(Exception cause) {
    System.err.println(
        "  [sync] Reconcile with main failed ("
            + cause.getMessage()
            + "); continuing on the local replica. The next write, stale read, or manual"
            + " 'sail sync' retries.");
  }
}
