/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.ConnectEnvironment;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TestLogins;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The agent-principal lifecycle end to end: a real dispatch reserves the run and mints its
 * principal, the run's launch.env carries the credential into the container environment, the local
 * API lane resolves that credential so the agent's spec update and emitted events are attributed to
 * the minted handle — and once the run is stopped, the same credential is refused with 401.
 */
class AgentPrincipalLifecycleTest {

  private static final String HANDLE = "uday";
  private static final Actor ADMIN = new Actor(HANDLE, Role.ADMIN, Actor.Lane.CLI);

  private static final String RUNNING_JSON =
      """
      [{"name": "acme", "status": "Running", "state": {}}]
      """;

  private static final String YAML =
      """
      name: acme
      agent:
        type: claude-code
      """;

  @TempDir Path tempDir;
  private Sqlite db;
  private EventBus bus;

  @AfterEach
  void tearDown() {
    if (bus != null) {
      bus.close();
    }
    if (db != null) {
      db.close();
    }
  }

  @Test
  void aDispatchedRunActsAsItsPrincipalUntilStoppedThen401() throws Exception {
    var yaml = tempDir.resolve("sail.yaml");
    Files.writeString(yaml, YAML);
    db = Sqlite.open(tempDir.resolve("principal.db"));
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "acme");
    bus = new EventBus();
    var specStore = new SpecStore(db);
    var runStore = new RunStore(db);
    new FdeStore(db).add(HANDLE, null, null, "admin");
    TestLogins.store(db, HANDLE);
    Acting.as(
        HANDLE,
        () ->
            specStore.create(
                new SpecStore.SpecRow(
                    "auth",
                    "acme",
                    "Add auth",
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
                    List.of())));
    Acting.system(() -> specStore.setContent("auth", "Do auth", ""));

    var agentAlive = new AtomicBoolean(true);
    var shell = new AgentShell(agentAlive);
    var onArgv = new AtomicReference<String>();
    var events = new CopyOnWriteArrayList<Event>();
    var dispatchOps =
        new DispatchOperations(
            shell,
            TestProjects.reading(yaml),
            specStore,
            new ReviewStore(db),
            runStore,
            new FdeStore(db),
            bus::publish,
            new WatcherSpawner(shell, (command, logPath) -> 4242L),
            (project, config) -> "",
            command -> {
              onArgv.set(String.join(" ", command));
              return 0;
            },
            DispatchOperations.Listener.NONE,
            SessionYield.NONE);

    var outcome =
        Acting.by(
            ADMIN,
            () ->
                dispatchOps.dispatch(
                    "acme",
                    new DispatchOperations.Request("auth", "background", false, null, false),
                    HANDLE));
    var dispatched = assertInstanceOf(DispatchOperations.Dispatched.class, outcome);
    var runId = dispatched.runId();
    var credential = shell.pushed("SAIL_RUN_CREDENTIAL");
    assertTrue(credential.startsWith("sailrun_"), "the launch env carries the credential");
    assertFalse(onArgv.get().contains(credential), "and no command line does");

    var run = runStore.findById(runId).orElseThrow();
    var expectedHandle = "claude/" + runId;
    assertEquals(expectedHandle, run.principal());
    assertEquals(HANDLE, run.owner());

    var operations =
        new SailOperations(
            shell,
            TestProjects.reading(yaml),
            (command, logPath) -> 4242L,
            bus,
            specStore,
            new ReviewStore(db),
            runStore,
            null,
            ConnectEnvironment.detector(),
            SyncScheduler.disabled(),
            new FdeStore(db),
            SessionYield.NONE);
    TestControlPlane.standalone(operations, db, tempDir);
    var router = new SocketDoor(operations);
    var delivered = new CountDownLatch(1);
    var subscription =
        bus.subscribe(
            BusTesting.latching(
                new EventSubscriber() {
                  @Override
                  public String name() {
                    return "capture";
                  }

                  @Override
                  public Predicate<Event> filter() {
                    return e -> Event.WellKnownTypes.AGENT_TOOL_FINISHED.equals(e.type());
                  }

                  @Override
                  public void onEvent(Event event) {
                    events.add(event);
                  }
                },
                delivered));

    var whoami = router.ask("GET", "/v1/whoami", credential, "");
    assertEquals(200, whoami.status());
    assertEquals(expectedHandle, whoami.body().get("handle"));
    assertEquals(HANDLE, whoami.body().get("owner"));

    var updated = router.ask("PUT", "/v1/specs/auth", credential, "priority=3");
    assertEquals(200, updated.status());
    assertEquals(
        expectedHandle,
        specStore.findById("auth").orElseThrow().updatedBy(),
        "the agent's spec update is attributed to its minted principal");

    var hookEvent =
        Event.of(
            "other-project",
            "other-spec",
            Event.WellKnownTypes.AGENT_TOOL_FINISHED,
            "claude-code",
            "acme",
            Map.of("run_id", "some-other-run", "source", "watcher"));
    var published = router.ask("POST", "/v1/events", credential, hookEvent.toJsonLine());
    assertEquals(202, published.status());
    BusTesting.awaitDelivery(delivered);
    var stamped = events.getFirst();
    assertNotNull(stamped, "the published event reaches the bus");
    assertEquals(
        expectedHandle, stamped.agent(), "event authorship is the server-resolved principal");
    assertEquals("acme", stamped.project(), "the event is scoped to the credential's project");
    assertEquals("auth", stamped.spec(), "the event is scoped to the credential's spec");
    assertEquals(runId, stamped.data().get("run_id"), "a forged run_id is overridden");
    assertFalse(stamped.data().containsKey("source"), "the authoritative-stop marker is stripped");

