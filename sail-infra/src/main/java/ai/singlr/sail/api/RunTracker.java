/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.store.RunStore;
import java.util.Set;
import java.util.function.Predicate;

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
 */
public final class RunTracker implements EventSubscriber {

  private static final Set<String> HANDLED_TYPES =
      Set.of(
          Event.WellKnownTypes.AGENT_SESSION_STOPPED, Event.WellKnownTypes.AGENT_SESSION_COMPLETED);

  private final RunStore runStore;
  private final SyncScheduler syncScheduler;

  public RunTracker(RunStore runStore, SyncScheduler syncScheduler) {
    this.runStore = runStore;
    this.syncScheduler = syncScheduler;
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
    var running = runStore.runningForProject(event.project());
    if (running.isPresent()) {
      runStore.complete(running.get().id(), status, exitCode);
      syncScheduler.afterWrite();
      return;
    }
    if (exitCode == null) {
      return;
    }
    runStore
        .latestForProject(event.project())
        .filter(run -> run.exitCode() == null)
        .ifPresent(
            run -> {
              runStore.recordExitCode(run.id(), exitCode);
              syncScheduler.afterWrite();
            });
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
