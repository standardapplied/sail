/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.Mappable;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.store.RunStore;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
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
  private static final String COUNT = "sail-count ";

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
   * Fetches the repository at {@code $1} as the dev user once its remote's host resolves, asked of
   * the resolver itself ({@code getent}) for up to 10 s, never read from a failed fetch's words: a
   * clone's network answers before its resolver does, by a fraction of a second and by more when
   * several clones start at once. Each ask again adds one to {@code retries}. A remote with no host
   * (a path, {@code file://}) is fetched at once; a failed fetch is said in git's own words.
   */
  static final String FETCH =
      """
      retries=0
      fetch() {
        local url host until=$((SECONDS + 10))
        url=$(as_dev git -C "$1" remote get-url origin)
        host=
        case $url in *://*) host=${url#*://}; host=${host#*@}; host=${host%%[:/]*} ;; esac
        if [ -n "$host" ]; then
          until getent hosts "$host" >/dev/null; do
            if [ "$SECONDS" -ge "$until" ]; then
              echo "$host did not resolve within 10 s, so its repo could not be fetched" >&2
              return 1
            fi
            retries=$((retries + 1))
            sleep 0.1
          done
        fi
        as_dev git -C "$1" fetch --quiet origin
      }
      """;

  /**
   * What makes a fresh clone a workspace of its own, run as root with the project ({@code $1}), the
   * workspace ({@code $2}), the branch its repositories go on ({@code $3}, empty for none) and each
   * repository's path and base branch after them, none of them ever part of the text: a machine-id
   * of its own, the project's name as its hostname (which the in-container helpers read the project
   * from; Incus rewrote it to the instance's name on the copy), SSH host keys of its own made under
   * that name, every repository fetched and switched to the branch (the remote's when it has one,
   * else a new one from the base) or, with no branch, left detached at the fetched base, sshd
   * restarted onto the new keys once systemd answers (it does not yet when {@code incus start}
   * returns, and it boots while the repositories are fetched), and the bus. It prints {@code
   * sail-step <step> <epoch ms>} as each step ends, which the create reads as the steps' times.
   */
  /**
   * The clone's own SSH host keys, made beside the template's and renamed over them, so the sshd
   * systemd may be starting at that moment never finds itself with none: removing them first and
   * then making new ones failed {@code ssh.service}'s start whenever the two met.
   */
  static final String HOST_KEYS =
      """
      keys=$(mktemp -d /etc/ssh/sail-keys.XXXXXX)
      mkdir -p "$keys/etc/ssh"
      ssh-keygen -A -f "$keys" >/dev/null
      for key in /etc/ssh/ssh_host_*; do [ -e "$keys/etc/ssh/${key##*/}" ] || rm -f "$key"; done
      mv -f "$keys"/etc/ssh/ssh_host_* /etc/ssh/
      rm -rf "$keys"
      """;

  static final String PREPARE =
      """
      set -eu
      project=$1 workspace=$2 branch=$3
      shift 3
      step() { printf 'sail-step %s %s\\n' "$1" "$(date +%s%3N)"; }
      step start
      rm -f /etc/machine-id
      systemd-machine-id-setup >/dev/null 2>&1
      if [ -e /var/lib/dbus/machine-id ]; then ln -sf /etc/machine-id /var/lib/dbus/machine-id; fi
      printf '%s\\n' "$project" > /etc/hostname
      hostname "$project"
      sed -i "s/^\\(127\\.0\\.1\\.1[[:space:]]*\\).*/\\1$project/" /etc/hosts
      """
          + HOST_KEYS
          + """
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
            if [ -z "$base" ]; then
              base=$(as_dev git -C "$repo" symbolic-ref --short refs/remotes/origin/HEAD)
              base=${base#origin/}
            fi
            if [ -z "$branch" ]; then
              as_dev git -C "$repo" switch --quiet --detach "origin/$base"
            elif as_dev git -C "$repo" show-ref --verify --quiet "refs/remotes/origin/$branch"; then
              as_dev git -C "$repo" switch --quiet -C "$branch" "origin/$branch"
            else
              as_dev git -C "$repo" switch --quiet --no-track -c "$branch" "origin/$base"
            fi
          done
          step repos
          printf 'sail-count fetch_retries %s\\n' "$retries"
          for _ in $(seq 1 300); do systemctl show --property=Version >/dev/null 2>&1 && break; sleep 0.1; done
          for _ in $(seq 1 300); do
            state=$(systemctl show --property=ActiveState --value ssh.service)
            case $state in activating|deactivating|reloading) sleep 0.1 ;; *) break ;; esac
          done
          case $state in activating|deactivating|reloading)
            echo "ssh.service was still $state after 30 s of waiting for it to finish starting" >&2
            exit 1 ;;
          esac
          if ! systemctl try-restart ssh.service; then
            printf 'ssh.service: %s; %s\\n' "$(systemctl show --property=Result --value ssh.service)" \\
              "$(journalctl --unit ssh.service --lines 3 --output cat --no-pager 2>&1 | paste -s -d ';' -)" >&2
            exit 1
          fi
          step sshd
          """
          + AWAIT_BUS
          + """
          step bus
          """;

  /**
   * What each phase took, in milliseconds, measured on {@link System#nanoTime}: the clone ({@code
   * null} for a start), counted from when the create asked for the runner's create lock so a wait
   * behind another create is in it, the start, the readiness that follows it, and the whole; and
   * each step of the readiness by name, in the order they ran.
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
   * workspace), when it was created, its IPv4 address, the spec whose runs live in it (none for a
   * person's) and the person it belongs to (none for the default workspace).
   */
  public record Row(
      String name,
      String instance,
      String state,
      String template,
      String createdAt,
      String ipv4,
      String spec,
      String owner)
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
      map.put("spec", spec);
      map.put("owner", owner);
      return map;
    }
  }

  /**
   * What a spec's run would enter, read before anything of the run is reserved: the spec's place,
   * the template version a new workspace of it clones, the workspace when there is one (whole, or
   * left by a create that did not finish), whether the template is older than the definition, the
   * space of the pool it clones onto, when Incus says, and what is said of that pool when it is a
   * degraded mirror.
   */
  public record SpecCheck(
      RunPlace.SpecWorkspace place,
      WorkspaceTemplate.Template template,
      Optional<BoxInstances.Instance> found,
      boolean stale,
      Optional<IncusStorage.Space> space,
      Optional<String> degraded) {

    /** Whether the spec's workspace is there and whole, so a run reuses it. */
    public boolean whole() {
      return found.map(i -> ((BoxInstances.Workspace) i.kind()).ready()).orElse(false);
    }

    /** Whether the spec's workspace is whole and its processes run. */
    public boolean running() {
      return whole() && found.get().state() instanceof ContainerState.Running;
    }
  }

  /** How a spec's run entered its workspace. */
  public sealed interface Entered {
    /** The instance the run goes on in. */
    String instance();
  }

  /** The spec's whole workspace, as it was, {@code started} when it had to be. */
  public record Reused(String instance, boolean started) implements Entered {}

  /** A workspace made for the spec, and what making it took. */
  public record Made(Created created) implements Entered {
    @Override
    public String instance() {
      return created.instance();
    }
  }

  /**
   * The box made no workspace because another create held its create lock, or a build its
   * template's lock, past {@link #CREATE_WAIT}: the box says when it is busy rather than queueing
   * without end (R6), in the words its caller is given.
   */
  public static final class Busy extends IllegalStateException {
    Busy(String said) {
      super(said);
    }

    static Busy creating() {
      return new Busy("This runner is making another workspace, which takes about a minute.");
    }

    static Busy changing(String project, String name) {
      return new Busy(
          "Workspace "
              + name
              + " of "
              + project
              + " is being stopped or deleted on this runner, and nothing enters it until that is"
              + " done.");
    }

    static Busy unread(String instance) {
      return new Busy(
          "Incus lists instance "
              + instance
              + " without its keys, as it does while another change holds it, so whose it is"
              + " cannot be told yet. If it stays so, sudo incus info "
              + instance
              + " says why.");
    }

    static Busy building(String project) {
      return new Busy(
          project + "'s template is being built, and no workspace is cloned from it until it is.");
    }
  }

  /** How long a create waits for another to finish before the box is {@link Busy}. */
  public static final Duration CREATE_WAIT = Duration.ofSeconds(60);

  /** The box-wide lock every create holds around its clone, start and prepare (D7). */
  static final String CREATE_LOCK = "workspace-create.lock";

  /** A project's workspaces, its default one first. */
  public record Listing(String project, List<Row> workspaces) {}

  private final ShellExec observer;
  private final ShellExec shell;
  private final LongSupplier nanos;
  private final Path locks;
  private final LongSupplier memoryMb;
  private final Predicate<String> asks;
  private final BoxInstances box;
  private final ContainerManager containers;

  /**
   * @param observer reads Incus, always for real
   * @param shell makes every change, narrating it instead in a dry run
   * @param nanos the clock phases are measured on, {@code System::nanoTime} outside tests
   * @param locks the box's lock directory
   * @param memoryMb the box's memory in megabytes, which bounds its spec workspaces (D8)
   * @param asks whether a repo's smart-HTTP refs URL asks for credentials ({@link
   *     ShellExec#asksForCredentials}), which an owner who keeps no token for its host cannot fetch
   */
  public Workspaces(
      ShellExec observer,
      ShellExec shell,
      LongSupplier nanos,
      Path locks,
      LongSupplier memoryMb,
      Predicate<String> asks) {
    this.observer = Objects.requireNonNull(observer, "observer");
    this.shell = Objects.requireNonNull(shell, "shell");
    this.nanos = Objects.requireNonNull(nanos, "nanos");
    this.locks = Objects.requireNonNull(locks, "locks");
    this.memoryMb = Objects.requireNonNull(memoryMb, "memoryMb");
    this.asks = Objects.requireNonNull(asks, "asks");
    this.box = new BoxInstances(observer);
    this.containers = new ContainerManager(shell);
  }

  /**
   * This box's workspaces over {@code shell}, read through its observer, on the real clock and
   * under the box's lock directory: what every door of the box uses.
   */
  public static Workspaces onThisBox(ShellExec shell) {
    return new Workspaces(
        shell.observer(),
        shell,
        System::nanoTime,
        SailPaths.locksDir(),
        memoryOf(new HostDetector()),
        shell::asksForCredentials);
  }

  public void requireOwner(WorkspaceOwner owner, ProjectDefinition config) {
    owner.require(config, asks);
  }

  /** The memory {@code host} reads, in megabytes; unchecked when it cannot be read. */
  public static LongSupplier memoryOf(HostDetector host) {
    return () -> {
      try {
        return host.detect().memoryMb();
      } catch (IOException e) {
        throw new UncheckedIOException("Could not read this box's memory: " + e.getMessage(), e);
      }
    };
  }

  /** This box's memory in megabytes, as its host says it. */
  public long memoryMb() {
    return memoryMb.getAsLong();
  }

  /**
   * Creates workspace {@code name} of {@code config}'s project for {@code handle}: a clone of its
   * template's current version, built first when there is none or it is stale, then started, given
   * its owner's credentials, made its own with every repo on a branch of its name, and marked
   * whole. One create of a workspace runs at a time, under its lock: of two of one name, the second
   * finds the first's workspace and is refused. An instance a create of it did not finish is
   * deleted and made again.
   */
  public Created create(
      ProjectDefinition config,
      String name,
      WorkspaceTemplate.Source source,
      Path files,
      String handle,
      Function<String, WorkspaceOwner> owners)
      throws Exception {
    var project = config.name();
    var instance = requireNewName(project, name);
    requireOwner(owners.apply(handle), config);
    var zfs = WorkspaceTemplate.requireZfs(observer);
    var template = WorkspaceTemplate.requireName(project, source);
    try (var held = nameLock(instance)) {
      var unfinished = requireUnclaimed(project, name, instance, source.held(), box.all());
      var built =
          new WorkspaceTemplate(observer, shell, locks).ensure(config, source, template, zfs);
      return make(
          config,
          name,
          instance,
          built,
          List.of(),
          new Owning(handle, owners),
          name,
          unfinished,
          files,
          whole -> {});
    }
  }

  /**
   * What a run of {@code place}'s spec would enter, read and refused before anything is reserved
   * (R3): a box with no ZFS pool is refused naming the command that adds one, a project with no
   * current template naming the command that builds it, a pool ZFS says is neither online nor
   * degraded naming {@code zpool status}, and a name another workspace holds naming that workspace.
   * The template is never built here: a stale one is cloned as it is, and said to be stale; one a
   * build holds is waited for (W7).
   */
  public SpecCheck checkSpec(RunPlace.SpecWorkspace place, String inputs, Predicate<String> held)
      throws IOException, InterruptedException, TimeoutException {
    var zfs = WorkspaceTemplate.requireZfs(observer);
    var template =
        new WorkspaceTemplate(observer, shell, locks)
            .current(place.project(), zfs, nanos)
            .orElseThrow(() -> noTemplate(place.project()));
    var degraded = WorkspaceTemplate.usable(observer, template.pool());
    Optional<BoxInstances.Instance> found;
    try (var entry = entryLock(place.project(), place.workspace())) {
      found = requireTheSpecs(place, held, box.all());
    }
    var space = new IncusStorage(observer).find(template.pool()).flatMap(IncusStorage.Pool::space);
    return new SpecCheck(
        place, template, found, !inputs.equals(template.inputs()), space, degraded);
  }

  /**
   * Puts a spec's run into the workspace {@code check} found, or makes it, for {@code owner}, the
   * person the run acts for: a whole one is started when stopped and kept as it is, tree and all,
   * losing only a kept mark ({@value BoxInstances#KEPT_KEY}), so a spec done again is decided
   * again; otherwise a clone of the checked template carrying the spec and the owner is made, with
   * every repo left detached at its fetched base, under the box's create lock. Either way the
   * workspace is {@code handle}'s, and holds their credentials alone, before anything of the run is
   * staged (R12, T2, T3), and {@code room}, given the other whole spec workspaces, is asked before
   * a clone or a start. A create that cannot have the create lock within {@link #CREATE_WAIT} is
   * {@link Busy}.
   */
  public Entered enterSpec(
      SpecCheck check,
      ProjectDefinition config,
      String handle,
      Function<String, WorkspaceOwner> owners,
      Path files,
      Consumer<Set<String>> room)
      throws Exception {
    var place = check.place();
    try (var held = entryLock(place.project(), place.workspace())) {
      var found = requireTheSpecs(place, name -> false, box.all());
      if (found.map(i -> ((BoxInstances.Workspace) i.kind()).ready()).orElse(false)) {
        if (!(found.get().state() instanceof ContainerState.Running)) {
          var others = wholeSpecs();
          others.remove(place.project() + "/" + place.specId());
          room.accept(others);
        }
        return reuse(found.get(), config, new Owning(handle, owners));
      }
      return new Made(
          make(
              config,
              place.workspace(),
              place.instance(),
              check.template(),
              List.of(BoxInstances.SPEC_KEY + "=" + place.specId()),
              new Owning(handle, owners),
              "",
              found.isPresent(),
              files,
              room));
    }
  }

  /**
   * The spec workspaces this box holds whole, each as {@code <project>/<spec>}: what a create made
   * under the create lock counts its room against, so two that each found room before their runs
   * were reserved never both make one past the cap.
   */
  private Set<String> wholeSpecs() throws IOException, InterruptedException, TimeoutException {
    var specs = new HashSet<String>();
    for (var instance : box.all()) {
      if (instance.kind() instanceof BoxInstances.Workspace workspace && workspace.ready()) {
        instance
            .key(BoxInstances.SPEC_KEY)
            .ifPresent(spec -> specs.add(workspace.project() + "/" + spec));
      }
    }
    return specs;
  }

  /**
   * An attach's door: spec {@code place}'s whole workspace, started when stopped and given its
   * recorded owner's credentials ({@code owners}) as any start is, under the entry's lock; nothing
   * made, checked or cleared, and none refused naming the dispatch that makes one.
   */
  public void startExisting(
      RunPlace.SpecWorkspace place,
      ProjectDefinition config,
      Function<String, WorkspaceOwner> owners)
      throws Exception {
    try (var held = entryLock(place.project(), place.workspace())) {
      var found =
          requireTheSpecs(place, name -> false, box.all())
              .filter(i -> ((BoxInstances.Workspace) i.kind()).ready())
              .orElseThrow(
                  () ->
                      Remedied.state(
                          "Spec "
                              + place.specId()
                              + " has no workspace of "
                              + place.project()
                              + " on this runner (a done spec's is deleted), and an attach makes"
                              + " none: a run of the spec makes it.",
                          Remedy.of(
                              "Dispatch one, which reopens a done spec, with: ",
                              Remedy.sail(
                                  "spec dispatch",
                                  "-p",
                                  place.project(),
                                  "--spec",
                                  place.specId(),
                                  "--restart"))));
      startGiving(found, config, owners);
    }
  }

  /**
   * A terminal's door to workspace {@code name} of {@code project}: its own instance, found by name
   * and keys under the entry's lock, started when stopped and given its recorded owner's
   * credentials, as an attach is; one the project does not have, or that is not whole, is refused
   * as {@link #start} refuses it. Nothing is made.
   */
  public void startExisting(
      String project,
      String name,
      ProjectDefinition config,
      Function<String, WorkspaceOwner> owners)
      throws Exception {
    try (var held = entryLock(project, name)) {
      startGiving(whole(project, name, "project shell", false), config, owners);
    }
  }

  /**
   * The person workspace {@code found} is, by handle: its labelled owner while the id its label
   * stores (none for an owner whose id this box does not know) is the id of that handle's active
   * person ({@code owners}); none for one made by hand, taken back, whose person is gone or
   * disabled, or whose handle is now another person's. The one read of the label every door decides
   * by.
   */
  public static Optional<String> ownerOf(
      BoxInstances.Instance found, Function<String, WorkspaceOwner> owners) {
    return found
        .key(BoxInstances.OWNER_KEY)
        .filter(Strings::isNotBlank)
        .filter(handle -> isTheirs(found, owners, handle));
  }

  private static boolean isTheirs(
      BoxInstances.Instance found, Function<String, WorkspaceOwner> owners, String handle) {
    try {
      return labelledFor(found, owners.apply(handle));
    } catch (IllegalStateException notActiveHere) {
      return false;
    }
  }

  /**
   * Makes workspace {@code name} of {@code config}'s project for {@code handle}, as {@link #create}
   * does but from the project's current template as it is: none is refused naming its build, and
   * one older than the definition is cloned as a dispatch clones it. What an API door makes, which
   * builds nothing. {@code held} names the projects whose containers no workspace may take.
   */
  public Created createFromCurrent(
      ProjectDefinition config,
      String name,
      Path files,
      String handle,
      Function<String, WorkspaceOwner> owners,
      Predicate<String> held)
      throws Exception {
    var project = config.name();
    var instance = requireNewName(project, name);
    requireOwner(owners.apply(handle), config);
    var zfs = WorkspaceTemplate.requireZfs(observer);
    var template =
        new WorkspaceTemplate(observer, shell, locks)
            .current(project, zfs, nanos)
            .orElseThrow(() -> noTemplate(project));
    try (var lock = nameLock(instance)) {
      var unfinished = requireUnclaimed(project, name, instance, held, box.all());
      return make(
          config,
          name,
          instance,
          template,
          List.of(),
          new Owning(handle, owners),
          name,
          unfinished,
          files,
          whole -> {});
    }
  }

  /**
   * What the instance of workspace {@code name} of {@code project} is, and whose, when it is one.
   */
  public Optional<BoxInstances.Instance> find(String project, String name)
      throws IOException, InterruptedException, TimeoutException {
    return box.lookup(project + "-" + name, Duration.ofSeconds(BoxInstances.LOOKUP_SECONDS))
        .filter(
            instance ->
                instance.kind() instanceof BoxInstances.Workspace(var p, var w, var ready)
                    && p.equals(project)
                    && w.equals(name));
  }

  private boolean startIfStopped(BoxInstances.Instance found) throws Exception {
    if (found.state() instanceof ContainerState.Running) {
      return false;
    }
    containers.start(found.name());
    awaitReady(found.name());
    return true;
  }

  /**
   * Starts {@code found} when it is stopped, then {@link #giveOrStop}. Answers whether it started
   * it. The caller holds the workspace's lock.
   */
  private boolean startGiving(
      BoxInstances.Instance found,
      ProjectDefinition config,
      Function<String, WorkspaceOwner> owners)
      throws Exception {
    if (!startIfStopped(found)) {
      return false;
    }
    giveOrStop(found, config, owners);
    return true;
  }

  /**
   * What every start of a workspace does once it runs, the one place each start passes through: its
   * labelled owner's credentials given again (a token change while it was stopped did not reach
   * it), after what a removal could not do while it was stopped. One whose labelled handle is now
   * another person (its stored id is not theirs) is stripped and made nobody's; one whose labelled
   * person is not active here is stripped and refused. Anything that fails stops it again before
   * the failure leaves, so a running workspace with an owner always holds their credentials and its
   * next start strips and gives from the beginning. The caller holds the workspace's lock.
   */
  private void giveOrStop(
      BoxInstances.Instance found,
      ProjectDefinition config,
      Function<String, WorkspaceOwner> owners)
      throws Exception {
    var owner = found.key(BoxInstances.OWNER_KEY).filter(handle -> !handle.isBlank());
    if (owner.isEmpty()) {
      return;
    }
    try {
      WorkspaceOwner holder;
      try {
        holder = owners.apply(owner.get());
      } catch (IllegalStateException notActiveHere) {
        WorkspaceCredentials.strip(shell, found.name());
        throw notActiveHere;
      }
      if (labelledFor(found, holder)) {
        giveAll(found.name(), config, holder);
      } else {
        WorkspaceCredentials.strip(shell, found.name());
        label(found.name(), "");
      }
    } catch (IOException given) {
      var stopped =
          new IOException(
              "Workspace "
                  + found.name()
                  + " could not be given its owner's credentials, so it is stopped again; its next"
                  + " start strips it and gives them from the beginning: "
                  + given.getMessage(),
              given);
      stopAgain(found.name(), stopped);
      throw stopped;
    } catch (RuntimeException | TimeoutException | InterruptedException refused) {
      stopAgain(found.name(), refused);
      throw refused;
    }
  }

  private void stopAgain(String instance, Exception failure) {
    try {
      containers.stop(instance);
    } catch (Exception stopping) {
      if (stopping instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      failure.addSuppressed(stopping);
    }
  }

  /** Read afresh at each give: a token or key revoked since the check must not be written. */
  private record Owning(String handle, Function<String, WorkspaceOwner> owners) {
    WorkspaceOwner now(Workspaces workspaces, ProjectDefinition config) {
      var owner = owners.apply(handle);
      workspaces.requireOwner(owner, config);
      return owner;
    }
  }

  /**
   * The label is cleared before the give and written after it, and a give cut short is stripped
   * again: a re-own that fails is nobody's and holds nothing of anyone's. A kept mark is cleared.
   */
  private Reused reuse(BoxInstances.Instance found, ProjectDefinition config, Owning owning)
      throws Exception {
    var labelled = found.key(BoxInstances.OWNER_KEY).orElse("");
    var labelledId = found.key(BoxInstances.OWNER_ID_KEY).filter(Strings::isNotBlank);
    var another =
        !owning.handle().equals(labelled)
            || labelledId.isPresent()
                && !labelledId.get().equals(owning.owners().apply(owning.handle()).id());
    boolean started;
    if (another) {
      started = startIfStopped(found);
      if (!labelled.isBlank()) {
        label(found.name(), "");
      }
      try {
        var owner = owning.now(this, config);
        giveAll(found.name(), config, owner);
        label(found.name(), owner.handle(), owner.id());
      } catch (Exception failure) {
        stripAfter(found.name(), failure);
        if (started) {
          stopAgain(found.name(), failure);
        }
        throw failure;
      }
    } else {
      started = startGiving(found, config, owning.owners());
    }
    if (found.key(BoxInstances.KEPT_KEY).isPresent()) {
      var cleared =
          new ContainerConfig(shell, locks).set(found.name(), List.of(BoxInstances.KEPT_KEY + "="));
      if (!cleared.ok()) {
        throw new IOException(
            "Workspace "
                + found.name()
                + " keeps its kept mark, so no run goes in it: "
                + cleared.reason());
      }
    }
    return new Reused(found.name(), started);
  }

  private void stripAfter(String instance, Exception failure) {
    try {
      WorkspaceCredentials.strip(shell, instance);
    } catch (IOException | TimeoutException | InterruptedException stripping) {
      if (stripping instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      failure.addSuppressed(stripping);
    }
  }

  private void label(String instance, String handle)
      throws IOException, InterruptedException, TimeoutException {
    label(instance, handle, null);
  }

  private void label(String instance, String handle, String id)
      throws IOException, InterruptedException, TimeoutException {
    var owned =
        new ContainerConfig(shell, locks)
            .set(
                instance,
                List.of(
                    BoxInstances.OWNER_KEY + "=" + handle,
                    BoxInstances.OWNER_ID_KEY + "=" + Objects.requireNonNullElse(id, "")));
    if (!owned.ok()) {
      throw new IOException(
          "Workspace "
              + instance
              + " could not be made "
              + (handle.isBlank() ? "nobody's" : handle + "'s")
              + ", so no run goes in it: "
              + owned.reason());
    }
  }

  /**
   * Running ones only, each under its workspace's lock and renewed in place, never stripped: a
   * stopped one takes them at its next start. Answers those not reached.
   */
  public List<String> refresh(WorkspaceOwner owner, Function<String, ProjectDefinition> definitions)
      throws IOException, InterruptedException, TimeoutException {
    var unreached = new ArrayList<String>();
    for (var instance : box.wholeWorkspacesOf(owner.handle())) {
      if (!(instance.state() instanceof ContainerState.Running)) {
        continue;
      }
      try (var held = nameLock(instance.name())) {
        var project = ((BoxInstances.Workspace) instance.kind()).project();
        WorkspaceCredentials.renew(shell, instance.name(), definitions.apply(project), owner);
      } catch (IOException | TimeoutException | RuntimeException failure) {
        unreached.add(instance.name() + ": " + Remedied.reasonOf(failure).strip());
      }
    }
    return List.copyOf(unreached);
  }

  private void giveAll(String instance, ProjectDefinition config, WorkspaceOwner owner)
      throws IOException, InterruptedException, TimeoutException {
    WorkspaceCredentials.give(shell, instance, config, owner);
    WorkspaceCredentials.logForgeIn(shell, instance, config, owner);
  }

  /**
   * A workspace whose owner {@code held} says is not a person here, by its stored owner id when it
   * has one, else by handle, is stripped in the container (started for it when stopped, and stopped
   * again) and made nobody's, when {@code due} says of its name that it is time to try. Clears the
   * owner too, so a person later given the handle is re-given theirs at their next entry. Answers
   * those not reached.
   */
  public List<String> disown(BiPredicate<String, String> held, Predicate<String> due)
      throws IOException, InterruptedException, TimeoutException {
    var unreached = new ArrayList<String>();
    for (var instance : box.all()) {
      var owner = instance.key(BoxInstances.OWNER_KEY).orElse("");
      var id = instance.key(BoxInstances.OWNER_ID_KEY).filter(Strings::isNotBlank).orElse(null);
      if (!(instance.kind() instanceof BoxInstances.Workspace)
          || owner.isBlank()
          || held.test(owner, id)
          || !due.test(instance.name())) {
        continue;
      }
      try (var locked = nameLock(instance.name())) {
        var stopped = !(instance.state() instanceof ContainerState.Running);
        if (stopped) {
          containers.start(instance.name());
        }
        try {
          WorkspaceCredentials.strip(shell, instance.name());
        } finally {
          if (stopped) {
            containers.stop(instance.name());
          }
        }
        label(instance.name(), "");
      } catch (IOException | TimeoutException failure) {
        unreached.add(instance.name() + ": " + Remedied.reasonOf(failure).strip());
      }
    }
    return List.copyOf(unreached);
  }

  /**
   * The lock an entry, a start, a token change or a disown of {@code instance} holds, so none
   * interleaves another's strip, give or label; none in a dry run, which makes nothing.
   */
  private FileMutex nameLock(String instance) throws IOException {
    return shell.isDryRun() ? null : FileMutex.acquire(nameLockFile(instance));
  }

  /**
   * The lock a create or a run's entry of {@code instance} holds, when nothing holds it now: what a
   * change of the workspace that no run asked for holds while it decides and acts, so a run that
   * enters it meanwhile waits and then finds what the change left. Empty while another holds it.
   */
  public Optional<FileMutex> holdIfFree(String instance) throws IOException, InterruptedException {
    return FileMutex.acquireUntil(nameLockFile(instance), 0, () -> 0);
  }

  /**
   * The lock a run's entry of {@code place}'s workspace holds, waited for while another change of
   * it, the sweep's stop or delete, holds it, no longer than {@link #CREATE_WAIT} on the create
   * lock's clock: past that the box is {@link Busy}, never a stranger's. None in a dry run.
   */
  private FileMutex entryLock(String project, String name)
      throws IOException, InterruptedException {
    var instance = project + "-" + name;
    if (shell.isDryRun()) {
      return null;
    }
    var free = holdIfFree(instance);
    if (free.isPresent()) {
      return free.get();
    }
    return FileMutex.acquireUntil(
            nameLockFile(instance), nanos.getAsLong() + CREATE_WAIT.toNanos(), nanos)
        .orElseThrow(() -> Busy.changing(project, name));
  }

  private Path nameLockFile(String instance) {
    return locks.resolve(instance + ".workspace.lock");
  }

  /**
   * Makes {@code instance}, workspace {@code name}: under the box's create lock, which it waits for
   * no longer than {@link #CREATE_WAIT}, the clone of {@code built} with the workspace's keys,
   * {@code keys} and the owner's added, its start, and its prepare onto {@code branch} (empty for
   * none). One a create did not finish ({@code unfinished}) is deleted first. Under the lock,
   * before the clone, {@code room} is given the spec workspaces the box holds whole and refuses one
   * more when the box has no room for it.
   */
  private Created make(
      ProjectDefinition config,
      String name,
      String instance,
      WorkspaceTemplate.Template built,
      List<String> keys,
      Owning owning,
      String branch,
      boolean unfinished,
      Path files,
      Consumer<Set<String>> room)
      throws Exception {
    var project = config.name();
    if (unfinished) {
      containers.forceDelete(instance);
    }
    var started = nanos.getAsLong();
    try (var held = createLock(started)) {
      room.accept(wholeSpecs());
      return made(config, name, instance, built, keys, owning, branch, files, started);
    }
  }

  private FileMutex createLock(long started) throws IOException, InterruptedException {
    if (shell.isDryRun()) {
      return null;
    }
    return FileMutex.acquireUntil(
            locks.resolve(CREATE_LOCK), started + CREATE_WAIT.toNanos(), nanos)
        .orElseThrow(Busy::creating);
  }

  private Created made(
      ProjectDefinition config,
      String name,
      String instance,
      WorkspaceTemplate.Template built,
      List<String> keys,
      Owning owning,
      String branch,
      Path files,
      long started)
      throws Exception {
    var project = config.name();
    var copyKeys = new ArrayList<>(keys);
    copyKeys.add(BoxInstances.OWNER_KEY + "=" + owning.handle());
    clone(project, name, instance, built, copyKeys);
    try {
      var cloned = nanos.getAsLong();
      containers.start(instance);
      var running = nanos.getAsLong();
      var owner = owning.now(this, config);
      WorkspaceCredentials.give(shell, instance, config, owner);
      if (owner.id() != null) {
        label(instance, owner.handle(), owner.id());
      }
      var steps = prepare(config, name, branch, instance, files);
      WorkspaceCredentials.logForgeIn(shell, instance, config, owner);
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

  /** Who a workspace whose label stores an owner id no active person holds is shown as. */
  public static final String REMOVED_OWNER = "removed";

  /**
   * Project {@code project}'s workspaces: its default one, then every workspace, by name. A box
   * with no ZFS pool has only the default one, which is answered as on any other box. A workspace's
   * owner is shown by the id its label stores: one {@code activeId} does not hold is shown as
   * {@link #REMOVED_OWNER}, never under the handle someone else may hold now; a label with a handle
   * alone is shown by it.
   */
  public Listing list(String project, Predicate<String> activeId)
      throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(project);
    var instances = box.all();
    var rows = new ArrayList<Row>();
    rows.add(
        instances.stream()
            .filter(i -> i.name().equals(project) && i.plain())
            .findFirst()
            .map(i -> row(DEFAULT, i, word(i.state()), null, fromIncus(i.createdAt()), activeId))
            .orElse(
                new Row(
                    DEFAULT,
                    project,
                    word(new ContainerState.NotCreated()),
                    null,
                    null,
                    null,
                    null,
                    null)));
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
                  i.key(CREATED_KEY).map(Workspaces::fromMillis).orElse(null),
                  activeId);
            })
        .forEach(rows::add);
    return new Listing(project, List.copyOf(rows));
  }

  /**
   * When spec {@code place}'s workspace was made: empty when it has none, the epoch for one that
   * does not say. A session a run left in it is there only when the run ended after this.
   */
  public Optional<Instant> madeAt(RunPlace.SpecWorkspace place)
      throws IOException, InterruptedException, TimeoutException {
    return box.lookup(place.instance(), Duration.ofSeconds(BoxInstances.LOOKUP_SECONDS))
        .filter(instance -> instance.isTheSpecs(place))
        .map(
            instance ->
                instance
                    .key(CREATED_KEY)
                    .filter(MILLIS.asMatchPredicate())
                    .map(Long::parseLong)
                    .map(DateTimeUtils::ofMillis)
                    .orElse(Instant.EPOCH));
  }

  /**
   * Starts workspace {@code name}, stopped or frozen, and says what its resume took, its owner's
   * credentials given as every start gives them ({@link #giveOrStop}): a start that cannot give
   * them leaves it stopped.
   */
  public Changed start(
      ProjectDefinition config, String name, Function<String, WorkspaceOwner> owners)
      throws Exception {
    var project = config.name();
    var found = whole(project, name, "project start", true);
    if (found.state() instanceof ContainerState.Running) {
      return new Changed(project, name, found.name(), Change.ALREADY_RUNNING, null);
    }
    var started = nanos.getAsLong();
    containers.start(found.name());
    var running = nanos.getAsLong();
    awaitReady(found.name());
    try (var held = nameLock(found.name())) {
      giveOrStop(found, config, owners);
    }
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

  /**
   * Whether workspace {@code found} is still {@code holder}'s, the active person of its labelled
   * handle: its label stores no id (made before ids were stored), or theirs.
   */
  private static boolean labelledFor(BoxInstances.Instance found, WorkspaceOwner holder) {
    var id = found.key(BoxInstances.OWNER_ID_KEY).filter(Strings::isNotBlank);
    return id.isEmpty() || id.get().equals(holder.id());
  }

  /**
   * Stops workspace {@code name} when its processes run, unless a run of the spec it is lives in it
   * ({@code live} says of a spec), which is refused naming the stop that ends the run.
   */
  public Changed stop(String project, String name, Predicate<String> live) throws Exception {
    var found = existing(project, name, "project stop", true);
    requireNoLiveRun(project, name, found, live);
    if (!found.state().live()) {
      return new Changed(project, name, found.name(), Change.ALREADY_STOPPED, null);
    }
    containers.stop(found.name());
    return new Changed(project, name, found.name(), Change.STOPPED, null);
  }

  /**
   * Deletes workspace {@code name}, whole or not, and with its instance the workspace (W3), once
   * {@code confirm}, handed the workspace's instance, returns: it is asked only of a workspace that
   * exists and holds no live run ({@code live} says of a spec), so a missing one, the default one
   * or one a run of its spec lives in is refused before anything is asked.
   */
  public Changed delete(
      String project, String name, Predicate<String> live, Consumer<String> confirm)
      throws Exception {
    var found = existing(project, name, "project destroy", true);
    requireNoLiveRun(project, name, found, live);
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

  /**
   * Refuses a change to {@code found}, workspace {@code name}, when it is a spec's workspace and a
   * run of that spec is {@code live}: the run would die under it.
   */
  private static void requireNoLiveRun(
      String project, String name, BoxInstances.Instance found, Predicate<String> live) {
    var spec = found.key(BoxInstances.SPEC_KEY);
    if (spec.isPresent() && live.test(spec.get())) {
      throw Remedied.state(
          "Workspace '"
              + name
              + "' of "
              + project
              + " holds a live run of spec "
              + spec.get()
              + ", which would die with it.",
          Remedy.of(
              "Stop the run first: ",
              Remedy.sail("agent stop", RunStore.RunRow.addressed(project, null, spec.get())),
              "."));
    }
  }

  private static IllegalStateException noTemplate(String project) {
    return Remedied.state(
        project + " has no template yet:",
        Remedy.of(Remedy.sail("project template build", project), "."));
  }

  private void clone(
      String project,
      String name,
      String instance,
      WorkspaceTemplate.Template template,
      List<String> keys)
      throws IOException, InterruptedException, TimeoutException {
    var argv =
        new ArrayList<>(
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
    keys.forEach(
        key -> {
          argv.add("-c");
          argv.add(key);
        });
    var copied = shell.exec(argv);
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
      ProjectDefinition config, String name, String branch, String instance, Path files)
      throws IOException, InterruptedException, TimeoutException {
    var argv =
        new ArrayList<>(
            List.of("bash", "-c", PREPARE, "sail-workspace", config.name(), name, branch));
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
   * line ending one, the first marking the start, and each {@code sail-count <name> <n>} line a
   * count kept as it is (the fetch's retries); lines of any other shape are passed over.
   */
  static LinkedHashMap<String, Long> steps(String stdout) {
    var steps = new LinkedHashMap<String, Long>();
    Long before = null;
    for (var line : Objects.requireNonNullElse(stdout, "").lines().toList()) {
      var counted = fields(line, COUNT);
      if (counted.length == 2) {
        steps.put(counted[0], Long.parseLong(counted[1]));
        continue;
      }
      var fields = fields(line, STEP);
      if (fields.length != 2) {
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

  /** The name and number after {@code prefix} on {@code line}, or nothing when it has no such. */
  private static String[] fields(String line, String prefix) {
    var fields =
        line.startsWith(prefix) ? line.substring(prefix.length()).split(" ") : new String[0];
    return fields.length == 2 && MILLIS.matcher(fields[1]).matches() ? fields : new String[0];
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
      Predicate<String> held,
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
    if (held.test(instance)) {
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

  /**
   * Spec {@code place}'s workspace when there is one, whole or left by a create that did not
   * finish; none when its name is free. The name is the spec's only when the instance of it carries
   * the spec's own key: a person's workspace of that name, or another spec's whose cut name
   * collides, is refused naming it, and so is any other instance or project of the name (W4).
   */
  private static Optional<BoxInstances.Instance> requireTheSpecs(
      RunPlace.SpecWorkspace place, Predicate<String> held, List<BoxInstances.Instance> instances) {
    var project = place.project();
    var name = place.workspace();
    var instance = place.instance();
    var taken = instances.stream().filter(i -> i.name().equals(instance)).findFirst();
    if (taken.isPresent() && taken.get().isWorkspaceOf(place)) {
      var spec = taken.get().key(BoxInstances.SPEC_KEY);
      if (spec.isEmpty()) {
        throw Remedied.state(
            "Workspace '"
                + name
                + "' of "
                + project
                + " is a person's, not spec "
                + place.specId()
                + "'s, so no run of the spec goes in it.",
            Remedy.of(
                "Delete it if it is not wanted (",
                Remedy.sail("project workspace delete", project, name),
                ")."));
      }
      if (!spec.get().equals(place.specId())) {
        throw new IllegalStateException(
            "Workspace '"
                + name
                + "' of "
                + project
                + " is spec "
                + spec.get()
                + "'s, whose workspace name spec "
                + place.specId()
                + "'s is too, so no run of "
                + place.specId()
                + " goes in it. Give one of the two specs another id.");
      }
      if (!((BoxInstances.Workspace) taken.get().kind()).ready()
          && taken.get().key(CREATING_KEY).isEmpty()) {
        throw markTakenOff(project, name);
      }
      return taken;
    }
    if (taken.isPresent()
        && taken.get().state() instanceof ContainerState.Error
        && taken.get().config().isEmpty()) {
      throw Busy.unread(instance);
    }
    if (taken.isPresent() || held.test(instance)) {
      throw new IllegalArgumentException(
          "Spec "
              + place.specId()
              + "'s workspace of "
              + project
              + " would be the instance '"
              + instance
              + "', which is "
              + (taken.isPresent()
                  ? whatItIs(taken.get())
                  : "the container of project '" + instance + "'")
              + ", so no run of the spec goes there. Give the spec another id.");
    }
    requireUnclaimed(project, name, instance, held, instances);
    return Optional.empty();
  }

  /** What an instance a spec's workspace name collides with is, in words that name its owner. */
  private static String whatItIs(BoxInstances.Instance taken) {
    return taken.kind() instanceof BoxInstances.Workspace(var p, var w, var ready)
        ? "workspace '" + w + "' of " + p
        : "an instance Incus already has";
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
   * A verb that {@code changes} it is refused first on a box with no ZFS pool (W8), and on a pool
   * ZFS says is neither online nor degraded.
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
    if (changes) {
      WorkspaceTemplate.requireUsable(observer, carrying.getFirst().pool());
    }
    return carrying.getFirst();
  }

  private static void requireWorkspaceName(String name) {
    NameValidator.requireValidWorkspaceName(name);
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

  /** Project {@code project} has no workspace {@code name} here. */
  public static IllegalStateException absent(String project, String name) {
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
      String createdAt,
      Predicate<String> activeId) {
    var ipv4 = instance.state() instanceof ContainerState.Running running ? running.ipv4() : null;
    var owner = instance.key(BoxInstances.OWNER_KEY).filter(Strings::isNotBlank).orElse(null);
    var ownerId = instance.key(BoxInstances.OWNER_ID_KEY).filter(Strings::isNotBlank);
    return new Row(
        name,
        instance.name(),
        state,
        template,
        createdAt,
        ipv4,
        instance.key(BoxInstances.SPEC_KEY).orElse(null),
        owner != null && ownerId.isPresent() && !activeId.test(ownerId.get())
            ? REMOVED_OWNER
            : owner);
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
