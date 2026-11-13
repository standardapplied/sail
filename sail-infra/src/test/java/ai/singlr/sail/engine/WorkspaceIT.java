/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.EventBus;
import ai.singlr.sail.api.LocalApiSocket;
import ai.singlr.sail.api.ProjectReader;
import ai.singlr.sail.api.SailOperations;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.api.TestControlPlane;
import ai.singlr.sail.commands.DownCommand;
import ai.singlr.sail.commands.ExecCommand;
import ai.singlr.sail.commands.ProjectDestroyCommand;
import ai.singlr.sail.commands.RestoreCommand;
import ai.singlr.sail.commands.ShellCommand;
import ai.singlr.sail.commands.SnapCommand;
import ai.singlr.sail.commands.SnapDeleteCommand;
import ai.singlr.sail.commands.SnapsPruneCommand;
import ai.singlr.sail.commands.UpCommand;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.BoxCredentialStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Workspaces on a real Incus: a dir pool the runner's Incus starts with and its base profile uses,
 * where the project's own container runs, and a ZFS pool in a file beside it, as {@code host init
 * --storage zfs --pool <name> --pool-file-size} adds one. A real template build runs the project's
 * provisioner, which reaches the internet (apt, the runtimes' archives) that no test body may, so
 * the template is sealed here as a finished build leaves it: the prepared image with the dev user
 * lingering, sail's machinery and a repository whose origin is a bare repository inside the
 * template, stopped, snapshot {@code v1}, and the record of the definition's digest. Everything
 * after that is the production lane: the clone, the per-workspace step, the list and the lifecycle.
 * The measured times are printed into the job log as a record, not checked: CI is not a runner.
 */
class WorkspaceIT extends AbstractIncusIT {

  private static final String POOL = "sail-it-ws";
  private static final String BASE_POOL = "default";
  private static final String PROJECT = "sail-it-ws";
  private static final String TEMPLATE = WorkspaceTemplate.nameOf(PROJECT);
  private static final String FIX = "fix-login";
  private static final String SECOND = "second";
  private static final String SPEC_ID = "sail-it-ws-probe";
  private static final Path CONTAINER_DIR = Path.of("/var/lib/sail/run");

  private static final String SEAL =
      """
      set -eu
      userdel -r ubuntu 2>/dev/null || true
      id -u dev >/dev/null 2>&1 || useradd -m -u 1000 -s /bin/bash dev
      for _ in $(seq 1 30); do loginctl enable-linger dev 2>/dev/null && break; sleep 1; done
      loginctl enable-linger dev
      as_dev() { runuser -u dev -- env HOME=/home/dev "$@"; }
      git init --quiet --bare -b main /srv/app.git
      chown -R dev:dev /srv/app.git
      as_dev git config --global user.email it@sail.invalid
      as_dev git config --global user.name it
      as_dev git init --quiet -b main /tmp/seed
      echo seed | as_dev tee /tmp/seed/README >/dev/null
      as_dev git -C /tmp/seed add README
      as_dev git -C /tmp/seed commit --quiet -m seed
      as_dev git -C /tmp/seed push --quiet /srv/app.git main main:second
      as_dev mkdir -p /home/dev/workspace
      as_dev git clone --quiet file:///srv/app.git /home/dev/workspace/app
      """;

  private final ContainerManager containers = new ContainerManager(shell);
  private final Workspaces workspaces =
      new Workspaces(shell, shell, System::nanoTime, SailPaths.locksDir());

  private static ProjectDefinition definition() {
    return new ProjectDefinition(
        PROJECT,
        "Workspaces on a real Incus",
        new ProjectDefinition.Resources(2, "2GB", "10GB"),
        null,
        null,
        null,
        List.of(new ProjectDefinition.Repo("https://example.invalid/app.git", "app", null)),
        null,
        null,
        null,
        null);
  }

  private static WorkspaceTemplate.Source source() {
    return new WorkspaceTemplate.Source(
        BASE_POOL,
        ProjectProvisioner.inputs(definition(), Optional.empty()),
        name -> name.equals(PROJECT),
        (into, fresh) -> {
          throw new AssertionError("the sealed template is current, so nothing is built");
        });
  }

  @BeforeEach
  void aDirBoxWithAZfsPoolBesideAndASealedTemplate() throws Exception {
    ensureIncusOrSkip();
    removeEverything();
    var made = new IncusStorage(shell).create(POOL, new PoolSource.File(10240));
    assertTrue(made.ok(), "Incus could not make a file-backed zfs pool: " + made.reason());
    launchPrepared(PROJECT);
    launchPrepared(TEMPLATE, "--storage", POOL);
    var sealed = exec(TEMPLATE, List.of("bash", "-c", SEAL));
    assertTrue(sealed.ok(), "could not seal the template: " + sealed.stderr() + sealed.stdout());
    ContainerSailSetup.ensureInstalled(shell, TEMPLATE);
    containers.stop(TEMPLATE);
    var recorded =
        shell.exec(
            List.of(
                "incus",
                "config",
                "set",
                TEMPLATE,
                "user.sail.template.of=" + PROJECT,
                "user.sail.template.current=v1",
                "user.sail.template.inputs="
                    + ProjectProvisioner.inputs(definition(), Optional.empty())));
    assertTrue(recorded.ok(), recorded.reason());
    new SnapshotManager(shell).create(TEMPLATE, "v1");
  }

  @AfterEach
  void removeEverything() {
    deleteContainerQuietly(PROJECT + "-" + FIX);
    deleteContainerQuietly(PROJECT + "-" + SECOND);
    deleteContainerQuietly(TEMPLATE);
    deleteContainerQuietly(PROJECT);
    try {
      shell.exec(List.of("incus", "storage", "delete", POOL));
    } catch (Exception ignored) {
    }
  }

  @Test
  void workspacesAreClonesOfTheTemplateEachItsOwnMachineBesideTheProjectsContainer()
      throws Exception {
    var before = bystander();
    var fix = workspaces.create(definition(), FIX, source(), null);
    var second = workspaces.create(definition(), SECOND, source(), null);
    record("create " + FIX, fix.timings());
    record("create " + SECOND, second.timings());

    assertEquals("v1", fix.template().version());
    assertEquals(false, second.template().built());
    for (var workspace : List.of(fix, second)) {
      assertEquals(
          POOL + "/containers/" + TEMPLATE + "@snapshot-v1",
          zfs("get", "-H", "-o", "value", "origin", POOL + "/containers/" + workspace.instance()),
          "a workspace is a ZFS clone of the template's snapshot");
    }
    assertEquals(
        List.of(PROJECT + " on " + BASE_POOL, TEMPLATE + " on " + POOL),
        new BoxInstances(shell)
            .all().stream()
                .filter(i -> i.name().equals(PROJECT) || i.name().equals(TEMPLATE))
                .map(i -> i.name() + " on " + i.pool())
                .sorted()
                .toList(),
        "the project's own container stays on dir; the template is on the ZFS pool");

    assertEquals(
        List.of("main running", FIX + " running", SECOND + " running"),
        workspaces.list(PROJECT).workspaces().stream()
            .map(row -> row.name() + " " + row.state())
            .toList());

    assertEquals(FIX, inWorkspace(fix, "git -C /home/dev/workspace/app branch --show-current"));
    assertEquals(
        "origin/" + SECOND,
        inWorkspace(second, "git -C /home/dev/workspace/app rev-parse --abbrev-ref @{upstream}"),
        "a branch the remote has is the one a workspace of that name takes");
    assertEquals(PROJECT, inWorkspace(fix, "hostname"));

    var identities = new ArrayList<String>();
    identities.add(read(TEMPLATE + "/etc/machine-id"));
    identities.add(read(fix.instance() + "/etc/machine-id"));
    identities.add(read(second.instance() + "/etc/machine-id"));
    var hostKeys = new ArrayList<String>();
    hostKeys.add(read(TEMPLATE + "/etc/ssh/ssh_host_ed25519_key.pub"));
    hostKeys.add(read(fix.instance() + "/etc/ssh/ssh_host_ed25519_key.pub"));
    hostKeys.add(read(second.instance() + "/etc/ssh/ssh_host_ed25519_key.pub"));
    assertEquals(3, new HashSet<>(identities).size(), "machine-ids: " + identities);
    assertEquals(3, new HashSet<>(hostKeys).size(), "host keys: " + hostKeys);
    for (var workspace : List.of(fix, second)) {
      assertTrue(
          read(workspace.instance() + "/etc/ssh/ssh_host_ed25519_key.pub")
              .endsWith(" root@" + PROJECT),
          "a workspace's host keys are made under the project's name");
      var keys = new BoxInstances(shell).named(workspace.instance()).orElseThrow();
      assertEquals(
          List.of(),
          List.of(
                  BoxInstances.TEMPLATE_OF_KEY,
                  WorkspaceTemplate.CURRENT_KEY,
                  WorkspaceTemplate.INPUTS_KEY,
                  Workspaces.CREATING_KEY)
              .stream()
              .filter(key -> keys.key(key).isPresent())
              .toList(),
          "a workspace carries none of its template's keys, nor the copy's mark");
      assertTrue(keys.key(BoxInstances.READY_KEY).isPresent());
    }

    var serve =
        "setsid python3 -m http.server 3000 >/dev/null 2>&1 < /dev/null &"
            + " for i in $(seq 1 100); do ss -lntH | grep -q ':3000 ' && exit 0; sleep 0.1; done;"
            + " exit 1";
    for (var workspace : List.of(fix, second)) {
      var served = exec(workspace.instance(), List.of("bash", "-c", serve));
      assertTrue(served.ok(), workspace.workspace() + " could not serve 3000: " + served.stderr());
    }
    for (var workspace : List.of(fix, second)) {
      assertTrue(
          exec(workspace.instance(), List.of("ss", "-lntH")).stdout().contains(":3000 "),
          "each workspace listens on 3000 in its own network");
    }
    var addresses =
        workspaces.list(PROJECT).workspaces().stream()
            .filter(row -> !row.name().equals(Workspaces.DEFAULT))
            .map(Workspaces.Row::ipv4)
            .toList();
    assertEquals(2, new HashSet<>(addresses).size(), "two clones, two addresses: " + addresses);
    assertEquals(before, bystander(), "the project's own container is untouched");
  }

  @Test
  void everyProjectVerbIsRefusedOnARealWorkspaceAndTemplateAndChangesNeither() throws Exception {
    var fix = workspaces.create(definition(), FIX, source(), null);
    var ws = fix.instance();
    var verbs =
        List.<Map.Entry<Object, List<String>>>of(
            Map.entry(new ShellCommand(), List.of(ws)),
            Map.entry(new ExecCommand(), List.of(ws, "true")),
            Map.entry(new ExecCommand(), List.of(TEMPLATE, "true")),
            Map.entry(new UpCommand(), List.of(TEMPLATE)),
            Map.entry(new DownCommand(), List.of(ws)),
            Map.entry(new SnapCommand(), List.of(TEMPLATE)),
            Map.entry(new SnapCommand(), List.of(ws)),
            Map.entry(new SnapDeleteCommand(), List.of(TEMPLATE, "v1")),
            Map.entry(new RestoreCommand(), List.of(TEMPLATE, "v1")),
            Map.entry(new SnapsPruneCommand(), List.of(TEMPLATE, "--keep", "0")),
            Map.entry(new ProjectDestroyCommand(), List.of(ws, "--dry-run")));

    for (var verb : verbs) {
      var err = new ByteArrayOutputStream();
      var original = System.err;
      System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
      int exit;
      try {
        var cli = new CommandLine(verb.getKey());
        cli.setExecutionExceptionHandler((failure, line, parsed) -> 1);
        exit = cli.execute(verb.getValue().toArray(String[]::new));
      } finally {
        System.setErr(original);
      }
      var said = err.toString(StandardCharsets.UTF_8);
      var name = verb.getValue().getFirst();
      assertEquals(1, exit, verb.getValue() + ": " + said);
      assertTrue(
          said.contains("Project " + name + " cannot take instance " + name + ": it is "),
          verb.getValue() + ": " + said);
    }

    var box = new BoxInstances(shell);
    assertEquals(new ContainerState.Stopped(), box.named(TEMPLATE).orElseThrow().state());
    assertTrue(box.named(ws).orElseThrow().state() instanceof ContainerState.Running);
    assertEquals(
        List.of("v1"),
        new SnapshotManager(shell)
            .list(TEMPLATE).stream().map(SnapshotManager.SnapshotInfo::name).toList());
    assertEquals(
        List.of(),
        new SnapshotManager(shell)
            .list(ws).stream().map(SnapshotManager.SnapshotInfo::name).toList());
  }

  @Test
  void aWorkspaceStopsAndResumesWithItsEditsAndAPauseListsAsFrozen() throws Exception {
    var fix = workspaces.create(definition(), FIX, source(), null);
    var edited = exec(fix.instance(), List.of("bash", "-c", "echo kept > /home/dev/edit"));
    assertTrue(edited.ok(), edited.stderr());

    assertEquals(Workspaces.Change.STOPPED, workspaces.stop(PROJECT, FIX).change());
    assertEquals(
        new ContainerState.Stopped(),
        new BoxInstances(shell).named(fix.instance()).orElseThrow().state());
    var resumed = workspaces.start(PROJECT, FIX);
    record("start " + FIX, resumed.timings());

    assertEquals(Workspaces.Change.STARTED, resumed.change());
    assertEquals("kept", inWorkspace(fix, "cat /home/dev/edit"));
    assertEquals(PROJECT, inWorkspace(fix, "hostname"), "the hostname survives a restart");

    assertTrue(shell.exec(List.of("incus", "pause", fix.instance())).ok());
    assertEquals(
        List.of("main running", FIX + " frozen"),
        workspaces.list(PROJECT).workspaces().stream()
            .map(row -> row.name() + " " + row.state())
            .toList());
    assertEquals(Workspaces.Change.STARTED, workspaces.start(PROJECT, FIX).change());
  }

  @Test
  void theInContainerSpecBoardOfAWorkspaceReadsItsProjectsSpecs() throws Exception {
    var socketDir = Files.createTempDirectory("sail-it-ws-socket");
    Files.setPosixFilePermissions(socketDir, PosixFilePermissions.fromString("rwxr-xr-x"));
    var dbPath = Files.createTempDirectory("sail-it-ws-db").resolve("sail.db");
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, PROJECT);
      var specs = new SpecStore(db);
      Acting.as("it", () -> specs.create(probe()));
      var fdes = new FdeStore(db);
      fdes.add("it", "IT", "it@example.dev", "admin");
      var boxes = new BoxCredentialStore(db);
      BoxCredentialFile.ensure(boxes, "it", socketDir);
      var operations =
          new SailOperations(
                  new ShellExecutor(false),
                  ProjectReader.ofCatalog(new ProjectStore(db)),
                  new EventBus(),
                  specs,
                  null,
                  new RunStore(db),
                  null,
                  SyncScheduler.disabled(),
                  fdes,
                  SessionYield.NONE)
              .useMessages(new MessageStore(db))
              .useBoxCredentials(boxes)
              .useRooms(new RoomStore(db));
      TestControlPlane.on(operations, db, socketDir, new SyncConfig(null, null, "it"));
      try (var server = new LocalApiSocket(operations, socketDir.resolve("api.sock"))) {
        server.start();
        var fix = workspaces.create(definition(), FIX, source(), null);
        new IncusDeviceManager(shell).ensureEventSocket(fix.instance(), socketDir, CONTAINER_DIR);

        var board =
            shell.exec(
                ContainerExec.asDevUser(
                    fix.instance(), List.of(SpecCliHelper.SCRIPT_PATH, "board")));

        assertTrue(board.ok(), "spec board failed: " + board.stderr() + board.stdout());
        assertTrue(
            board.stdout().contains(SPEC_ID),
            "the board lists the project's spec: " + board.stdout());
      }
    }
  }

  private static SpecStore.SpecRow probe() {
    return new SpecStore.SpecRow(
        SPEC_ID,
        PROJECT,
        "Workspace board probe",
        SpecStatus.PENDING,
        null,
        null,
        null,
        null,
        null,
        0,
        "it",
        null,
        null,
        "it",
        List.of(),
        List.of());
  }

  /** The project's own container as Incus holds it: its state, snapshots and configuration. */
  private String bystander() throws Exception {
    var state = containers.queryState(PROJECT);
    var snapshots = new SnapshotManager(shell).list(PROJECT).size();
    var config = shell.exec(List.of("incus", "config", "show", PROJECT));
    assertTrue(config.ok(), config.reason());
    return state + " " + snapshots + "\n" + config.stdout();
  }

  private String inWorkspace(Workspaces.Created workspace, String command) throws Exception {
    var result =
        shell.exec(ContainerExec.asDevUser(workspace.instance(), List.of("bash", "-c", command)));
    assertTrue(result.ok(), command + ": " + result.stderr());
    return result.stdout().strip();
  }

  private String read(String path) throws Exception {
    var pulled = shell.exec(List.of("incus", "file", "pull", path, "-"));
    assertTrue(pulled.ok(), path + ": " + pulled.stderr());
    assertNotEquals("", pulled.stdout().strip(), path);
    return pulled.stdout().strip();
  }

  private String zfs(String... args) throws Exception {
    var command = new ArrayList<>(List.of("sudo", "-n", "zfs"));
    command.addAll(List.of(args));
    var result = shell.exec(command);
    assertTrue(result.ok(), String.join(" ", command) + ": " + result.reason());
    return result.stdout().strip();
  }

  /** Prints what a create or start took into the job log: a record, not a gate. */
  private static void record(String what, Workspaces.Timings timings) {
    System.out.println(
        "workspace-timing: "
            + what
            + " clone_ms="
            + timings.cloneMs()
            + " start_ms="
            + timings.startMs()
            + " ready_ms="
            + timings.readyMs()
            + " total_ms="
            + timings.totalMs()
            + " ready_steps_ms="
            + timings.readySteps());
  }
}
