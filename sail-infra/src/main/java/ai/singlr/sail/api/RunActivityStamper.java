/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.store.RunStore;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * EventBus subscriber that stamps a run's {@code last_activity_at} whenever a progress event
 * ({@link Event.WellKnownTypes#progress}) carries its {@code run_id} — the same event set the
 * guardrail watcher's stall timer trusts, so "working" here and "not stalled" there can never
 * disagree. Deliberately its own subscriber, not folded into {@link RunTracker}, whose contract is
 * to stamp only the terminal status and exit code.
 *
 * <p>The stamp is a best-effort local column update: {@link RunStore#stampActivity} coalesces to
 * one write per {@link #COALESCE_FLOOR} window and journals no revision, so a continuous {@code
 * agent_log_chunk} stream can never flood the ChangeLog or fire sync-on-write per chunk. Progress
 * events only ever originate on the executing box (the in-container hooks post to the local
 * socket), so the run addressed here is always this box's own — no ownership guard needed.
 */
public final class RunActivityStamper implements EventSubscriber {

  /**
   * Minimum spacing between stamps for one run. Presence needs ~{@link RunPresence#THRESHOLD}
   * granularity, so anything well under it keeps the chip honest while a chunk burst stays one
   * write.
   */
  public static final Duration COALESCE_FLOOR = Duration.ofSeconds(30);

  private final RunStore runStore;

  public RunActivityStamper(RunStore runStore) {
    this.runStore = runStore;
  }

  @Override
  public String name() {
    return "run-activity";
  }

  @Override
  public Predicate<Event> filter() {
    return event -> Event.WellKnownTypes.progress(event.type());
  }

  @Override
  public void onEvent(Event event) {
    var runId = Objects.toString(event.data().get(Event.WellKnownData.RUN_ID), null);
    if (Strings.isBlank(runId)) {
      return;
    }
    try {
      runStore.stampActivity(runId, COALESCE_FLOOR);
    } catch (Exception e) {
      System.err.println("run-activity: failed to stamp run " + runId + ": " + e.getMessage());
    }
  }
}
