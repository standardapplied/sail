/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.store.RunStore;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * EventBus subscriber that closes out a {@link RunStore} run when its agent stops. The run row
 * itself is written at launch by the dispatcher (which mints the run id so the log directory is
 * addressable before the agent starts); this tracker only stamps the terminal status and exit code
 * on {@code agent_session_stopped} / {@code agent_session_completed}.
 *
 * <p>Completing a run is a local write on the executing node, so it fires the node's sync-on-write
 * trigger: "sumesh's agent finished" reaches main promptly and every box can see the run is done.
 * On main and standalone boxes the injected {@link SyncScheduler} is {@link
 * SyncScheduler#disabled()}, so the trigger is a no-op there.
 *
 * <p>Completion is a {@code running → terminal} compare-and-set, so it is mutually exclusive with a
 * clean stop's claim: a stop that moves the run to {@code stopping} after this tracker read the row
 * wins the write, and the tracker falls back to enriching the exit code without ever overwriting
 * {@code stopping} or {@code stopped} — a cancelled spec can never end up paired with a {@code
 * completed} run.
 *
 * <p>Completion addresses the exact run by the {@code run_id} the terminal event carries, not "the
 * newest running run of the project". EventBus delivery is asynchronous, so a stop for run A can
 * arrive after run B has started in the same project; resolving by project alone would let A's
 * delayed stop complete B. A stop that carries no {@code run_id} (a bare agent-hook turn-end that
 * minted no run correlation) is ignored here — the watcher's and reconciler's authoritative,
 * run-addressed stops drive completion instead. Ownership is still enforced by {@code localHandle}
 * so this box only ever writes its own runs, preserving the single-writer invariant now that the
 * {@code runs} table also holds foreign rows adopted via sync.
 */
public final class RunTracker implements EventSubscriber {

  private static final Set<String> HANDLED_TYPES =
      Set.of(
          Event.WellKnownTypes.AGENT_SESSION_STOPPED, Event.WellKnownTypes.AGENT_SESSION_COMPLETED);

  private final RunStore runStore;
  private final SyncScheduler syncScheduler;
  private final Supplier<String> localHandle;

  public RunTracker(RunStore runStore, SyncScheduler syncScheduler, Supplier<String> localHandle) {
    this.runStore = runStore;
    this.syncScheduler = syncScheduler;
    this.localHandle = localHandle;
  }

  @Override
  public String name() {
    return "run-tracker";
  }

  @Override
  public Predicate<Event> filter() {
    return e -> HANDLED_TYPES.contains(e.type());
  }

  @Override
  public void onEvent(Event event) {
    try {
      switch (event.type()) {
        case Event.WellKnownTypes.AGENT_SESSION_STOPPED -> complete(event, "stopped");
        case Event.WellKnownTypes.AGENT_SESSION_COMPLETED -> complete(event, "completed");
        default -> {}
      }
    } catch (Exception e) {
      System.err.println(
          "run-tracker: failed to process "
              + event.type()
              + " for project "
              + event.project()
              + ": "
              + e.getMessage());
    }
  }

  private void complete(Event event, String status) {
    var exitCode = extractInt(event.data().get(Event.WellKnownData.EXIT_CODE));
    var runId = Objects.toString(event.data().get(Event.WellKnownData.RUN_ID), null);
    if (Strings.isBlank(runId)) {
      return;
    }
    var node = localHandle.get();
    runStore
        .findById(runId)
        .filter(run -> SailOperations.ownsRun(run.node(), node))
        .ifPresent(run -> completeOrRecord(run, status, exitCode));
  }

  private void completeOrRecord(RunStore.RunRow run, String status, Integer exitCode) {
    if (runStore.transition(run.id(), "running", status, exitCode)) {
      syncScheduler.afterWrite();
      return;
    }
    if (exitCode == null) {
      return;
    }
    var current = runStore.findById(run.id()).orElse(null);
    if (current != null && current.exitCode() == null) {
      runStore.recordExitCode(run.id(), exitCode);
      syncScheduler.afterWrite();
    }
  }

  private static Integer extractInt(Object value) {
    if (value instanceof Number n) {
      return n.intValue();
    }
    if (value instanceof String s) {
      try {
        return Integer.parseInt(s);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }
}
