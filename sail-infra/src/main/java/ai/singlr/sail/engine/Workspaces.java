/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.Mappable;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.ProjectDefinition;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * A project's workspaces on this box. The project's own container, named after it, is its default
 * workspace {@value #DEFAULT}, which nothing here writes to, copies, snapshots or restarts (W1).
 * Every other workspace is an Incus instance cloned from a snapshot of the project's template
 * ({@link WorkspaceTemplate}) and told by its keys ({@link BoxInstances}): the one instance named
 * {@code <project>-<workspace>} that carries that pair (W3, W4). A workspace is whole once it
 * carries {@value BoxInstances#READY_KEY}, written last (W12): one without it is listed as
 * incomplete, never started or entered, and replaced by the next create of its name.
 */
public final class Workspaces {

  /** The name the project's own container goes by among its workspaces. */
  public static final String DEFAULT = "main";

  static final String TEMPLATE_KEY = "user.sail.template";
  static final String CREATED_KEY = "user.sail.created_ms";

  /**
   * Written by the copy and cleared with the ready mark: a workspace without the mark that carries
   * it is one a create did not finish, which the next create replaces; one without either had its
   * mark taken away by hand, and is left to {@code workspace delete}.
   */
  static final String CREATING_KEY = "user.sail.creating";

  /**
   * The template's own keys, which its clone carries from the copy and drops with the ready mark: a
   * workspace is no template.
   */
  private static final List<String> TEMPLATE_KEYS =
      List.of(
          BoxInstances.TEMPLATE_OF_KEY,
          WorkspaceTemplate.CURRENT_KEY,
          WorkspaceTemplate.INPUTS_KEY,
          WorkspaceTemplate.LAST_KEY,
          ProjectProvisioner.INPUTS_KEY);

  private static final String STEP = "sail-step ";

  private static final Pattern MILLIS = Pattern.compile("[0-9]{1,18}");
  private static final Duration READY_TIMEOUT = Duration.ofMinutes(10);
  private static final long NANOS_PER_MILLI = 1_000_000L;
  private static final PrintStream DISCARD =
      new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);

  /**
   * Waits for the dev user's session bus, the readiness {@code incus exec} as that user needs, in
   * the container itself, so a workspace is ready the moment its bus is and no host poll adds to
   * the time it reports.
   */
  static final String AWAIT_BUS =
      """
      for _ in $(seq 1 300); do [ -S /run/user/1000/bus ] && break; sleep 0.1; done
      if [ ! -S /run/user/1000/bus ]; then
        echo "the dev user's session bus did not come up within 30 s" >&2
        exit 1
      fi
      """;

  /**
   * Fetches the repository at {@code $1} as the dev user, asking again for up to 10 s while its
   * host does not resolve: a clone's network answers before its resolver does, by a fraction of a
   * second and by more when several clones start at once. Any other failure is said at once.
   */
  static final String FETCH =
      """
      fetch() {
        for _ in $(seq 1 100); do
          if failed=$(as_dev git -C "$1" fetch --quiet origin 2>&1); then return 0; fi
          case $failed in
            *"Could not resolve host"*) sleep 0.1 ;;
            *) printf '%s\\n' "$failed" >&2; return 1 ;;
          esac
        done
        printf '%s\\n' "$failed" >&2
        return 1
      }
      """;

  /**
   * What makes a fresh clone a workspace of its own, run as root with the project ({@code $1}), the
   * workspace ({@code $2}) and each repository's path and base branch after them, none of them ever
   * part of the text: a machine-id of its own, the project's name as its hostname (which the
   * in-container helpers read the project from; Incus rewrote it to the instance's name on the
   * copy), SSH host keys of its own made under that name, every repository fetched and switched to
   * the workspace's branch (the remote's when it has one, else a new one from the base), sshd
   * restarted onto the new keys once systemd answers (it does not yet when {@code incus start}
   * returns, and it boots while the repositories are fetched), and the bus. It prints {@code
   * sail-step <step> <epoch ms>} as each step ends, which the create reads as the steps' times.
   */
  static final String PREPARE =
      """
      set -eu
      project=$1 workspace=$2
      shift 2
      step() { printf 'sail-step %s %s\\n' "$1" "$(date +%s%3N)"; }
      step start
      rm -f /etc/machine-id
      systemd-machine-id-setup >/dev/null 2>&1
      if [ -e /var/lib/dbus/machine-id ]; then ln -sf /etc/machine-id /var/lib/dbus/machine-id; fi
      printf '%s\\n' "$project" > /etc/hostname
      hostname "$project"
      sed -i "s/^\\(127\\.0\\.1\\.1[[:space:]]*\\).*/\\1$project/" /etc/hosts
      rm -f /etc/ssh/ssh_host_*
      ssh-keygen -A >/dev/null
      step identity
      as_dev() { runuser -u dev -- env HOME=/home/dev "$@"; }
      """
          + FETCH
          + """
          if [ "$#" -gt 0 ]; then
            for _ in $(seq 1 300); do
              ip -4 -o addr show dev eth0 | grep -q inet && break
              sleep 0.1
            done
          fi
          step network
          while [ "$#" -ge 2 ]; do
            repo=/home/dev/workspace/$1 base=$2
            shift 2
            fetch "$repo"
            if as_dev git -C "$repo" show-ref --verify --quiet "refs/remotes/origin/$workspace"; then
              as_dev git -C "$repo" switch --quiet -C "$workspace" "origin/$workspace"
            else
              if [ -z "$base" ]; then
                base=$(as_dev git -C "$repo" symbolic-ref --short refs/remotes/origin/HEAD)
                base=${base#origin/}
              fi
              as_dev git -C "$repo" switch --quiet --no-track -c "$workspace" "origin/$base"
            fi
          done
          step repos
          for _ in $(seq 1 300); do systemctl show --property=Version >/dev/null 2>&1 && break; sleep 0.1; done
          systemctl try-restart ssh.service
          step sshd
          """
          + AWAIT_BUS
          + """
          step bus
          """;

  /**
   * What each phase took, in milliseconds, measured on {@link System#nanoTime}: the clone ({@code
   * null} for a start), the start, the readiness that follows it, and the whole; and each step of
   * the readiness by name, in the order they ran.
   */
  public record Timings(
      Long cloneMs, long startMs, long readyMs, long totalMs, Map<String, Long> readySteps) {}

  /** A workspace made: its instance, the template version it was cloned from, and its times. */
  public record Created(
      String project,
      String workspace,
      String instance,
      WorkspaceTemplate.Template template,
      Timings timings) {}

  /** What a start, stop or delete did. */
  public enum Change {
    STARTED,
    ALREADY_RUNNING,
    STOPPED,
    ALREADY_STOPPED,
    DELETED
  }

  /** A workspace started, stopped, deleted or left as it was, with a start's times. */
  public record Changed(
      String project, String workspace, String instance, Change change, Timings timings) {}

  /**
   * One workspace as Incus has it: its name, instance and state ({@code incomplete} for one that
   * was never made whole), the template version it was cloned from (none for the default
   * workspace), when it was created and its IPv4 address.
   */
  public record Row(
      String name, String instance, String state, String template, String createdAt, String ipv4)
      implements Mappable {

    @Override
    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("instance", instance);
      map.put("state", state);
      map.put("template", template);
      map.put("created_at", createdAt);
      map.put("ipv4", ipv4);
      return map;
    }
  }

  /** A project's workspaces, its default one first. */
  public record Listing(String project, List<Row> workspaces) {}

  private final ShellExec observer;
  private final ShellExec shell;
  private final LongSupplier nanos;
  private final Path locks;
  private final BoxInstances box;
  private final ContainerManager containers;

  /**
   * @param observer reads Incus, always for real
   * @param shell makes every change, narrating it instead in a dry run
   * @param nanos the clock phases are measured on, {@code System::nanoTime} outside tests
   * @param locks the box's lock directory
   */
  public Workspaces(ShellExec observer, ShellExec shell, LongSupplier nanos, Path locks) {
    this.observer = Objects.requireNonNull(observer, "observer");
    this.shell = Objects.requireNonNull(shell, "shell");
    this.nanos = Objects.requireNonNull(nanos, "nanos");
    this.locks = Objects.requireNonNull(locks, "locks");
    this.box = new BoxInstances(observer);
    this.containers = new ContainerManager(shell);
  }

  /**
   * Creates workspace {@code name} of {@code config}'s project: a clone of its template's current
   * version, built first when there is none or it is stale, then started, made its own, and marked
   * whole. One create of a workspace runs at a time, under its lock: of two of one name, the second
   * finds the first's workspace and is refused. An instance a create of it did not finish is
   * deleted and made again.
   */
  public Created create(
      ProjectDefinition config, String name, WorkspaceTemplate.Source source, Path files)
      throws Exception {
    var project = config.name();
    var instance = requireNewName(project, name);
    var zfs = WorkspaceTemplate.requireZfs(observer);
    var template = WorkspaceTemplate.requireName(project, source);
    if (shell.isDryRun()) {
      return create(config, name, instance, template, zfs, source, files);
    }
    try (var held = FileMutex.acquire(locks.resolve(instance + ".workspace.lock"))) {
      return create(config, name, instance, template, zfs, source, files);
    }
  }

  private Created create(
      ProjectDefinition config,
      String name,
      String instance,
      String template,
      List<String> zfs,
      WorkspaceTemplate.Source source,
      Path files)
      throws Exception {
    var project = config.name();
    var unfinished = requireUnclaimed(project, name, instance, source, box.all());
    var built = new WorkspaceTemplate(observer, shell, locks).ensure(config, source, template, zfs);
    if (unfinished) {
      containers.forceDelete(instance);
    }
    var started = nanos.getAsLong();
    clone(project, name, instance, built);
    try {
      var cloned = nanos.getAsLong();
      containers.start(instance);
      var running = nanos.getAsLong();
      var steps = prepare(config, name, instance, files);
      var ready = nanos.getAsLong();
      return new Created(
          project,
          name,
          instance,
          built,
          new Timings(
              millis(cloned - started),
              millis(running - cloned),
              millis(ready - running),
              millis(ready - started),
              steps));
    } catch (Exception e) {
      discard(instance, e);
      throw e;
    }
  }

  /**
   * Project {@code project}'s workspaces: its default one, then every workspace, by name. A box
   * with no ZFS pool has only the default one, which is answered as on any other box.
   */
  public Listing list(String project) throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(project);
    var instances = box.all();
    var rows = new ArrayList<Row>();
    rows.add(
        instances.stream()
            .filter(i -> i.name().equals(project) && i.plain())
            .findFirst()
            .map(i -> row(DEFAULT, i, word(i.state()), null, fromIncus(i.createdAt())))
            .orElse(
                new Row(
                    DEFAULT, project, word(new ContainerState.NotCreated()), null, null, null)));
    instances.stream()
        .filter(i -> i.kind() instanceof BoxInstances.Workspace w && w.project().equals(project))
        .sorted(Comparator.comparing(BoxInstances.Instance::name))
        .map(
            i -> {
              var workspace = (BoxInstances.Workspace) i.kind();
              return row(
                  workspace.workspace(),
                  i,
                  workspace.ready() ? word(i.state()) : "incomplete",
                  i.key(TEMPLATE_KEY).orElse(null),
                  i.key(CREATED_KEY).map(Workspaces::fromMillis).orElse(null));
            })
        .forEach(rows::add);
    return new Listing(project, List.copyOf(rows));
  }

  /** Starts workspace {@code name}, stopped or frozen, and says what its resume took. */
  public Changed start(String project, String name) throws Exception {
    var found = whole(project, name, "project start", true);
    if (found.state() instanceof ContainerState.Running) {
      return new Changed(project, name, found.name(), Change.ALREADY_RUNNING, null);
    }
    var started = nanos.getAsLong();
    containers.start(found.name());
    var running = nanos.getAsLong();
    awaitReady(found.name());
    var ready = nanos.getAsLong();
    return new Changed(
        project,
        name,
        found.name(),
        Change.STARTED,
        new Timings(
            null,
            millis(running - started),
            millis(ready - running),
            millis(ready - started),
            Map.of()));
  }

  /** Stops workspace {@code name} when its processes run. */
  public Changed stop(String project, String name) throws Exception {
    var found = existing(project, name, "project stop", true);
    if (!found.state().live()) {
      return new Changed(project, name, found.name(), Change.ALREADY_STOPPED, null);
    }
    containers.stop(found.name());
    return new Changed(project, name, found.name(), Change.STOPPED, null);
  }

  /** Deletes workspace {@code name}, whole or not, and with its instance the workspace (W3). */
  public Changed delete(String project, String name) throws Exception {
    return delete(project, name, instance -> {});
  }

  /**
   * As {@link #delete(String, String)}, once {@code confirm}, handed the workspace's instance,
   * returns: it is asked only of a workspace that exists, so a missing one or the default one is
   * refused before anything is asked.
   */
  public Changed delete(String project, String name, Consumer<String> confirm) throws Exception {
    var found = existing(project, name, "project destroy", true);
    confirm.accept(found.name());
    containers.forceDelete(found.name());
    return new Changed(project, name, found.name(), Change.DELETED, null);
  }

  /** The instance of workspace {@code name}, when it runs: where a shell opens. */
  public String running(String project, String name) throws Exception {
    var found = whole(project, name, "project shell", false);
    return switch (found.state()) {
      case ContainerState.Running ignored -> found.name();
      case ContainerState.Stopped ignored -> throw notRunning(project, name, "stopped");
      case ContainerState.Frozen ignored -> throw notRunning(project, name, "frozen");
      case ContainerState.NotCreated _, ContainerState.Error _ ->
          throw new IllegalStateException(
              "Workspace '"
                  + name
                  + "' of "
                  + project
                  + " is not running: Incus lists it as "
                  + found.state()
                  + ".");
    };
  }

  private void clone(
      String project, String name, String instance, WorkspaceTemplate.Template template)
      throws IOException, InterruptedException, TimeoutException {
    var copied =
        shell.exec(
            List.of(
                "incus",
                "copy",
                template.instance() + "/" + template.version(),
                instance,
                "-c",
                BoxInstances.PROJECT_KEY + "=" + project,
                "-c",
                BoxInstances.WORKSPACE_KEY + "=" + name,
                "-c",
                TEMPLATE_KEY + "=" + template.version(),
                "-c",
                CREATED_KEY + "=" + DateTimeUtils.toMillis(DateTimeUtils.now()),
                "-c",
                CREATING_KEY + "=1"));
    if (!copied.ok()) {
      throw new IOException(
          "Failed to clone "
              + template.instance()
              + "/"
              + template.version()
              + " as '"
              + instance
              + "': "
              + copied.reason());
    }
  }

  /**
   * Makes {@code instance} workspace {@code name} of its own and marks it whole, answering what
   * each step took: the script's steps as it timed them, then the shared files, the machinery and
   * the mark. The mark drops the template's keys and the copy's {@value #CREATING_KEY} in the same
   * write.
   */
  private Map<String, Long> prepare(
      ProjectDefinition config, String name, String instance, Path files)
      throws IOException, InterruptedException, TimeoutException {
    var argv =
        new ArrayList<>(List.of("bash", "-c", PREPARE, "sail-workspace", config.name(), name));
    for (var repo : Objects.requireNonNullElse(config.repos(), List.<ProjectDefinition.Repo>of())) {
      argv.add(repo.path());
      argv.add(Objects.requireNonNullElse(repo.branch(), ""));
    }
    var prepared = shell.exec(ContainerExec.asRoot(instance, argv), null, READY_TIMEOUT);
    if (!prepared.ok()) {
      throw new IOException(
          "Workspace '" + name + "' could not be made its own: " + prepared.reason());
    }
    var steps = steps(prepared.stdout());
    var at = nanos.getAsLong();
    new ProjectApplier(observer, shell, DISCARD)
        .applyWorkspaceFiles(instance, files, ProjectDefinition.USER);
    at = took(steps, "files", at);
    ContainerSetupSweep.reconcile(shell, instance, locks);
    at = took(steps, "machinery", at);
    var mark = new ArrayList<String>();
    mark.add(BoxInstances.READY_KEY + "=" + DateTimeUtils.toMillis(DateTimeUtils.now()));
    mark.add(CREATING_KEY + "=");
    TEMPLATE_KEYS.forEach(key -> mark.add(key + "="));
    var marked = new ContainerConfig(shell, locks).set(instance, mark);
    if (!marked.ok()) {
      throw new IOException(
          "Workspace '" + name + "' could not be marked whole: " + marked.reason());
    }
    took(steps, "mark", at);
    return Collections.unmodifiableMap(steps);
  }

  /**
   * The steps {@code stdout}, the prepare script's, timed, each {@code sail-step <step> <epoch ms>}
   * line ending one, the first marking the start; lines of any other shape are passed over.
   */
  static LinkedHashMap<String, Long> steps(String stdout) {
    var steps = new LinkedHashMap<String, Long>();
    Long before = null;
    for (var line : Objects.requireNonNullElse(stdout, "").lines().toList()) {
      var fields = line.startsWith(STEP) ? line.substring(STEP.length()).split(" ") : new String[0];
      if (fields.length != 2 || !MILLIS.matcher(fields[1]).matches()) {
        continue;
      }
      var at = Long.parseLong(fields[1]);
      if (before != null) {
        steps.put(fields[0], at - before);
      }
      before = at;
    }
    return steps;
  }

  private long took(Map<String, Long> steps, String step, long since) {
    var now = nanos.getAsLong();
    steps.put(step, millis(now - since));
    return now;
  }

  private void awaitReady(String instance)
      throws IOException, InterruptedException, TimeoutException {
    var ready = shell.exec(ContainerExec.asRoot(instance, List.of("bash", "-c", AWAIT_BUS)));
    if (!ready.ok()) {
      throw new IOException("Workspace '" + instance + "' did not come up: " + ready.reason());
    }
    ContainerSetupSweep.reconcile(shell, instance, locks);
  }

  /**
   * Deletes {@code instance}, made by the create that {@code failure} ended; one left by a create
   * that could not get here carries no ready mark and is replaced by the next create.
   */
  private void discard(String instance, Exception failure) throws InterruptedException {
    try {
      containers.forceDelete(instance);
    } catch (IOException | TimeoutException removal) {
      failure.addSuppressed(removal);
    }
  }

  /**
   * {@code name} as a new workspace of {@code project}, and the instance it would be, checked by
   * name alone (W4).
   */
  private static String requireNewName(String project, String name) {
    NameValidator.requireValidProjectName(project);
    requireWorkspaceName(name);
    if (DEFAULT.equals(name)) {
      throw Remedied.argument(
          "'"
              + DEFAULT
              + "' is "
              + project
              + "'s default workspace, its own container, so no new workspace takes that name.",
          Remedy.of(
              "Name the workspace for its line of work, e.g. ",
              Remedy.sail("project workspace create", project, "fix-login"),
              "."));
    }
    if (name.endsWith("-")) {
      throw new IllegalArgumentException(
          "Workspace '"
              + name
              + "' ends in '-', and Incus names no instance so. Give the workspace another name.");
    }
    var instance = project + "-" + name;
    WorkspaceTemplate.requireLength(instance, "Workspace '" + name + "' of " + project);
    if (instance.endsWith(BoxInstances.TEMPLATE_SUFFIX)) {
      throw new IllegalArgumentException(
          "Workspace '"
              + name
              + "' of "
              + project
              + " would be the instance '"
              + instance
              + "', a name kept for templates. Give the workspace another name.");
    }
    return instance;
  }

  /**
   * Refuses a name already taken; answers whether the instance there is this workspace a create did
   * not finish (it carries {@value #CREATING_KEY}), which the create deletes and makes again. One
   * that lost its ready mark otherwise is refused, naming {@code workspace delete}.
   */
  private static boolean requireUnclaimed(
      String project,
      String name,
      String instance,
      WorkspaceTemplate.Source source,
      List<BoxInstances.Instance> instances) {
    var strays =
        instances.stream()
            .filter(
                i ->
                    i.kind() instanceof BoxInstances.Stray(var p, var w)
                        && project.equals(p)
                        && name.equals(w))
            .map(BoxInstances.Instance::name)
            .toList();
    if (!strays.isEmpty()) {
      throw new IllegalStateException(
          "Instance "
              + String.join(", ", strays)
              + " carries the keys of workspace '"
              + name
              + "' of "
              + project
              + " under another name. Delete it or its keys before creating the workspace.");
    }
    var taken = instances.stream().filter(i -> i.name().equals(instance)).findFirst();
    if (taken.isPresent()
        && taken.get().kind() instanceof BoxInstances.Workspace(var p, var w, var ready)
        && p.equals(project)
        && w.equals(name)) {
      if (ready) {
        throw new IllegalStateException(
            "Workspace '"
                + name
                + "' of "
                + project
                + " exists already, as the instance '"
                + instance
                + "'.");
      }
      if (taken.get().key(CREATING_KEY).isEmpty()) {
        throw markTakenOff(project, name);
      }
      return true;
    }
    if (taken.isPresent()) {
      throw new IllegalArgumentException(
          "Workspace '"
              + name
              + "' of "
              + project
              + " would be the instance '"
              + instance
              + "', which Incus already has. Give the workspace another name.");
    }
    if (source.held().test(instance)) {
      throw new IllegalArgumentException(
          "Workspace '"
              + name
              + "' of "
              + project
              + " would be the instance '"
              + instance
              + "', the container of project '"
              + instance
              + "'. Give the workspace another name.");
    }
    return false;
  }

  /** As {@link #existing}, refused when the workspace was never made whole (W12). */
  private BoxInstances.Instance whole(
      String project, String name, String projectVerb, boolean changes)
      throws IOException, InterruptedException, TimeoutException {
    var found = existing(project, name, projectVerb, changes);
    if (!((BoxInstances.Workspace) found.kind()).ready() && found.key(CREATING_KEY).isEmpty()) {
      throw markTakenOff(project, name);
    }
    if (!((BoxInstances.Workspace) found.kind()).ready()) {
      throw Remedied.state(
          "Workspace '"
              + name
              + "' of "
              + project
              + " was never made whole: a create of it did not finish.",
          Remedy.of(
              "Create it again, which replaces it: ",
              Remedy.sail("project workspace create", project, name),
              "."));
    }
    return found;
  }

  /**
   * Workspace {@code name} of {@code project}: the one instance named {@code <project>-<name>}
   * carrying that pair. Any other instance carrying the pair is refused with it, naming each (W4).
   * A verb that {@code changes} it is refused first on a box with no ZFS pool (W8).
   */
  private BoxInstances.Instance existing(
      String project, String name, String projectVerb, boolean changes)
      throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(project);
    requireWorkspaceName(name);
    if (DEFAULT.equals(name)) {
      throw Remedied.argument(
          "'"
              + DEFAULT
              + "' is "
              + project
              + "'s default workspace, the project's own container, which workspace commands leave"
              + " alone.",
          Remedy.of("Use: ", Remedy.sail(projectVerb, project), "."));
    }
    if (changes) {
      WorkspaceTemplate.requireZfs(observer);
    }
    var carrying =
        box.all().stream()
            .filter(
                i ->
                    switch (i.kind()) {
                      case BoxInstances.Workspace w ->
                          w.project().equals(project) && w.workspace().equals(name);
                      case BoxInstances.Stray s ->
                          project.equals(s.project()) && name.equals(s.workspace());
                      default -> false;
                    })
            .toList();
    if (carrying.isEmpty()) {
      throw absent(project, name);
    }
    if (carrying.size() > 1 || !(carrying.getFirst().kind() instanceof BoxInstances.Workspace)) {
      throw new IllegalStateException(
          (carrying.size() == 1 ? "Instance " : "Instances ")
              + String.join(", ", carrying.stream().map(BoxInstances.Instance::name).toList())
              + (carrying.size() == 1 ? " carries" : " carry")
              + " the keys of workspace '"
              + name
              + "' of "
              + project
              + ", which only "
              + project
              + "-"
              + name
              + " may: nothing is done to any of them. Delete the copies, or their keys, first.");
    }
    return carrying.getFirst();
  }

  private static void requireWorkspaceName(String name) {
    if (!NameValidator.isValidProjectName(name)) {
      throw new IllegalArgumentException(
          "Invalid workspace name: '"
              + name
              + "'. Must match [a-z0-9][a-z0-9-]*, as a project name does.");
    }
  }

  /** A workspace whose create finished and whose ready mark was taken off since, by hand. */
  private static IllegalStateException markTakenOff(String project, String name) {
    return Remedied.state(
        "Workspace '"
            + name
            + "' of "
            + project
            + " has no ready mark, yet a create of it finished: the mark was taken off by hand, so"
            + " the workspace is left as it is.",
        Remedy.of(
            "Delete it if it is not wanted: ",
            Remedy.sail("project workspace delete", project, name),
            "."));
  }

  private static IllegalStateException absent(String project, String name) {
    return Remedied.state(
        "Project '" + project + "' has no workspace '" + name + "' on this box.",
        Remedy.of("List them with: ", Remedy.sail("project workspace list", project), "."));
  }

  private static IllegalStateException notRunning(String project, String name, String state) {
    return Remedied.state(
        "Workspace '" + name + "' of " + project + " is " + state + ".",
        Remedy.of("Start it with: ", Remedy.sail("project workspace start", project, name), "."));
  }

  private static Row row(
      String name,
      BoxInstances.Instance instance,
      String state,
      String template,
      String createdAt) {
    var ipv4 = instance.state() instanceof ContainerState.Running running ? running.ipv4() : null;
    return new Row(name, instance.name(), state, template, createdAt, ipv4);
  }

  private static String word(ContainerState state) {
    return switch (state) {
      case ContainerState.Running ignored -> "running";
      case ContainerState.Stopped ignored -> "stopped";
      case ContainerState.Frozen ignored -> "frozen";
      case ContainerState.NotCreated ignored -> "not_created";
      case ContainerState.Error ignored -> "error";
    };
  }

  private static String fromIncus(String createdAt) {
    return createdAt == null ? null : DateTimeUtils.format(Instant.parse(createdAt));
  }

  private static String fromMillis(String createdMs) {
    return MILLIS.matcher(createdMs).matches()
        ? DateTimeUtils.format(DateTimeUtils.ofMillis(Long.parseLong(createdMs)))
        : null;
  }

  private static long millis(long nanos) {
    return nanos / NANOS_PER_MILLI;
  }
}
