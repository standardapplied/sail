/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

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
 * wait for a debounce. All of it is best-effort: a failed round records health and backs off and
 * the write or read proceeds on the local replica — local-first is never blocked by sync.
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

  /**
   * How often an idle node (no reads, no writes) still checks in with main: often enough that a
   * stale node shows within a minute, seldom enough that a fleet of idle nodes is not a load on
   * main — the read-driven freshen window is a tolerance, not a poll.
   */
  public static final Duration IDLE_POLL = Duration.ofMinutes(1);

  private final boolean enabled;
  private final Reconcile reconcile;
  private final Duration debounce;
  private final Duration freshenTtl;
  private final ExecutorService executor;
  private final LongSupplier nanoTime;
  private final Sleeper sleeper;
  private final Supplier<Instant> now;
  private final Backoff backoff;
  private Supplier<SyncStatus> health;
  private AutoCloseable healthSource = () -> {};
  private ScheduledExecutorService timer;
  private int failures;

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
        Thread::sleep,
        Instant::now);
    timer = Executors.newSingleThreadScheduledExecutor();
    timer.scheduleWithFixedDelay(
        () -> {
          try {
            tick();
          } catch (RuntimeException e) {
            System.getLogger(SyncScheduler.class.getName())
                .log(
                    System.Logger.Level.WARNING, "Sync timer failed; retrying on the next tick", e);
          }
        },
        1,
        1,
        TimeUnit.SECONDS);
  }

  SyncScheduler(
      Reconcile reconcile,
      Duration debounce,
      Duration freshenTtl,
      ExecutorService executor,
      LongSupplier nanoTime,
      Sleeper sleeper) {
    this(
        reconcile,
        debounce,
        freshenTtl,
        executor,
        nanoTime,
        sleeper,
        () -> Instant.EPOCH.plusNanos(nanoTime.getAsLong()));
  }

  SyncScheduler(
      Reconcile reconcile,
      Duration debounce,
      Duration freshenTtl,
      ExecutorService executor,
      LongSupplier nanoTime,
      Sleeper sleeper,
      Supplier<Instant> now) {
    this.enabled = reconcile != null;
    this.reconcile = reconcile;
    this.debounce = debounce;
    this.freshenTtl = freshenTtl;
    this.executor = executor;
    this.nanoTime = nanoTime;
    this.sleeper = sleeper;
    this.now = now;
    this.backoff = new Backoff(now, () -> ThreadLocalRandom.current().nextDouble());
  }

  public void useHealth(Supplier<SyncStatus> health) {
    synchronized (freshenLock) {
      this.health = health;
    }
  }

  /** As {@link #useHealth(Supplier)}, owning {@code source}: it closes when the scheduler does. */
  public void useHealth(Supplier<SyncStatus> health, AutoCloseable source) {
    synchronized (freshenLock) {
      this.health = health;
      this.healthSource = Objects.requireNonNull(source, "source");
    }
  }

  /** The timer's beat: a due half-open probe, else an idle node's once-a-minute check-in. */
  void tick() {
    if (!enabled) return;
    synchronized (freshenLock) {
      refreshBackoff();
      if (backoff.probeDue()) {
        runRound();
      } else if (!attemptedWithin(IDLE_POLL) && backoff.ready(false)) {
        runRound();
      }
    }
  }

  private void refreshBackoff() {
    if (health == null) return;
    var status = health.get();
    failures = status.consecutiveFailures();
    backoff.observe(status.lastAttemptAt(), failures);
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
      refreshBackoff();
      if (withinFreshenTtl() || !backoff.ready(false)) {
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
    synchronized (freshenLock) {
      runRound();
    }
  }

  @Override
  public void close() {
    if (timer != null) timer.shutdownNow();
    if (executor != null) {
      executor.close();
    }
    try {
      healthSource.close();
    } catch (Exception e) {
      throw new IllegalStateException("Closing the sync health source failed", e);
    }
  }

  private boolean withinFreshenTtl() {
    return attemptedWithin(freshenTtl);
  }

  /** Whether a round was attempted within {@code window}; a round still marked in flight counts. */
  private boolean attemptedWithin(Duration window) {
    if (health != null) {
      var status = health.get();
      var ttl = "syncing".equals(status.state()) ? Backoff.HALF_OPEN_DELAY : window;
      return status.lastAttemptAt() != null && now.get().isBefore(status.lastAttemptAt().plus(ttl));
    }
    synchronized (state) {
      return attempted && nanoTime.getAsLong() - attemptedAtNanos < window.toNanos();
    }
  }

  private void drain() {
    awaitQuiet();
    synchronized (state) {
      scheduled = false;
      running = true;
    }
    try {
      runWhenReady();
    } finally {
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
  }

  private void runWhenReady() {
    while (true) {
      Duration wait;
      synchronized (freshenLock) {
        refreshBackoff();
        if (backoff.ready(true)) {
          runRound();
          return;
        }
        wait = backoff.remaining();
      }
      try {
        sleeper.sleep(wait);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        synchronized (freshenLock) {
          runRound();
        }
        return;
      }
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
    Exception failure = null;
    try {
      reconcile.run();
      failures = 0;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      failures++;
      failure = e;
    } catch (Exception e) {
      failures++;
      failure = e;
    } finally {
      if (health == null) backoff.observe(now.get(), failures);
      else refreshBackoff();
      synchronized (state) {
        attempted = true;
        attemptedAtNanos = nanoTime.getAsLong();
      }
    }
    if (failure != null) {
      System.getLogger(SyncScheduler.class.getName())
          .log(
              System.Logger.Level.WARNING,
              "Sync failed: "
                  + failure.getMessage()
                  + "; "
                  + (backoff.open() ? "circuit open; next probe in " : "retry in ")
                  + backoff.remaining().toSeconds()
                  + "s");
    }
  }
}