    var stopOps =
        new StopOperations(
            shell,
            TestProjects.reading(yaml),
            specStore,
            runStore,
            bus::publish,
            (project, unit) -> {
              agentAlive.set(false);
              return new AgentSession.Halt.Ended();
            },
            StopOperations.Listener.NONE);
    var stopped =
        Acting.by(ADMIN, () -> stopOps.stop(new StopOperations.RunTarget(runId), HANDLE, false));
    assertInstanceOf(StopOperations.Stopped.class, stopped);

    var refused = router.ask("GET", "/v1/whoami", credential, "");
    assertEquals(401, refused.status(), "a stopped run's credential is revoked");
    subscription.close();
  }

  @Test
  void anAdminDispatchOnAnotherFdesBoxOwnsTheRunForTheAssigneeNotTheAdmin() throws Exception {
    var yaml = tempDir.resolve("sail.yaml");
    Files.writeString(yaml, YAML);
    db = Sqlite.open(tempDir.resolve("admin-dispatch.db"));
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "acme");
    bus = new EventBus();
    var specStore = new SpecStore(db);
    var runStore = new RunStore(db);
    var fdes = new FdeStore(db);
    fdes.add(HANDLE, null, null, "member");
    fdes.add("alice", null, null, "admin");
    var claude = Harnesses.of("claude-code");
    fdes.logins().store(fdes.byHandle(HANDLE).orElseThrow().id(), claude, null, null, "uday-token");
    fdes.logins()
        .store(fdes.byHandle("alice").orElseThrow().id(), claude, null, null, "alice-token");
    Acting.as(
        HANDLE,
        () ->
            specStore.create(
                new SpecStore.SpecRow(
                    "auth",
                    "acme",
                    "Add auth",
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
                    List.of())));
    Acting.system(() -> specStore.setContent("auth", "Do auth", ""));

    var shell = new AgentShell(new AtomicBoolean(true));
    var onArgv = new AtomicReference<String>();
    var dispatchOps =
        new DispatchOperations(
            shell,
            TestProjects.reading(yaml),
            specStore,
            new ReviewStore(db),
            runStore,
            fdes,
            bus::publish,
            new WatcherSpawner(shell, (command, logPath) -> 4242L),
            (project, config) -> "",
            command -> {
              onArgv.set(String.join(" ", command));
              return 0;
            },
            DispatchOperations.Listener.NONE,
            SessionYield.NONE);

    var outcome =
        Acting.by(
            new Actor("alice", Role.ADMIN, Actor.Lane.CLI),
            () ->
                dispatchOps.dispatch(
                    "acme",
                    new DispatchOperations.Request("auth", "background", false, null, false),
                    HANDLE));
    var dispatched = assertInstanceOf(DispatchOperations.Dispatched.class, outcome);

    var run = runStore.findById(dispatched.runId()).orElseThrow();
    assertEquals(
        HANDLE,
        run.owner(),
        "the agent acts for the assignee whose spec it builds, never for the initiating admin");
    assertEquals("uday-token", shell.pushed("CLAUDE_CODE_OAUTH_TOKEN"), "it spends uday's login");
    assertEquals(new RunStore.RunLogin("default", "token"), run.login());
    assertEquals("alice", run.dispatchedBy(), "and records who asked");
    var credential = shell.pushed("SAIL_RUN_CREDENTIAL");

    var operations =
        new SailOperations(
            shell,
            TestProjects.reading(yaml),
            (command, logPath) -> 4242L,
            bus,
            specStore,
            new ReviewStore(db),
            runStore,
            null,
            ConnectEnvironment.detector(),
            SyncScheduler.disabled(),
            new FdeStore(db),
            SessionYield.NONE);
    var router = new SocketDoor(operations);
    var updated = router.ask("PUT", "/v1/specs/auth", credential, "priority=7");
    assertEquals(
        200,
        updated.status(),
        "the dispatched agent can mutate its own assigned spec because it acts for the assignee");
  }

  /**
   * Answers the dispatch and stop lanes' probes: the container is running, the agent pid is live
   * until the halter flips it dead, and every filesystem staging command succeeds.
   */
  private static final class AgentShell implements ShellExec {
    private final AtomicBoolean agentAlive;
    private final Map<String, String> pushed = new ConcurrentHashMap<>();

    AgentShell(AtomicBoolean agentAlive) {
      this.agentAlive = agentAlive;
    }

    /** The value a run's launch.env pushed into the container gave {@code name}. */
    String pushed(String name) {
      return pushed.getOrDefault(name, "");
    }

    @Override
    public Result exec(List<String> command) {
      var joined = String.join(" ", command);
      if (joined.startsWith("incus file push") && command.getLast().endsWith("/launch.env")) {
        try {
          Files.readAllLines(Path.of(command.get(command.size() - 2)))
              .forEach(
                  line ->
                      pushed.put(
                          line.substring(0, line.indexOf('=')),
                          line.substring(line.indexOf('=') + 1)));
        } catch (IOException e) {
          return new Result(1, "", e.getMessage());
        }
        return new Result(0, "", "");
      }
      if (joined.contains("incus list ^acme$")) {
        return new Result(0, RUNNING_JSON, "");
      }
      if (joined.contains("agent.pid")) {
        return agentAlive.get() ? new Result(0, "123", "") : new Result(1, "", "gone");
      }
      if (joined.contains("kill -0 123")) {
        return agentAlive.get() ? new Result(0, "", "") : new Result(1, "", "gone");
      }
      if (joined.contains("agent-session.json")) {
        return new Result(0, "{\"task\": \"work\"}", "");
      }
      return new Result(0, "", "");
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
