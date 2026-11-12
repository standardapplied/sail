/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.Mappable;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.ProjectDefinition;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A project's workspaces on this box. The project's own container, named after it, is its default
 * workspace {@value #DEFAULT}, which nothing here writes to, copies, snapshots or restarts (W1).
 * Every other workspace is an Incus instance cloned from a snapshot of the project's template, the
 * instance {@code <project>--tmpl} the project's own provisioner builds, on the box's ZFS pool. The
 * instance is the record (W3): a workspace is an instance carrying {@value #PROJECT_KEY} and
 * {@value #WORKSPACE_KEY}, and the project and workspace are only ever read from those keys, never
 * split from the instance's name (W4).
 */
public final class Workspaces {

  /** The name the project's own container goes by among its workspaces. */
  public static final String DEFAULT = "main";

  /** The project a workspace instance belongs to. */
  public static final String PROJECT_KEY = "user.sail.project";

  /** The workspace an instance is. */
  public static final String WORKSPACE_KEY = "user.sail.workspace";

  static final String TEMPLATE_KEY = "user.sail.template";
  static final String CREATED_KEY = "user.sail.created_ms";
  static final String CURRENT_KEY = "user.sail.template.current";
  static final String INPUTS_KEY = "user.sail.template.inputs";

  private static final String TEMPLATE_SUFFIX = "--tmpl";
  private static final int MAX_INSTANCE_NAME = 63;
  private static final Pattern VERSION = Pattern.compile("v([1-9][0-9]{0,8})");
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
      for _ in $(seq 1 300); do [ -S /run/user/1000/bus ] && exit 0; sleep 0.1; done
      echo "the dev user's session bus did not come up within 30 s" >&2
      exit 1
      """;

  /**
   * What makes a fresh clone a workspace of its own, run as root with the project ({@code $1}), the
   * workspace ({@code $2}) and each repository's path and base branch after them, none of them ever
   * part of the text: a machine-id and SSH host keys of its own (a clone has the template's), the
   * project's name as its hostname (which the in-container helpers read the project from; Incus
   * rewrote it to the instance's name on the copy), every repository fetched and switched to the
   * workspace's branch (the remote's when it has one, else a new one from the base), and the bus.
   */
  static final String PREPARE =
      """
      set -eu
      project=$1 workspace=$2
      shift 2
      rm -f /etc/machine-id
      systemd-machine-id-setup >/dev/null
      if [ -e /var/lib/dbus/machine-id ]; then ln -sf /etc/machine-id /var/lib/dbus/machine-id; fi
      rm -f /etc/ssh/ssh_host_*
      ssh-keygen -A >/dev/null
      systemctl try-restart ssh.service
      printf '%s\\n' "$project" > /etc/hostname
      hostname "$project"
      sed -i "s/^\\(127\\.0\\.1\\.1[[:space:]]*\\).*/\\1$project/" /etc/hosts
      as_dev() { runuser -u dev -- env HOME=/home/dev "$@"; }
      if [ "$#" -gt 0 ]; then
        for _ in $(seq 1 300); do
          ip -4 -o addr show dev eth0 | grep -q inet && break
          sleep 0.1
        done
      fi
      while [ "$#" -ge 2 ]; do
        repo=/home/dev/workspace/$1 base=$2
        shift 2
        as_dev git -C "$repo" fetch --quiet origin
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
      """
          + AWAIT_BUS;

  /**
   * Provisions the project's template into the instance it is handed, {@code fresh} when that
   * instance does not exist yet, so no progress recorded for an earlier one is resumed.
   */
  @FunctionalInterface
  public interface Build {
    void into(ProjectProvisioner.Instance template, boolean fresh) throws Exception;
  }

  /**
   * What a project's template is built from on this box: the base profile's pool ({@code host.yaml}
   * names it), the digest of the definition and key a build provisions from ({@link
   * ProjectProvisioner#inputs}), whether the catalog holds a project by a name, and the build.
   */
  public record Source(String basePool, String inputs, Predicate<String> held, Build build) {
    public Source {
      Objects.requireNonNull(basePool, "basePool");
      Objects.requireNonNull(inputs, "inputs");
      Objects.requireNonNull(held, "held");
      Objects.requireNonNull(build, "build");
    }
  }

  /**
   * A project's template as it stands: its instance, the version workspaces clone, the inputs it
   * was built from, the pool it is on, and whether this call built it.
   */
  public record Template(
      String project, String instance, String version, String inputs, String pool, boolean built) {}

  /**
   * What each phase took, in milliseconds, measured on {@link System#nanoTime}: the clone ({@code
   * null} for a start), the start, the readiness that follows it, and the whole.
   */
  public record Timings(Long cloneMs, long startMs, long readyMs, long totalMs) {}

  /** A workspace made: its instance, the template version it was cloned from, and its times. */
  public record Created(
      String project, String workspace, String instance, Template template, Timings timings) {}

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
   * One workspace as Incus has it: its name, instance and state, the template version it was cloned
   * from (none for the default workspace), when it was created and its IPv4 address.
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
  private final ContainerManager observed;
  private final ContainerManager containers;

  /**
   * @param observer reads Incus, always for real
   * @param shell makes every change, narrating it instead in a dry run
   * @param nanos the clock phases are measured on, {@code System::nanoTime} outside tests
   */
  public Workspaces(ShellExec observer, ShellExec shell, LongSupplier nanos) {
    this.observer = Objects.requireNonNull(observer, "observer");
    this.shell = Objects.requireNonNull(shell, "shell");
    this.nanos = Objects.requireNonNull(nanos, "nanos");
    this.observed = new ContainerManager(observer);
    this.containers = new ContainerManager(shell);
  }

  /** The name of project {@code project}'s template instance. */
  public static String templateOf(String project) {
    return project + TEMPLATE_SUFFIX;
  }

  /**
   * Builds {@code config}'s template unless its current version was built from {@code source}'s
   * inputs, which leaves it as it is (W7: a version is current only once it is whole).
   */
  public Template template(ProjectDefinition config, Source source) throws Exception {
    NameValidator.requireValidProjectName(config.name());
    var pool = choose(zfsPools(), source.basePool());
    return template(config, source, pool, requireTemplateName(config.name(), source));
  }

  /**
   * Creates workspace {@code name} of {@code config}'s project: a clone of its template's current
   * version, built first when there is none or it is stale, then started and made its own.
   */
  public Created create(ProjectDefinition config, String name, Source source, Path files)
      throws Exception {
    var project = config.name();
    var instance = requireNewName(project, name);
    var zfs = zfsPools();
    var pool = choose(zfs, source.basePool());
    var template = requireTemplateName(project, source);
    requireUnclaimed(project, name, instance, source);
    var built = template(config, source, pool, template);
    requireOnZfs(zfs, built);
    var started = nanos.getAsLong();
    clone(project, name, instance, built);
    try {
      var cloned = nanos.getAsLong();
      containers.start(instance);
      var running = nanos.getAsLong();
      prepare(config, name, instance, files);
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
              millis(ready - started)));
    } catch (Exception e) {
      discard(instance, e);
      throw e;
    }
  }

  /** Project {@code project}'s workspaces: its default one, then every clone, by name. */
  public Listing list(String project) throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(project);
    zfsPools();
    var instances = observed.instances();
    var rows = new ArrayList<Row>();
    rows.add(
        instances.stream()
            .filter(i -> i.name().equals(project) && i.key(WORKSPACE_KEY).isEmpty())
            .findFirst()
            .map(i -> row(DEFAULT, i, null, fromIncus(i.createdAt())))
            .orElse(
                new Row(
                    DEFAULT, project, word(new ContainerState.NotCreated()), null, null, null)));
    instances.stream()
        .filter(i -> isWorkspaceOf(i, project))
        .sorted(Comparator.comparing(i -> i.key(WORKSPACE_KEY).orElseThrow()))
        .map(
            i ->
                row(
                    i.key(WORKSPACE_KEY).orElseThrow(),
                    i,
                    i.key(TEMPLATE_KEY).orElse(null),
                    i.key(CREATED_KEY).map(Workspaces::fromMillis).orElse(null)))
        .forEach(rows::add);
    return new Listing(project, List.copyOf(rows));
  }

  /** Starts workspace {@code name}, stopped or frozen, and says what its resume took. */
  public Changed start(String project, String name) throws Exception {
    var found = existing(project, name, "project start");
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
            null, millis(running - started), millis(ready - running), millis(ready - started)));
  }

  /** Stops workspace {@code name} when its processes run. */
  public Changed stop(String project, String name) throws Exception {
    var found = existing(project, name, "project stop");
    if (!found.state().live()) {
      return new Changed(project, name, found.name(), Change.ALREADY_STOPPED, null);
    }
    containers.stop(found.name());
    return new Changed(project, name, found.name(), Change.STOPPED, null);
  }

  /** Deletes workspace {@code name}, and with its instance the workspace (W3). */
  public Changed delete(String project, String name) throws Exception {
    var found = existing(project, name, "project destroy");
    containers.forceDelete(found.name());
    return new Changed(project, name, found.name(), Change.DELETED, null);
  }

  /** The instance of workspace {@code name}, when it runs: where a shell opens. */
  public String running(String project, String name) throws Exception {
    var found = existing(project, name, "project shell");
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

  private Template template(ProjectDefinition config, Source source, String pool, String template)
      throws Exception {
    if (shell.isDryRun()) {
      return buildIfStale(config, source, pool, template);
    }
    try (var held = ContainerConfig.hold(SailPaths.locksDir(), template)) {
      return buildIfStale(config, source, pool, template);
    }
  }

  private Template buildIfStale(
      ProjectDefinition config, Source source, String pool, String template) throws Exception {
    var project = config.name();
    var found = observed.instances().stream().filter(i -> i.name().equals(template)).findFirst();
    var versions =
        found.isPresent()
            ? new SnapshotManager(observer)
                .list(template).stream()
                    .map(SnapshotManager.SnapshotInfo::name)
                    .map(VERSION::matcher)
                    .filter(Matcher::matches)
                    .map(matcher -> Integer.parseInt(matcher.group(1)))
                    .toList()
            : List.<Integer>of();
    var at = found.map(ContainerManager.Instance::pool).orElse(pool);
    var current = found.flatMap(i -> i.key(CURRENT_KEY));
    if (current.isPresent()
        && found.get().key(INPUTS_KEY).equals(Optional.of(source.inputs()))
        && versions.stream().anyMatch(n -> current.get().equals("v" + n))) {
      return new Template(project, template, current.get(), source.inputs(), at, false);
    }
    var version = "v" + (versions.stream().mapToInt(Integer::intValue).max().orElse(0) + 1);
    if (found.isPresent() && !(found.get().state() instanceof ContainerState.Running)) {
      containers.start(template);
    }
    source
        .build()
        .into(new ProjectProvisioner.Instance(template, Optional.of(pool)), found.isEmpty());
    containers.stop(template);
    var snapshots = new SnapshotManager(shell);
    snapshots.create(template, version);
    var recorded =
        new ContainerConfig(shell, SailPaths.locksDir())
            .set(
                template, List.of(CURRENT_KEY + "=" + version, INPUTS_KEY + "=" + source.inputs()));
    if (!recorded.ok()) {
      var failure =
          new IOException(
              "Failed to record "
                  + version
                  + " as "
                  + project
                  + "'s current template, so it was removed and the version before stays current: "
                  + recorded.reason());
      try {
        snapshots.delete(template, version);
      } catch (IOException | TimeoutException removal) {
        failure.addSuppressed(removal);
      }
      throw failure;
    }
    return new Template(project, template, version, source.inputs(), at, true);
  }

  private void clone(String project, String name, String instance, Template template)
      throws IOException, InterruptedException, TimeoutException {
    var copied =
        shell.exec(
            List.of(
                "incus",
                "copy",
                template.instance() + "/" + template.version(),
                instance,
                "-c",
                PROJECT_KEY + "=" + project,
                "-c",
                WORKSPACE_KEY + "=" + name,
                "-c",
                TEMPLATE_KEY + "=" + template.version(),
                "-c",
                CREATED_KEY + "=" + DateTimeUtils.toMillis(DateTimeUtils.now())));
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

  private void prepare(ProjectDefinition config, String name, String instance, Path files)
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
    new ProjectApplier(observer, shell, DISCARD)
        .applyWorkspaceFiles(instance, files, ProjectDefinition.USER);
    ContainerSetupSweep.reconcile(shell, instance);
  }

  private void awaitReady(String instance)
      throws IOException, InterruptedException, TimeoutException {
    var ready = shell.exec(ContainerExec.asRoot(instance, List.of("bash", "-c", AWAIT_BUS)));
    if (!ready.ok()) {
      throw new IOException("Workspace '" + instance + "' did not come up: " + ready.reason());
    }
    ContainerSetupSweep.reconcile(shell, instance);
  }

  /**
   * Deletes {@code instance}, made by the create that {@code failure} ended, so none is left half
   * made.
   */
  private void discard(String instance, Exception failure) throws InterruptedException {
    try {
      containers.forceDelete(instance);
    } catch (IOException | TimeoutException removal) {
      failure.addSuppressed(removal);
    }
  }

  /**
   * The ZFS pool templates and workspaces are made on: the base profile's pool when it is ZFS, else
   * the one ZFS pool beside it. Refused before anything changes on a box with none (W8), and where
   * several sit beside a base pool that is not ZFS, since nothing says which is meant.
   */
  private static String choose(List<String> zfs, String basePool) {
    if (zfs.contains(basePool)) {
      return basePool;
    }
    if (zfs.size() == 1) {
      return zfs.getFirst();
    }
    throw Remedied.state(
        "Incus has the ZFS pools "
            + String.join(", ", zfs)
            + " and the base profile's pool '"
            + basePool
            + "' is none of them, so Sail cannot tell which one workspaces go on.",
        Remedy.of(
            "Keep one ZFS pool beside the base profile's, or put the base profile on the one to use."));
  }

  private List<String> zfsPools() throws IOException, InterruptedException, TimeoutException {
    var zfs =
        new IncusStorage(observer)
            .pools().stream()
                .filter(pool -> HostYaml.BACKEND_ZFS.equals(pool.driver()))
                .map(IncusStorage.Pool::name)
                .toList();
    if (zfs.isEmpty()) {
      throw Remedied.state(
          "This box has no ZFS pool, and a workspace is a ZFS clone of its project's template.",
          Remedy.of(
              "Add one beside the box's pool with: ",
              Remedy.sail("host init", "--storage", "zfs", "--pool", "tank", "--disk", "<device>"),
              ", or with --pool-file-size <size> in place of --disk to try ZFS in a file."));
    }
    return zfs;
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
    var instance = project + "-" + name;
    requireLength(instance, "Workspace '" + name + "' of " + project);
    if (instance.endsWith(TEMPLATE_SUFFIX)) {
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

  private static String requireTemplateName(String project, Source source) {
    var template = templateOf(project);
    requireLength(template, project + "'s template");
    if (source.held().test(template)) {
      throw new IllegalArgumentException(
          project
              + "'s template would be the instance '"
              + template
              + "', which is the name of project '"
              + template
              + "' on this box, so "
              + project
              + " has no workspaces here.");
    }
    return template;
  }

  private void requireUnclaimed(String project, String name, String instance, Source source)
      throws IOException, InterruptedException, TimeoutException {
    var taken = observed.instances().stream().filter(i -> i.name().equals(instance)).findFirst();
    if (taken.isPresent()
        && isWorkspaceOf(taken.get(), project)
        && taken.get().key(WORKSPACE_KEY).orElseThrow().equals(name)) {
      throw new IllegalStateException(
          "Workspace '"
              + name
              + "' of "
              + project
              + " exists already, as the instance '"
              + instance
              + "'.");
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
  }

  private static void requireOnZfs(List<String> zfs, Template template) {
    if (!zfs.contains(template.pool())) {
      throw Remedied.state(
          template.instance()
              + " is on pool '"
              + template.pool()
              + "', which is not ZFS, so a copy of it would be a full copy, not a clone.",
          Remedy.of(
              "Delete it so the next create builds it on ZFS: ",
              Remedy.program("incus", "delete", "--force", template.instance())));
    }
  }

  private ContainerManager.Instance existing(String project, String name, String projectVerb)
      throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(project);
    requireWorkspaceName(name);
    if (DEFAULT.equals(name)) {
      throw Remedied.argument(
          "'"
              + DEFAULT
              + "' is "
              + project
              + "'s default workspace, the project's own container, which workspace commands leave alone.",
          Remedy.of("Use: ", Remedy.sail(projectVerb, project), "."));
    }
    zfsPools();
    return observed.instances().stream()
        .filter(i -> isWorkspaceOf(i, project) && i.key(WORKSPACE_KEY).orElseThrow().equals(name))
        .findFirst()
        .orElseThrow(() -> absent(project, name));
  }

  private static boolean isWorkspaceOf(ContainerManager.Instance instance, String project) {
    return instance.key(PROJECT_KEY).equals(Optional.of(project))
        && instance.key(WORKSPACE_KEY).isPresent();
  }

  private static void requireWorkspaceName(String name) {
    if (!NameValidator.isValidProjectName(name)) {
      throw new IllegalArgumentException(
          "Invalid workspace name: '"
              + name
              + "'. Must match [a-z0-9][a-z0-9-]*, as a project name does.");
    }
  }

  private static void requireLength(String instance, String what) {
    if (instance.length() > MAX_INSTANCE_NAME) {
      throw new IllegalArgumentException(
          what
              + " would be the instance '"
              + instance
              + "', "
              + instance.length()
              + " characters, and Incus names an instance in at most "
              + MAX_INSTANCE_NAME
              + ". Use a shorter name.");
    }
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
      String name, ContainerManager.Instance instance, String template, String createdAt) {
    var ipv4 = instance.state() instanceof ContainerState.Running running ? running.ipv4() : null;
    return new Row(name, instance.name(), word(instance.state()), template, createdAt, ipv4);
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
