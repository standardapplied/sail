/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.store.EventStore;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Periodically prunes old telemetry events in bounded transactions. */
public final class EventRetentionSweeper implements AutoCloseable {

  public static final Duration DEFAULT_RETENTION = Duration.ofDays(60);
  public static final int BATCH_SIZE = 5000;

  private final EventStore events;
  private final Duration retention;
  private final ScheduledExecutorService scheduler;

  public EventRetentionSweeper(EventStore events) {
    this(
        events,
        DEFAULT_RETENTION,
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("sail-event-retention").daemon(true).factory()));
  }

  EventRetentionSweeper(EventStore events, Duration retention, ScheduledExecutorService scheduler) {
    this.events = events;
    this.retention = retention;
    this.scheduler = scheduler;
  }

  public void start() {
    scheduler.scheduleAtFixedRate(this::sweepQuietly, 1, 24, TimeUnit.HOURS);
  }

  int sweep() {
    return events.pruneBefore(
        DateTimeUtils.now().minus(retention), Event.WellKnownTypes.TELEMETRY_TYPES, BATCH_SIZE);
  }

  void sweepQuietly() {
    try {
      sweep();
    } catch (RuntimeException e) {
      System.err.println(
          "sail event retention: could not prune telemetry (" + e.getMessage() + ").");
    }
  }

  /** Stops sweeping and waits for a sweep in flight, so the database it uses can close after. */
  @Override
  public void close() {
    scheduler.shutdownNow();
    scheduler.close();
  }
}
