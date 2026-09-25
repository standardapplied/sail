/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.identity.Actor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Periodically surfaces specs stranded mid-flight — left in {@code in_progress} or {@code review}
 * past {@link #DEFAULT_THRESHOLD} — so a dispatch whose Stop hook never fired (or whose watcher or
 * review crashed) is escalated to a human rather than waiting forever. This is the loop-engineering
 * "triage inbox": the reconciler only reports; {@code onStranded} decides what to do (publish an
 * event, notify, etc.).
 *
 * <p>Like {@link ExpiredRowSweeper}, each sweep runs on its own short-lived connection so a read
 * issued from the scheduler thread never lands inside an in-flight request transaction, and a
 * transient failure just skips this sweep — the next one retries.
 */
public final class StuckSpecReconciler implements AutoCloseable {

  /** How often to sweep — frequent enough to surface a stranded spec promptly, negligible load. */
  public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(15);

  /**
   * How long in {@code in_progress}/{@code review} counts as stranded — above the watchdog ceiling.
   */
  public static final Duration DEFAULT_THRESHOLD = Duration.ofHours(6);

  private static final SpecStore.SpecFilter ACTIVE_FILTER =
      new SpecStore.SpecFilter(null, "in_progress,review", null, null, null);

  private final Path dbPath;
  private final Duration threshold;
  private final Consumer<List<SpecStore.SpecRow>> onStranded;
  private final ScheduledExecutorService scheduler;

  public StuckSpecReconciler(
      Path dbPath, Duration threshold, Consumer<List<SpecStore.SpecRow>> onStranded) {
    this.dbPath = dbPath;
    this.threshold = threshold;
    this.onStranded = onStranded;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("sail-reconcile-", 0).factory());
  }

  /** Starts the periodic reconciliation at the default interval. */
  public void start() {
    start(DEFAULT_INTERVAL);
  }

  public void start(Duration interval) {
    scheduler.scheduleAtFixedRate(
        this::sweep, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
  }

  /**
   * Runs one reconciliation pass as this box's machinery; surfaces any stranded specs via {@code
   * onStranded}.
   */
  void sweep() {
    Actor.run(Actor.system(), this::reconcile);
  }

  private void reconcile() {
    try (var db = Sqlite.open(dbPath)) {
      var active = new SpecStore(db).list(ACTIVE_FILTER);
      var stranded = StrandedSpecs.find(active, DateTimeUtils.now(), threshold);
      if (!stranded.isEmpty()) {
        onStranded.accept(stranded);
      }
    } catch (Exception ignored) {
      // transient (e.g. lock contention); the next sweep retries.
    }
  }

  @Override
  public void close() {
    scheduler.close();
  }
}
