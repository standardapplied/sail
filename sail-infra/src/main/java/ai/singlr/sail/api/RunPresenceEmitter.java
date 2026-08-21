/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.store.RunStore;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Publishes one {@code agent_presence} event per presence <em>crossing</em> of a local running run
 * — {@code quiet} when it goes silent past {@link RunPresence#THRESHOLD}, {@code working} once
 * activity resumes — never a heartbeat stream. SSE clients get the edge without polling; between
 * edges, presence is derived at read time from {@code last_activity_at}, so nothing here is ever
 * authoritative state.
 *
 * <p>Edge detection holds each run's previously-emitted presence in memory only, consistent with
 * "presence is never persisted": entries for runs no longer live are dropped each pass, and a
 * daemon restart simply re-seeds from current state without replaying old edges. Only runs this
 * node executed are considered — a foreign run's stamp is only as fresh as the last sync, and
 * narrating quiet from stale data would be noise; its executing box owns its edges. A sibling of
 * {@link MissedStopReconciler} / {@link WatcherRearmer} on the shared {@link PeriodicPass}
 * discipline: passes never overlap, a throwing pass never cancels the schedule.
 */
public final class RunPresenceEmitter implements AutoCloseable {

  /**
   * Pass cadence: a fraction of {@link RunPresence#THRESHOLD}, so a crossing is narrated well
   * within the granularity presence promises.
   */
  public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(15);

  private final RunStore runStore;
  private final EventBus bus;
  private final Supplier<String> localHandle;
  private final Supplier<Instant> clock;
  private final PeriodicPass pass;
  private final Map<String, String> lastEmitted = new HashMap<>();

  public RunPresenceEmitter(
      RunStore runStore, EventBus bus, Supplier<String> localHandle, Supplier<Instant> clock) {
    this.runStore = runStore;
    this.bus = bus;
    this.localHandle = localHandle;
    this.clock = clock;
    this.pass = new PeriodicPass("presence", this::sweep);
  }

  /** Starts the periodic pass at the default cadence. */
  public void start() {
    start(DEFAULT_INTERVAL);
  }

  public void start(Duration interval) {
    pass.start(interval);
  }

  /**
   * Runs one pass and returns how many presence events were published. A run with no presence (no
   * activity stamp yet) publishes nothing and holds no edge state, so its first stamp can never
   * read as a resume.
   */
  public int sweep() {
    var node = localHandle.get();
    var now = clock.get();
    var live = new HashSet<String>();
    var emitted = 0;
    for (var run : runStore.runningForPresence()) {
      if (!run.ownedBy(node)) {
        continue;
      }
      var presence = RunPresence.of(run.status(), run.lastActivityAt(), now);
      if (presence == null) {
        continue;
      }
      live.add(run.id());
      var previous = lastEmitted.put(run.id(), presence);
      if (presence.equals(previous) || (previous == null && RunPresence.WORKING.equals(presence))) {
        continue;
      }
      bus.publish(presenceEvent(run, presence));
      emitted++;
    }
    lastEmitted.keySet().retainAll(live);
    return emitted;
  }

  private static Event presenceEvent(RunStore.RunRow run, String presence) {
    var data = new LinkedHashMap<String, Object>();
    data.put("presence", presence);
    data.put(Event.WellKnownData.RUN_ID, run.id());
    data.put(Event.WellKnownData.RUN_ROLE, run.role());
    data.put("last_activity_at", run.lastActivityAt());
    return Event.of(
        run.project(),
        run.specId(),
        Event.WellKnownTypes.AGENT_PRESENCE,
        Event.SAIL_AGENT,
        HostInfo.hostname(),
        data);
  }

  @Override
  public void close() {
    pass.close();
  }
}
