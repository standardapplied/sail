/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.RunStatus;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.engine.ContainerConfig;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerState;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Keeps this box's spec workspaces to what their specs still need (D6), deciding each from what the
 * stores and Incus say now, never from an event (R8): a runner that restarts, a status that arrives
 * by sync, or an instance stopped by hand converges in the next sweep. Only a whole workspace
 * carrying {@value BoxInstances#SPEC_KEY} is swept; a person's, and one never made whole, are never
 * touched (R14, W12). A workspace a run of its spec is live in is left as it is (R13), unless its
 * spec or project was erased, which deletes it at once. Otherwise one whose spec is {@code done} or
 * {@code archived} is deleted, once every run of the spec here has its usage recorded and no repo
 * holds a commit no remote has (R4); one that fails either is kept, stopped, carrying {@value
 * BoxInstances#KEPT_KEY}, and said once in the spec's room. Any other is stopped {@link #IDLE}
 * after the spec's newest run here ended.
 *
 * <p>Each workspace is decided under the lock a run's entry takes, tried and never waited on: a run
 * reserved before the decision is seen live, and one reserved after it enters what the decision
 * left. A workspace whose lock another holds is left to the next sweep.
 */
public final class SpecWorkspaces {

  /** How long a spec's workspace runs with no run of its spec live before it is stopped (D6). */
  public static final Duration IDLE = Duration.ofMinutes(10);

  /**
   * Prints, for each repository under {@code $1}, the commits that no remote has and, of those, the
   * ones not on branch {@code $2} (all of them when it is empty or the repository has no such
   * branch), then its path: {@code <unpushed> <off the branch> <path>}. Nothing is fetched, so a
   * branch the forge deleted after its merge still counts as the remote's.
   */
  static final String UNPUSHED =
      """
      set -euo pipefail
      cd "$1"
      branch=$2
      find . -mindepth 2 -maxdepth 3 -name .git -prune -printf '%h\\n' | sort | while IFS= read -r repo; do
        repo=${repo#./}
        unpushed=$(git -C "$repo" rev-list --count HEAD --branches --not --remotes)
        off=$unpushed
        if [ -n "$branch" ] && git -C "$repo" show-ref --verify --quiet "refs/heads/$branch"; then
          off=$(git -C "$repo" rev-list --count HEAD --branches --not --remotes "refs/heads/$branch")
        fi
        printf '%s %s %s\\n' "$unpushed" "$off" "$repo"
      done
      """;

  private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration PUSH_TIMEOUT = Duration.ofMinutes(2);
  private static final Pattern COUNTED = Pattern.compile("([0-9]+) ([0-9]+) (.+)");
  private static final Pattern MILLIS = Pattern.compile("[0-9]{1,18}");
  private static final Map<String, String> C_LOCALE = Map.of("LC_ALL", "C");

  private final Workspaces workspaces;
  private final ShellExec shell;
  private final BoxInstances box;
  private final ContainerConfig config;
  private final SpecStore specs;
  private final RunStore runs;
  private final ChangeLog changeLog;
  private final RunUsageReader usage;
  private final RunLauncher.Room room;
  private final Supplier<String> localHandle;
  private final Supplier<Instant> clock;
  private final Set<String> logged = ConcurrentHashMap.newKeySet();

  /**
   * @param workspaces the box's workspaces, whose doors start, stop and delete one
   * @param shell reads Incus and runs in a workspace, always for real
   * @param locks the box's lock directory
   * @param room where a kept workspace is said, as Sail ({@link RunLauncher.Room#tell})
   */
  public SpecWorkspaces(
      Workspaces workspaces,
      ShellExec shell,
      Path locks,
      SpecStore specs,
      RunStore runs,
      ChangeLog changeLog,
      RunLauncher.Room room,
      Supplier<String> localHandle,
      Supplier<Instant> clock) {
    this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    this.shell = Objects.requireNonNull(shell, "shell");
    this.box = new BoxInstances(shell);
    this.config = new ContainerConfig(shell, locks);
    this.specs = Objects.requireNonNull(specs, "specs");
    this.runs = Objects.requireNonNull(runs, "runs");
    this.changeLog = Objects.requireNonNull(changeLog, "changeLog");
    this.usage = new RunUsageReader(shell);
    this.room = Objects.requireNonNull(room, "room");
    this.localHandle = Objects.requireNonNull(localHandle, "localHandle");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * One pass over the box's spec workspaces, as this box's machinery. Never throws: a workspace
   * that cannot be decided is logged and left to the next pass, and so is the box when Incus does
   * not list it.
   */
  public void sweep() {
    Actor.run(Actor.system(), this::pass);
  }

  private void pass() {
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
        log("could not tend " + instance.name() + ": " + e.getMessage());
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
    var row = specs.findById(specId);
    if (row.isEmpty() || !row.get().project().equals(project)) {
      logOnce(
          found.name(),
          row.isEmpty()
              ? "spec " + specId + " is not on this box"
              : "spec " + specId + " is filed under project " + row.get().project());
      return;
    }
    var node = localHandle.get();
    Predicate<String> live = spec -> runs.liveSpecs(node).contains(project + "/" + spec);
    if (live.test(specId)) {
      return;
    }
    var spec = row.get();
    var theirs =
        runs.listForSpec(specId).stream()
            .filter(run -> run.ownedBy(node) && project.equals(run.project()))
            .toList();
    if (spec.status() == SpecStatus.DONE || spec.status() == SpecStatus.ARCHIVED) {
      retire(found, workspace, spec, theirs, live);
      return;
    }
    if (found.key(BoxInstances.KEPT_KEY).isPresent()) {
      config.set(found.name(), List.of(BoxInstances.KEPT_KEY + "="));
    }
    if (found.state() instanceof ContainerState.Running
        && idle(found, theirs)
        && theirs.stream().noneMatch(SpecWorkspaces::unread)) {
      workspaces.stop(project, workspace.workspace(), live);
      log(
          "stopped "
              + found.name()
              + ": no run of spec "
              + specId
              + " for "
              + IDLE.toMinutes()
              + " min");
    }
  }

  /**
   * Deletes {@code found}, the workspace of done spec {@code spec}, once nothing its runs made
   * would be lost (R4), starting it first when it is stopped, since neither read runs in a stopped
   * instance. Otherwise it is kept, and stopped again unless it was running already, kept: then a
   * person started it to push by hand, and it is left to them.
   */
  private void retire(
      BoxInstances.Instance found,
      BoxInstances.Workspace workspace,
      SpecStore.SpecRow spec,
      List<RunStore.RunRow> theirs,
      Predicate<String> live)
      throws Exception {
    var project = workspace.project();
    var running = found.state() instanceof ContainerState.Running;
    if (!running) {
      try {
        workspaces.start(project, workspace.workspace());
      } catch (IOException | TimeoutException | RuntimeException e) {
        keep(found, workspace, spec, "it did not start: " + said(e.getMessage()));
        return;
      }
    }
    var lost = lost(found.name(), spec, theirs);
    if (lost.isEmpty()) {
      workspaces.delete(project, workspace.workspace(), live, instance -> {});
      log(
          "deleted "
              + found.name()
              + ": spec "
              + spec.id()
              + " is "
              + spec.status().wire()
              + " and nothing it made is lost");
      return;
    }
    var theirsToFinish = running && found.key(BoxInstances.KEPT_KEY).isPresent();
    keep(found, workspace, spec, lost.get());
    if (!theirsToFinish) {
      workspaces.stop(project, workspace.workspace(), live);
    }
  }

  /**
   * What deleting the running workspace {@code instance} would lose, or nothing: a run's usage it
   * does not answer, which is read and recorded here first, or a repo's commits no remote has, or
   * repos that cannot be counted. When every commit missing is on the runs' branch, that branch is
   * pushed, once in each repo holding some, and the repos are counted again.
   */
  private Optional<String> lost(
      String instance, SpecStore.SpecRow spec, List<RunStore.RunRow> theirs) throws Exception {
    for (var run : theirs.stream().filter(SpecWorkspaces::unread).toList()) {
      var read = usage.read(run);
      if (read == null) {
        return Optional.of("the usage of run " + run.id() + " could not be read");
      }
      runs.recordRead(run.id(), read);
    }
    var branch =
        theirs.stream()
            .map(RunStore.RunRow::branch)
            .filter(Strings::isNotBlank)
            .findFirst()
            .orElse(Objects.requireNonNullElse(spec.branch(), ""));
    try {
      var unpushed = unpushed(instance, branch);
      var elsewhere = unpushed.stream().filter(repo -> !repo.onTheBranch()).findFirst();
      if (unpushed.isEmpty() || elsewhere.isPresent()) {
        return elsewhere.map(repo -> repo.why(branch));
      }
      for (var repo : unpushed) {
        var pushed =
            shell.exec(
                ContainerExec.asDevUser(
                    instance,
                    null,
                    C_LOCALE,
                    List.of("git", "-C", repo.path(), "push", "origin", branch)),
                null,
                PUSH_TIMEOUT);
        if (!pushed.ok()) {
          return Optional.of(
              repo.repo() + ": the push of " + branch + " failed: " + said(pushed.stderr()));
        }
      }
      return unpushed(instance, branch).stream().findFirst().map(repo -> repo.why(branch));
    } catch (Uncounted uncounted) {
      return Optional.of(uncounted.getMessage());
    }
  }

  /** One repo's commits that no remote has, and how many of them are off the runs' branch. */
  private record Unpushed(String repo, int all, int off) {
    String path() {
      return ContainerExec.DEV_WORKSPACE + "/" + repo;
    }

    boolean onTheBranch() {
      return off == 0;
    }

    String why(String branch) {
      var count = onTheBranch() ? all : off;
      var commits = count + (count == 1 ? " commit" : " commits");
      return onTheBranch() || Strings.isBlank(branch)
          ? repo + ": " + commits + " that no remote has"
          : repo + ": " + commits + " on a branch other than " + branch + " that no remote has";
    }
  }

  /** Repos {@link #UNPUSHED} could not count, which a delete would lose unseen. */
  private static final class Uncounted extends Exception {
    Uncounted(String why) {
      super("its repos could not be counted: " + why);
    }
  }

  /** Every repo of {@code instance} holding a commit no remote has, by path. */
  private List<Unpushed> unpushed(String instance, String branch) throws Exception {
    var counted =
        shell.exec(
            ContainerExec.asDevUser(
                instance,
                null,
                C_LOCALE,
                List.of(
                    "bash", "-c", UNPUSHED, "sail-unpushed", ContainerExec.DEV_WORKSPACE, branch)),
            null,
            GIT_TIMEOUT);
    if (!counted.ok()) {
      throw new Uncounted(said(counted.reason()));
    }
    var unpushed = new ArrayList<Unpushed>();
    for (var line : counted.stdout().lines().toList()) {
      var fields = COUNTED.matcher(line);
      if (!fields.matches()) {
        throw new Uncounted(said(line));
      }
      var all = Integer.parseInt(fields.group(1));
      if (all > 0) {
        unpushed.add(new Unpushed(fields.group(3), all, Integer.parseInt(fields.group(2))));
      }
    }
    return unpushed;
  }

  private static String said(String text) {
    return Strings.oneLine(Objects.toString(text, "")).strip();
  }

  /**
   * Marks {@code found} kept for {@code why}, saying it in the spec's room only when it carried no
   * mark before, and only once the mark is written: the mark is what remembers it was said.
   */
  private void keep(
      BoxInstances.Instance found,
      BoxInstances.Workspace workspace,
      SpecStore.SpecRow spec,
      String why)
      throws Exception {
    var before = found.key(BoxInstances.KEPT_KEY);
    if (before.equals(Optional.of(why))) {
      return;
    }
    var written = config.set(found.name(), List.of(BoxInstances.KEPT_KEY + "=" + why));
    if (!written.ok()) {
      log("could not mark " + found.name() + " kept (" + why + "): " + written.reason());
      return;
    }
    if (before.isEmpty()) {
      log("kept " + found.name() + ": " + why);
      room.tell(spec.id(), notice(workspace, spec, why));
    }
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
            "Push what it holds by hand (",
            Remedy.sail("project workspace start", project, name),
            ", then ",
            Remedy.sail("project workspace shell", project, name),
            "), and Sail deletes it once nothing is left to lose; or drop it with ",
            Remedy.sail("project workspace delete", project, name),
            "."));
  }

  /**
   * Whether {@link #IDLE} has passed since the newest of {@code theirs} ended, or, with none, since
   * {@code found} was made whole.
   */
  private boolean idle(BoxInstances.Instance found, List<RunStore.RunRow> theirs) {
    var since =
        theirs.stream()
            .findFirst()
            .map(run -> Objects.requireNonNullElse(run.completedAt(), run.startedAt()))
            .or(
                () ->
                    found
                        .key(BoxInstances.READY_KEY)
                        .filter(MILLIS.asMatchPredicate())
                        .map(Long::parseLong)
                        .map(DateTimeUtils::ofMillis))
            .orElse(Instant.EPOCH);
    return !clock.get().isBefore(since.plus(IDLE));
  }

  /** Whether {@code run} finished with its report unread, as {@link RunStore#unread} reads it. */
  private static boolean unread(RunStore.RunRow run) {
    return RunStatus.isTerminal(run.status()) && run.usage() == null;
  }

  private void logOnce(String instance, String why) {
    if (logged.add(instance + ": " + why)) {
      log(instance + " is left as it is: " + why);
    }
  }

  private static void log(String line) {
    System.err.println("  [workspaces] " + line);
  }
}
