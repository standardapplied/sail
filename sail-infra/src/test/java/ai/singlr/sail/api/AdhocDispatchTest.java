/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ad-hoc launch lane: {@code startAdhoc} mints a run, reserves the whole container through the
 * same transaction as dispatch, launches on the run-scoped identity, and shares dispatch's watcher
 * and bookkeeping — so ad-hoc and dispatched agents are mutually exclusive by construction.
 */
class AdhocDispatchTest {

  private static final String HANDLE = "me";
  private static final Actor ADMIN = Actor.cliOperator(HANDLE);

  private static final String RUNNING_JSON =
      """
      [{"name": "acme", "status": "Running", "state": {}}]
      """;

  private static final String YAML =
      """
      name: acme
      ssh:
        user: dev
      repos:
        - url: https://github.com/acme/app.git
          path: app
      agent:
        type: claude-code
      """;

  @TempDir Path tempDir;

  private SpecStore specStore;
  private RunStore runStore;
  private Sqlite db;
  private final List<Event> events = new ArrayList<>();

  private DispatchOperations operations(ShellExec shell) throws IOException {
    return operations(shell, command -> 0, true);
  }

  private DispatchOperations operations(
      ShellExec shell, DispatchOperations.AgentLauncher launcher, boolean withRunStore)
      throws IOException {
    var yaml = tempDir.resolve("sail-" + System.nanoTime() + ".yaml");
    Files.writeString(yaml, YAML);
    db = Sqlite.open(tempDir.resolve("adhoc-" + System.nanoTime() + ".db"));
    new SchemaManager(db).migrate();
    specStore = new SpecStore(db);
    runStore = new RunStore(db);
    new FdeStore(db).add(HANDLE, null, null, "admin");
    return new DispatchOperations(
        shell,
        yaml.toString(),
        specStore,
        new ReviewStore(db),
        withRunStore ? runStore : null,
        new FdeStore(db),
        events::add,
        new WatcherSpawner(shell, (command, logPath) -> 4242L),
        (project, config) -> "",
        launcher,
        DispatchOperations.Listener.NONE);
  }

  private static StubShell liveAgentShell() {
    return new StubShell()
        .on("incus list ^acme$", RUNNING_JSON)
        .on("mkdir -p /home/dev/.sail", "")
        .on("printf '%s'", "")
        .on("agent.pid", "123")
        .on("kill -0 123", "")
        .on("agent-session.json", "{\"task\": \"work\"}");
  }

  private static DispatchOperations.AdhocRequest background(String task) {
    return new DispatchOperations.AdhocRequest(task, null, null, true, false);
  }

  @Test
  void aBackgroundAdhocLaunchMintsAReservedRunWithItsOwnIdentity() throws Exception {
    var ops = operations(liveAgentShell());

    var session = ops.startAdhoc("acme", background("fix the flaky test"), HANDLE);

    var run = runStore.findById(session.runId()).orElseThrow();
    assertEquals("adhoc", run.role());
    assertEquals("", run.specId());
    assertEquals(HANDLE, run.node());
    assertEquals("running", run.status());
    assertEquals(List.of(), run.repos());
    assertEquals("sail-agent-" + session.runId(), run.unit());
    assertEquals("/home/dev/.sail/runs/" + session.runId() + "/agent.log", run.logPath());
    assertEquals(123, run.pid());
    assertEquals("fix the flaky test", run.task());
    assertNotNull(session.session());
    assertTrue(session.watcher().isPresent());
  }

  @Test
  void aBackgroundAdhocLaunchPublishesARunAddressedStartWithNoSpec() throws Exception {
    var ops = operations(liveAgentShell());

    var session = ops.startAdhoc("acme", background("task"), HANDLE);

    assertEquals(1, events.size());
    var event = events.getFirst();
    assertEquals(Event.WellKnownTypes.AGENT_SESSION_STARTED, event.type());
    assertNull(event.spec());
    assertEquals(session.runId(), event.data().get(Event.WellKnownData.RUN_ID));
  }

  @Test
  void aForegroundAdhocLaunchCompletesItsRunWithTheExitCode() throws Exception {
    var shell =
        new StubShell()
            .on("incus list ^acme$", RUNNING_JSON)
            .on("mkdir -p /home/dev/.sail", "")
            .on("printf '%s'", "");
    var ops = operations(shell, command -> 3, true);

    var session =
        ops.startAdhoc(
            "acme", new DispatchOperations.AdhocRequest("task", null, null, false, false), HANDLE);

    assertEquals(3, session.exitCode());
    var run = runStore.findById(session.runId()).orElseThrow();
    assertEquals("failed", run.status());
    assertEquals(3, run.exitCode());
    assertEquals("", run.unit(), "a foreground session owns no systemd unit");
    assertTrue(session.watcher().isEmpty());
  }

  @Test
  void anAdhocSessionBlocksDispatchAndDispatchBlocksAdhoc() throws Exception {
    var ops = operations(liveAgentShell());
    seedSpec("auth");
    ops.startAdhoc("acme", background("task"), HANDLE);

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                ops.dispatch(
                    "acme",
                    new DispatchOperations.Request("auth", "background", false, null, false),
                    ADMIN,
                    HANDLE));

