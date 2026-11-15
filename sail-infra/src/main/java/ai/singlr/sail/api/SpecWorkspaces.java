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
import ai.singlr.sail.engine.AgentPresence;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.engine.ContainerConfig;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.RunPlace;
import ai.singlr.sail.engine.RunUsageReader;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Keeps this box's spec workspaces to what their specs still need (D6), deciding each from what the
 * stores and Incus say now, never from an event (R8), on a pass of its own that never holds up
 * another. Only a whole workspace carrying {@value BoxInstances#SPEC_KEY} is swept; a person's, and
 * one never made whole, are never touched (R14, W12). One whose spec or project was erased is
 * deleted at once. Every other one is stopped once it is idle: no run of its spec live here (a
 * room's member is one), no terminal open in it, for {@link #IDLE} since the later of its spec's
 * last run here ending and this process starting (R13). An idle one whose spec is {@code done} or
 * {@code archived} is decided once: deleted when nothing its runs made would be lost (R4), or
 * marked {@value BoxInstances#KEPT_KEY} and said once in the spec's room, after which the sweep
 * only stops it when idle, never starts or deletes it: its person does.
 *
 * <p>Each workspace is decided under the lock a run's entry takes, tried and never waited on, and a
 * delete acts on the spec and its runs read again just before it, so a run reserved or a spec
 * reopened meanwhile keeps the workspace.
 */
public final class SpecWorkspaces implements AutoCloseable {

  /** How long a spec's workspace is idle before it is stopped, or decided when done (D6). */
  public static final Duration IDLE = Duration.ofMinutes(10);

  /** How often a pass starts. */
  static final Duration INTERVAL = Duration.ofSeconds(60);

  private final Workspaces workspaces;
  private final BoxInstances box;
  private final ContainerConfig config;
  private final AgentPresence presence;
  private final WorkspaceGit git;
  private final RunUsageReader usage;
  private final SpecStore specs;
  private final RunStore runs;
  private final ChangeLog changeLog;
  private final ProjectReader definitions;
  private final RunLauncher.Room room;
  private final Supplier<String> localHandle;
  private final Supplier<Instant> clock;
  private final Instant started;
  private final PeriodicPass pass;
  private final Set<String> logged = ConcurrentHashMap.newKeySet();
  private final Set<String> failing = ConcurrentHashMap.newKeySet();

  /**
   * @param workspaces the box's workspaces, whose doors start, stop and delete one
   * @param shell reads Incus and runs in a workspace, always for real
   * @param locks the box's lock directory
   * @param definitions where a project's repos are read from
   * @param room where a kept workspace is said, as Sail ({@link RunLauncher.Room#tell})
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
      Supplier<String> localHandle,
      Supplier<Instant> clock) {
    this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    this.box = new BoxInstances(shell);
    this.config = new ContainerConfig(shell, locks);
    this.presence = new AgentPresence(shell);
    this.git = new WorkspaceGit(shell);
    this.usage = new RunUsageReader(shell);
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

  /**
   * One pass over the box's spec workspaces, as this box's machinery. Never throws: a workspace
   * that cannot be decided is logged once until a pass decides it, and the pass goes on to the
   * next; a box whose Incus does not list is logged and left to the next pass.
   */
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
        failing.remove(instance.name());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        if (failing.add(instance.name())) {
          log("could not tend " + instance.name() + ": " + e.getMessage());
        }
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
   * Decides, once, the idle workspace {@code found} of done spec {@code spec}: started when
   * stopped, since nothing runs in a stopped instance; deleted when nothing its runs made would be
   * lost, as the spec and its runs say just before the delete; otherwise kept and stopped. A spec
   * reopened, or a run started, during the reads leaves the workspace as it is, unkept, for a later
   * pass.
   */
  private void retire(
      BoxInstances.Instance found, BoxInstances.Workspace workspace, SpecStore.SpecRow spec)
      throws Exception {
    var project = workspace.project();
    if (!(found.state() instanceof ContainerState.Running)) {
      try {
        workspaces.start(project, workspace.workspace());
      } catch (IOException | TimeoutException | RuntimeException e) {
        keep(found, workspace, spec, "it did not start: " + said(e.getMessage()));
        return;
      }
    }
    var lost = lost(found.name(), spec);
    var now = specs.findById(spec.id()).filter(row -> row.project().equals(project));
    if (now.isEmpty() || !done(now.get()) || !idle(found, project, spec.id())) {
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
   * What deleting the running workspace {@code instance} would lose, or nothing: a run's usage it
   * does not answer, which is read and recorded here first, then whatever its project's repos hold
   * that no remote does ({@link WorkspaceGit#left}).
   */
  private Optional<String> lost(String instance, SpecStore.SpecRow spec)
      throws InterruptedException {
    var theirs = theirs(spec.project(), spec.id());
    for (var run : theirs.stream().filter(SpecWorkspaces::unread).toList()) {
      var read = usage.read(run);
      if (read == null) {
        return Optional.of("the usage of run " + run.id() + " could not be read");
      }
      runs.recordRead(run.id(), read);
    }
    Optional<ProjectDefinition> definition;
    try {
      definition = definitions.read(spec.project());
    } catch (RuntimeException unreadable) {
      definition = Optional.empty();
    }
    if (definition.isEmpty()) {
      return Optional.of(
          "the definition of project "
              + spec.project()
              + " is not readable here, so neither are its repos");
    }
    var repos =
        Objects.requireNonNullElse(definition.get().repos(), List.<ProjectDefinition.Repo>of())
            .stream()
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
   * Whether {@code found} is idle: no run of the spec live on this box, a room's member included,
   * no terminal open in it ({@link AgentUnit#TERMINAL}, a workspace that does not answer counting
   * as one open), and {@link #IDLE} since the later of the spec's last run here ending and this
   * process starting, so a restart never stops a workspace before the loop has replayed its runs.
   */
  private boolean idle(BoxInstances.Instance found, String project, String specId) {
    if (runs.liveSpecs(localHandle.get()).contains(project + "/" + specId)) {
      return false;
    }
    var since =
        theirs(project, specId).stream()
            .map(run -> Objects.requireNonNullElse(run.completedAt(), run.startedAt()))
            .filter(Objects::nonNull)
            .reduce(started, (a, b) -> a.isAfter(b) ? a : b);
    if (since.plus(IDLE).isAfter(clock.get())) {
      return false;
    }
    if (!(found.state() instanceof ContainerState.Running)) {
      return true;
    }
    try {
      return presence.gone(RunPlace.of(project, specId), AgentUnit.TERMINAL);
    } catch (Exception unanswered) {
      return false;
    }
  }

  private List<RunStore.RunRow> theirs(String project, String specId) {
    var node = localHandle.get();
    return runs.listForSpec(specId).stream()
        .filter(run -> run.ownedBy(node) && project.equals(run.project()))
        .toList();
  }

  /**
   * Marks {@code found} kept for {@code why} and says it in the spec's room, once: the room is told
   * first and the mark written after, so a crash between the two says it again rather than never. A
   * mark that cannot be written is logged, and a later pass decides the workspace again.
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

  private static String said(String text) {
    return Strings.oneLine(Objects.toString(text, "")).strip();
  }

  /** Logs {@code instance} followed by {@code said}, once per workspace in this process. */
  private void logOnce(String instance, String said) {
    if (logged.add(instance + said)) {
      log(instance + said);
    }
  }

  private static void log(String line) {
    System.err.println("  [workspaces] " + line);
  }
}
