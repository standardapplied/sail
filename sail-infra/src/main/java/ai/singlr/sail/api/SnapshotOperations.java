/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SnapshotManager;
import ai.singlr.sail.store.RunStore;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The single snapshot lane behind the API: reads and mutations over the existing {@link
 * SnapshotManager} — no new snapshot mechanism, and no snapshot creation (cadence stays at the
 * dispatch, guardrail, and invite seams).
 *
 * <p>Restore and delete can run for many minutes on the dir backend, far past any client's request
 * timeout, so both are accepted-then-async: the request validates and claims the project, returns
 * immediately, and the mutation completes on the executor, reporting through the {@code
 * snapshot_restored} / {@code snapshot_deleted} events already declared on the bus. A failed
 * mutation publishes the same event with an {@code error} entry so a listener never waits forever.
 *
 * <p>Restore is the dangerous verb — it discards the container's current state — so it refuses
 * while any run is live on this box's container, with the same vocabulary as dispatch conflicts.
 */
final class SnapshotOperations {

  static final String RESTORE_ACTION = "restore";
  static final String DELETE_ACTION = "delete";

  private final ProjectLoader projects;
  private final RunStore runStore;
  private final DispatchOperations.EventSink events;
  private final ContainerManager containers;
  private final SnapshotManager snapshots;
  private final ExecutorService executor;
  private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

  SnapshotOperations(
      ShellExec shell,
      ProjectLoader projects,
      RunStore runStore,
      DispatchOperations.EventSink events) {
    this(shell, projects, runStore, events, Executors.newVirtualThreadPerTaskExecutor());
  }

  SnapshotOperations(
      ShellExec shell,
      ProjectLoader projects,
      RunStore runStore,
      DispatchOperations.EventSink events,
      ExecutorService executor) {
    this.projects = projects;
    this.runStore = runStore;
    this.events = events;
    this.containers = new ContainerManager(shell);
    this.snapshots = new SnapshotManager(shell);
    this.executor = executor;
  }

  SnapshotListResponse list(String project) {
    projects.requireExists(project);
    var views =
        listSnapshots(project).stream()
            .map(info -> new SnapshotView(info.name(), info.createdAt(), sourceOf(info.name())))
            .toList();
    return new SnapshotListResponse(views);
  }

  SnapshotActionResponse restore(String project, String label, String localHandle) {
    NameValidator.requireValidSnapshotLabel(label);
    var state = projects.loadCreated(project).state();
    requireSnapshotExists(project, label);
    refuseLiveRun(project, label, localHandle);
    claim(project);
    executor.execute(() -> runRestore(project, label, state));
    return new SnapshotActionResponse(project, label, RESTORE_ACTION, "accepted");
  }

  SnapshotActionResponse delete(String project, String label) {
    NameValidator.requireValidSnapshotLabel(label);
    projects.requireExists(project);
    requireSnapshotExists(project, label);
    claim(project);
    executor.execute(() -> runDelete(project, label));
    return new SnapshotActionResponse(project, label, DELETE_ACTION, "accepted");
  }

  /**
   * Classifies a snapshot by the naming convention its producer already uses: {@code invite-} from
   * the hands-on invite lane, {@code guardrail-} from the guardrail watcher, {@code snap-} from the
   * dispatch auto-snapshot's default label, anything else operator-chosen. A CLI create that kept
   * the default label reads as {@code dispatch} — the name is all the metadata Incus keeps.
   */
  static String sourceOf(String name) {
    if (name.startsWith("invite-")) {
      return "invite";
    }
    if (name.startsWith("guardrail-")) {
      return "guardrail";
    }
    if (name.startsWith("snap-")) {
      return "dispatch";
    }
    return "manual";
  }

  private void runRestore(String project, String label, ContainerState state) {
    try {
      if (state instanceof ContainerState.Running) {
        containers.stop(project);
      }
      snapshots.restore(project, label);
      containers.start(project);
      publish(project, Event.WellKnownTypes.SNAPSHOT_RESTORED, Map.of("label", label));
    } catch (Exception e) {
      publishFailure(project, Event.WellKnownTypes.SNAPSHOT_RESTORED, label, e);
    } finally {
      inFlight.remove(project);
    }
  }

  private void runDelete(String project, String label) {
    try {
      snapshots.delete(project, label);
      publish(project, Event.WellKnownTypes.SNAPSHOT_DELETED, Map.of("label", label));
    } catch (Exception e) {
      publishFailure(project, Event.WellKnownTypes.SNAPSHOT_DELETED, label, e);
    } finally {
      inFlight.remove(project);
    }
  }

  private void publishFailure(String project, String type, String label, Exception e) {
    restoreInterrupt(e);
    ApiLog.unexpected("a snapshot mutation for '" + project + "'", e);
    publish(
        project,
        type,
        Map.of(
            "label",
            label,
            "error",
            Objects.toString(e.getMessage(), "snapshot operation failed")));
  }

  private void publish(String project, String type, Map<String, Object> data) {
    events.publish(Event.of(project, null, type, Event.SAIL_AGENT, HostInfo.hostname(), data));
  }

  private void claim(String project) {
    if (!inFlight.add(project)) {
      throw new ApiException(
          ErrorCode.CONFLICT,
          "A snapshot operation is already in progress for project '" + project + "'.",
          "Wait for its snapshot_restored or snapshot_deleted event, then retry.");
    }
  }

  private void refuseLiveRun(String project, String label, String localHandle) {
    if (runStore == null) {
      return;
    }
    runStore.listForProject(project).stream()
        .filter(DispatchOperations::ownsLiveAgent)
        .filter(run -> SailOperations.ownsRun(run.node(), localHandle))
        .findFirst()
        .ifPresent(
            run -> {
              throw restoreRefusal(run, label);
            });
  }

  private static ApiException restoreRefusal(RunStore.RunRow run, String label) {
    var occupied =
        Strings.isBlank(run.specId())
            ? "Ad-hoc agent run " + run.id() + " is occupying this container"
            : "Agent run "
                + run.id()
                + " is already working spec '"
                + run.specId()
                + "' in this container";
    return new ApiException(
        ErrorCode.AGENT_ALREADY_RUNNING,
        occupied + "; restoring snapshot '" + label + "' would discard its live work.",
        "Wait for it to finish or stop it, then retry the restore.");
  }

  private void requireSnapshotExists(String project, String label) {
    var exists = listSnapshots(project).stream().anyMatch(info -> label.equals(info.name()));
    if (!exists) {
      throw new ApiException(
          ErrorCode.NOT_FOUND,
          "Snapshot '" + label + "' does not exist for project '" + project + "'.",
          "List snapshots with GET /v1/projects/" + project + "/snapshots.");
    }
  }

  private List<SnapshotManager.SnapshotInfo> listSnapshots(String project) {
    try {
      return snapshots.list(project);
    } catch (Exception e) {
      restoreInterrupt(e);
      throw new ApiException(
          ErrorCode.SNAPSHOT_FAILED,
          Objects.toString(e.getMessage(), "snapshot listing failed"),
          "Check the host's incus daemon and retry.",
          e);
    }
  }

  private static void restoreInterrupt(Exception e) {
    if (e instanceof InterruptedException) {
      Thread.currentThread().interrupt();
    }
  }
}
