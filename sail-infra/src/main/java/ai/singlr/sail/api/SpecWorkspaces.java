/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.RunStatus;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.engine.ContainerConfig;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.Workspaces;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Keeps this box's spec workspaces to what their specs still need (D6, W13), from what the stores
 * and Incus say now (R8), on a pass of its own. A whole workspace carrying {@value
 * BoxInstances#SPEC_KEY} is deleted at once when its spec or project was erased, and otherwise
 * stopped once {@link #idle}; an idle one whose spec is {@code done} or {@code archived} is deleted
 * when its repos hold nothing its runs made (R4), or marked {@value BoxInstances#KEPT_KEY} and said
 * once in its room when they hold something. A failure decides nothing: logged once for its cause
 * and tried again once idle. Each is decided under the lock a run's entry takes, never waited on.
 */
public final class SpecWorkspaces implements AutoCloseable {

  /** How long a spec's workspace is idle before it is stopped, or decided when done (D6). */
  public static final Duration IDLE = Duration.ofMinutes(10);

  /** How often a pass starts. */
  static final Duration INTERVAL = Duration.ofSeconds(60);

  /** How long a workspace is held with nothing started or ended in it before that is said. */
  static final Duration HELD = Duration.ofHours(12);

  private final Workspaces workspaces;
  private final BoxInstances box;
  private final ContainerConfig config;
  private final WorkspaceGit git;
  private final RunReports reports;

  private final SpecStore specs;
  private final RunStore runs;
  private final ChangeLog changeLog;
  private final ProjectReader definitions;
  private final RunLauncher.Room room;
  private final Supplier<String> localHandle;
  private final Supplier<Instant> clock;
  private final Instant started;
  private final PeriodicPass pass;
  private final Set<String> said = ConcurrentHashMap.newKeySet();
  private final Map<String, Instant> lastHeld = new ConcurrentHashMap<>();

  /**
   * @param workspaces the box's workspaces, whose doors start, stop and delete one
   * @param shell reads Incus and runs in a workspace, always for real
   * @param locks the box's lock directory
   * @param definitions where a project's repos are read from * @param room where a kept workspace
   *     is said, as Sail ({@link RunLauncher.Room#tell})
   * @param clock the time now; read once here as this process's start
   */
  public SpecWorkspaces(
      Workspaces workspaces,
      ShellExec shell,
      Path locks,
      SpecStore specs,
      RunStore runs,
      ChangeLog changeLog,
      ProjectReader definitions,
      RunLauncher.Room room,
      RunReports reports,
      Supplier<String> localHandle,
      Supplier<Instant> clock) {
    this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    this.box = new BoxInstances(shell);
    this.config = new ContainerConfig(shell, locks);
    this.git = new WorkspaceGit(shell);
    this.reports = Objects.requireNonNull(reports, "reports");

    this.specs = Objects.requireNonNull(specs, "specs");
    this.runs = Objects.requireNonNull(runs, "runs");
    this.changeLog = Objects.requireNonNull(changeLog, "changeLog");
    this.definitions = Objects.requireNonNull(definitions, "definitions");
    this.room = Objects.requireNonNull(room, "room");
    this.localHandle = Objects.requireNonNull(localHandle, "localHandle");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.started = clock.get();
    this.pass = new PeriodicPass("workspaces", this::sweep);
  }

  /** Starts the passes, one each {@link #INTERVAL}, a pass still running skipping the next. */
  public void start() {
    pass.start(INTERVAL);
  }

  @Override
  public void close() {
    pass.close();
  }

  /** One pass over the box's spec workspaces, as this box's machinery; never throws. */
  public void sweep() {
    Actor.run(Actor.system(), this::sweepAll);
  }

  private void sweepAll() {
    List<BoxInstances.Instance> instances;
    try {
      instances = box.all();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    } catch (Exception e) {
      log("could not list this box's instances: " + e.getMessage());
      return;
    }
    for (var instance : instances) {
      var spec = instance.key(BoxInstances.SPEC_KEY);
      if (!(instance.kind() instanceof BoxInstances.Workspace workspace)
          || !workspace.ready()
          || spec.isEmpty()) {
        continue;
      }
      try {
        tend(instance.name(), workspace, spec.get());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        lastHeld.put(instance.name(), clock.get());
        logOnce(instance.name(), ": left as it is, and tried again once idle: " + e.getMessage());
      }
    }
  }

  private void tend(String name, BoxInstances.Workspace workspace, String specId) throws Exception {
    var held = workspaces.holdIfFree(name);
    if (held.isEmpty()) {
      return;
    }
    try (var lock = held.get()) {
      var found =
          box.lookup(name, Duration.ofSeconds(BoxInstances.LOOKUP_SECONDS))
              .filter(i -> i.kind().equals(workspace))
              .filter(i -> i.key(BoxInstances.SPEC_KEY).equals(Optional.of(specId)));
      if (found.isPresent()) {
        decide(found.get(), workspace, specId);
      }
    }
  }

  private void decide(BoxInstances.Instance found, BoxInstances.Workspace workspace, String specId)
      throws Exception {
    var project = workspace.project();
    if (changeLog.isErased(Erasure.SPEC, specId) || changeLog.isErased(Erasure.PROJECT, project)) {
      workspaces.delete(project, workspace.workspace(), spec -> false, instance -> {});
      log("deleted " + found.name() + ": spec " + specId + " or its project was erased");
      return;
    }
    var row = specs.findById(specId).filter(spec -> spec.project().equals(project));
    if (row.isEmpty()) {
      logOnce(
          found.name(),
          ": spec "
              + specId
              + " is not one of "
              + project
              + "'s on this box, so its workspace is stopped when idle and never deleted");
    }
    if (!idle(found, project, specId)) {
      return;
    }
    if (row.isPresent() && done(row.get()) && found.key(BoxInstances.KEPT_KEY).isEmpty()) {
      retire(found, workspace, row.get());
      return;
    }
    if (found.state() instanceof ContainerState.Running) {
      workspaces.stop(project, workspace.workspace(), spec -> false);
      log("stopped " + found.name() + ": idle for " + IDLE.toMinutes() + " min");
    }
  }

  /**
   * Decides the idle workspace {@code found} of done spec {@code spec}: started when stopped, then
   * deleted when nothing would be lost and the spec is still done and unoccupied, or kept and
   * stopped. A start or read that fails throws, stopping again what this started.
   */
  private void retire(
      BoxInstances.Instance found, BoxInstances.Workspace workspace, SpecStore.SpecRow spec)
      throws Exception {
    var project = workspace.project();
    var stopped = !(found.state() instanceof ContainerState.Running);
    Optional<String> lost;
    try {
      if (stopped) {
        workspaces.start(project, workspace.workspace());
      }
      lost = lost(found.name(), spec);
    } catch (IOException | TimeoutException | RuntimeException failed) {
      if (stopped) {
        workspaces.stop(project, workspace.workspace(), id -> false);
      }
      throw failed;
    }
    var now = specs.findById(spec.id()).filter(row -> row.project().equals(project));
    if (now.isEmpty() || !done(now.get()) || occupied(found.name(), true, project, spec.id())) {
      return;
    }
    if (lost.isEmpty()) {
      workspaces.delete(project, workspace.workspace(), id -> false, instance -> {});
      log(
          "deleted "
              + found.name()
              + ": spec "
              + spec.id()
              + " is "
              + spec.status().wire()
              + " and nothing its runs made is lost");
      return;
    }
    keep(found, workspace, spec, lost.get());
    workspaces.stop(project, workspace.workspace(), id -> false);
  }

  /**
   * What the repos of running workspace {@code instance} hold that no remote does ({@link
   * WorkspaceGit#left}), once its runs' unread usage is recorded; throws on what cannot be read.
   */
  private Optional<String> lost(String instance, SpecStore.SpecRow spec)
      throws IOException, TimeoutException, InterruptedException {
    var theirs = theirs(spec.project(), spec.id());
    for (var run : theirs.stream().filter(SpecWorkspaces::unread).toList()) {
      reports.finish(runs, run, report -> report != null && runs.recordRead(run.id(), report));
      if (runs.findById(run.id()).filter(SpecWorkspaces::unread).isPresent()) {
        throw new IOException("the usage of run " + run.id() + " could not be read");
      }
    }
    var definition =
        definitions
            .read(spec.project())
            .orElseThrow(
                () ->
                    new IOException(
                        "the definition of project "
                            + spec.project()
                            + " is not readable here, so neither are its repos"));
    var repos =
        Objects.requireNonNullElse(definition.repos(), List.<ProjectDefinition.Repo>of()).stream()
            .map(ProjectDefinition.Repo::path)
            .toList();
    var branch =
        theirs.stream()
            .map(RunStore.RunRow::branch)
            .filter(Strings::isNotBlank)
            .findFirst()
            .orElse(Objects.requireNonNullElse(spec.branch(), ""));
    return git.left(instance, repos, branch);
  }

  /**
   * Whether {@code found} is idle: nothing in it now ({@link #occupied}), and {@link #IDLE} since
   * the latest of its spec's last run here ending, Incus last starting it, this process starting
   * and this sweep last finding it held. One held {@link #HELD} with nothing started or ended in it
   * is said once.
   */
  private boolean idle(BoxInstances.Instance found, String project, String specId)
      throws InterruptedException {
    var now = clock.get();
    var ran =
        theirs(project, specId).stream()
            .map(run -> Objects.requireNonNullElse(run.completedAt(), run.startedAt()))
            .filter(Objects::nonNull)
            .reduce(latest(found.lastStarted(), started), SpecWorkspaces::latest);
    if (occupied(found.name(), found.state() instanceof ContainerState.Running, project, specId)) {
      lastHeld.put(found.name(), now);
      if (!ran.plus(HELD).isAfter(now)) {
        logOnce(
            found.name(),
            ": held 12 h with nothing started or ended in it (sudo incus operation list)");
      }
      return false;
    }
    return !latest(ran, lastHeld.getOrDefault(found.name(), Instant.EPOCH)).plus(IDLE).isAfter(now);
  }

  /**
   * Whether something is in {@code instance} now: a run of its spec live on this box, a room's
   * member included, or, when it is {@code running}, an operation Incus runs on it, an exec above
   * all ({@link BoxInstances#inUse}). An Incus that does not say counts as one that does.
   */
  private boolean occupied(String instance, boolean running, String project, String specId)
      throws InterruptedException {
    if (runs.liveSpecs(localHandle.get()).contains(project + "/" + specId)) {
      return true;
    }
    try {
      return running && box.inUse().contains(instance);
    } catch (IOException | TimeoutException unanswered) {
      return true;
    }
  }

  private List<RunStore.RunRow> theirs(String project, String specId) {
    var node = localHandle.get();
    return runs.listForSpec(specId).stream()
        .filter(run -> run.ownedBy(node) && project.equals(run.project()))
        .toList();
  }

  /**
   * Says {@code found} kept for {@code why} in the spec's room, then marks it, so a crash between
   * says it again rather than never; a mark not written is logged and decided again.
   */
  private void keep(
      BoxInstances.Instance found,
      BoxInstances.Workspace workspace,
      SpecStore.SpecRow spec,
      String why)
      throws Exception {
    room.tell(spec.id(), notice(workspace, spec, why));
    var written = config.set(found.name(), List.of(BoxInstances.KEPT_KEY + "=" + why));
    if (!written.ok()) {
      log("could not mark " + found.name() + " kept (" + why + "): " + written.reason());
      return;
    }
    log("kept " + found.name() + ": " + why);
  }

  private static String notice(
      BoxInstances.Workspace workspace, SpecStore.SpecRow spec, String why) {
    var project = workspace.project();
    var name = workspace.workspace();
    return Remedied.message(
        "Workspace "
            + name
            + " of "
            + project
            + " is kept, stopped, though spec "
            + spec.id()
            + " is "
            + spec.status().wire()
            + ": "
            + why
            + ".",
        Remedy.of(
            "Sail will not delete it. Take what it holds by hand (",
            Remedy.sail("project workspace start", project, name),
            ", then ",
            Remedy.sail("project workspace shell", project, name),
            "), then drop it with ",
            Remedy.sail("project workspace delete", project, name),
            "."));
  }

  private static boolean done(SpecStore.SpecRow spec) {
    return spec.status() == SpecStatus.DONE || spec.status() == SpecStatus.ARCHIVED;
  }

  /** Whether {@code run} finished with its report unread, as {@link RunStore#unread} reads it. */
  private static boolean unread(RunStore.RunRow run) {
    return RunStatus.isTerminal(run.status()) && run.usage() == null;
  }

  private static Instant latest(Instant a, Instant b) {
    return a.isAfter(b) ? a : b;
  }

  /** Logs {@code instance} then {@code line}, once per pair in this process. */
  private void logOnce(String instance, String line) {
    if (said.add(instance + line)) {
      log(instance + line);
    }
  }

  private static void log(String line) {
    System.err.println("  [workspaces] " + line);
  }
}