    assertEquals(ErrorCode.AGENT_ALREADY_RUNNING, refusal.failure().errorCode());
    assertTrue(refusal.getMessage().contains("Ad-hoc agent run"), refusal.getMessage());
    assertEquals(
        SpecStatus.PENDING,
        specStore.findById("auth").orElseThrow().status(),
        "the refused dispatch never claims the spec");
  }

  @Test
  void aRunningDispatchBlocksTheAdhocLaunch() throws Exception {
    var ops = operations(liveAgentShell());
    seedSpec("auth");
    runStore.reserveDispatch(
        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        "acme",
        "auth",
        HANDLE,
        "build",
        List.of("app"),
        "claude-code",
        "b1",
        "task",
        "/log",
        "sail-agent-x");

    var refusal =
        assertThrows(ApiException.class, () -> ops.startAdhoc("acme", background("task"), HANDLE));

    assertEquals(ErrorCode.AGENT_ALREADY_RUNNING, refusal.failure().errorCode());
  }

  @Test
  void aSecondAdhocSessionIsRefusedWhileTheFirstIsLive() throws Exception {
    var ops = operations(liveAgentShell());
    var first = ops.startAdhoc("acme", background("one"), HANDLE);

    var refusal =
        assertThrows(ApiException.class, () -> ops.startAdhoc("acme", background("two"), HANDLE));

    assertEquals(ErrorCode.AGENT_ALREADY_RUNNING, refusal.failure().errorCode());
    assertTrue(refusal.getMessage().contains(first.runId()), refusal.getMessage());
  }

  @Test
  void aDryRunMintsNothingAndWritesNothing() throws Exception {
    var launchedCommands = new ArrayList<List<String>>();
    var yaml = tempDir.resolve("dry.yaml");
    Files.writeString(yaml, YAML);
    db = Sqlite.open(tempDir.resolve("dry.db"));
    new SchemaManager(db).migrate();
    runStore = new RunStore(db);
    var ops =
        new DispatchOperations(
            new StubShell().on("incus list ^acme$", RUNNING_JSON),
            yaml.toString(),
            new SpecStore(db),
            new ReviewStore(db),
            runStore,
            new FdeStore(db),
            events::add,
            new WatcherSpawner(new StubShell(), (command, logPath) -> 4242L),
            (project, config) -> "",
            command -> {
              throw new AssertionError("a dry run must not launch");
            },
            new DispatchOperations.Listener() {
              @Override
              public void launching(boolean background, List<String> command) {
                launchedCommands.add(command);
              }
            });

    var session =
        ops.startAdhoc(
            "acme", new DispatchOperations.AdhocRequest("task", null, null, true, true), HANDLE);

    assertNotNull(session.runId());
    assertEquals(1, launchedCommands.size());
    assertTrue(runStore.listForProject("acme").isEmpty());
    assertTrue(events.isEmpty());
  }

  @Test
  void aFailedBackgroundLaunchReleasesTheReservation() throws Exception {
    var shell =
        new StubShell()
            .on("incus list ^acme$", RUNNING_JSON)
            .on("mkdir -p /home/dev/.sail", "")
            .on("printf '%s'", "");
    var ops = operations(shell, command -> 1, true);

    assertThrows(ApiException.class, () -> ops.startAdhoc("acme", background("task"), HANDLE));

    var run = runStore.listForProject("acme").getFirst();
    assertEquals("failed", run.status());
    assertTrue(
        runStore.runningForProjectOnNode("acme", HANDLE).isEmpty(),
        "a launch that never produced an agent frees the container");
  }

  @Test
  void aBoxWithoutARunAggregateRefusesTheAdhocLaunch() throws Exception {
    var ops = operations(liveAgentShell(), command -> 0, false);

    var refusal =
        assertThrows(ApiException.class, () -> ops.startAdhoc("acme", background("task"), HANDLE));

    assertEquals(ErrorCode.COMMAND_FAILED, refusal.failure().errorCode());
  }

  @Test
  void theWorkspacePathScopesTheLaunchWorkDir() throws Exception {
    var commands = new ArrayList<List<String>>();
    var yaml = tempDir.resolve("path.yaml");
    Files.writeString(yaml, YAML);
    db = Sqlite.open(tempDir.resolve("path.db"));
    new SchemaManager(db).migrate();
    runStore = new RunStore(db);
    var ops =
        new DispatchOperations(
            liveAgentShell(),
            yaml.toString(),
            new SpecStore(db),
            new ReviewStore(db),
            runStore,
            new FdeStore(db),
            events::add,
            new WatcherSpawner(liveAgentShell(), (command, logPath) -> 4242L),
            (project, config) -> "",
            command -> {
              commands.add(command);
              return 0;
            },
            DispatchOperations.Listener.NONE);

    ops.startAdhoc(
        "acme", new DispatchOperations.AdhocRequest("task", null, "app/api", true, false), HANDLE);

    assertTrue(
        String.join(" ", commands.getFirst()).contains("/home/dev/workspace/app/api"),
        () -> String.join(" ", commands.getFirst()));
  }

  private void seedSpec(String id) {
    specStore.create(
        new SpecStore.SpecRow(
            id,
            "acme",
            "Spec " + id,
            SpecStatus.PENDING,
            HANDLE,
            null,
            null,
            null,
            null,
            0,
            HANDLE,
            null,
            null,
            HANDLE,
            List.of(),
            List.of("app")));
    specStore.setContent(id, "Do " + id, "");
  }

  private static final class StubShell implements ShellExec {
    private final Map<String, Result> scripts = new LinkedHashMap<>();

    StubShell on(String pattern, String stdout) {
      scripts.put(pattern, new Result(0, stdout, ""));
      return this;
    }

    @Override
    public Result exec(List<String> command) {
      var joined = String.join(" ", command);
      for (var entry : scripts.entrySet()) {
        if (joined.contains(entry.getKey())) {
          return entry.getValue();
        }
      }
      return new Result(1, "", "no script for " + joined);
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
