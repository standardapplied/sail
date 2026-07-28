/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The agent-principal lifecycle end to end: a real dispatch reserves the run and mints its
 * principal, the launch command carries the credential into the container environment, the local
 * API lane resolves that credential so the agent's spec update and emitted events are attributed to
 * the minted handle — and once the run is stopped, the same credential is refused with 401.
 */
class AgentPrincipalLifecycleTest {

  private static final String HANDLE = "uday";
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
    bus = new EventBus();
    var specStore = new SpecStore(db);
    var runStore = new RunStore(db);
    new FdeStore(db).add(HANDLE, null, null, "admin");
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
            List.of()));
    specStore.setContent("auth", "Do auth", "");

    var agentAlive = new AtomicBoolean(true);
    var shell = new AgentShell(agentAlive);
    var credential = new AtomicReference<String>();
    var events = new CopyOnWriteArrayList<Event>();
    var dispatchOps =
        new DispatchOperations(
            shell,
            yaml.toString(),
            specStore,
            new ReviewStore(db),
            runStore,
            new FdeStore(db),
            bus::publish,
            new WatcherSpawner(shell, (command, logPath) -> 4242L),
            (project, config) -> "",
            command -> {
              credential.set(command.getLast());
              return 0;
            },
            DispatchOperations.Listener.NONE);

    var outcome =
        dispatchOps.dispatch(
            "acme",
            new DispatchOperations.Request("auth", "background", false, null, false),
            ADMIN,
            HANDLE);
    var dispatched = assertInstanceOf(DispatchOperations.Dispatched.class, outcome);
    var runId = dispatched.runId();
    assertTrue(credential.get().startsWith("sailrun_"), "the launch env carries the credential");

    var run = runStore.findById(runId).orElseThrow();
    var expectedHandle = "claude/" + runId.substring(runId.length() - 6);
    assertEquals(expectedHandle, run.principal());
    assertEquals(HANDLE, run.owner());

    var operations =
        new SailOperations(
            shell,
            yaml.toString(),
            (command, logPath) -> 4242L,
            bus,
            null,
            specStore,
            new ReviewStore(db),
            runStore);
    var router = new LocalApiRouter(bus, operations);
    var delivered = new java.util.concurrent.CountDownLatch(1);
    var subscription =
        bus.subscribe(
            BusTesting.latching(
                new EventSubscriber() {
                  @Override
                  public String name() {
                    return "capture";
                  }

                  @Override
                  public java.util.function.Predicate<Event> filter() {
                    return e -> "agent_progress".equals(e.type());
                  }

                  @Override
                  public void onEvent(Event event) {
                    events.add(event);
                  }
                },
                delivered));

    var whoami = router.handle(request("GET", "/v1/whoami", credential.get(), ""));
    assertEquals(200, whoami.status());
    assertEquals(expectedHandle, whoami.body().get("handle"));
    assertEquals(HANDLE, whoami.body().get("owner"));

    var updated = router.handle(request("PUT", "/v1/specs/auth", credential.get(), "priority=3"));
    assertEquals(200, updated.status());
    assertEquals(
        expectedHandle,
        specStore.findById("auth").orElseThrow().updatedBy(),
        "the agent's spec update is attributed to its minted principal");

    var hookEvent =
        Event.of("acme", "auth", "agent_progress", "claude-code", "acme", Map.of("run_id", runId));
    var published =
        router.handle(request("POST", "/v1/events", credential.get(), hookEvent.toJsonLine()));
    assertEquals(202, published.status());
    BusTesting.awaitDelivery(delivered);
    var stamped = events.getFirst();
    assertNotNull(stamped, "the published event reaches the bus");
    assertEquals(
        expectedHandle, stamped.agent(), "event authorship is the server-resolved principal");

    var stopOps =
        new StopOperations(
            shell,
            yaml.toString(),
            specStore,
            runStore,
            bus::publish,
            (project, unit) -> agentAlive.set(false),
            StopOperations.Listener.NONE);
    var stopped = stopOps.stop(new StopOperations.RunTarget(runId), ADMIN, HANDLE, false);
    assertInstanceOf(StopOperations.Stopped.class, stopped);

    var refused = router.handle(request("GET", "/v1/whoami", credential.get(), ""));
    assertEquals(401, refused.status(), "a stopped run's credential is revoked");
    subscription.close();
  }

  private static LocalApiRequest request(
      String method, String path, String credential, String body) {
    return new LocalApiRequest(
        method,
        path,
        Map.of(),
        Map.of("authorization", "Bearer " + credential),
        body.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Answers the dispatch and stop lanes' probes: the container is running, the agent pid is live
   * until the halter flips it dead, and every filesystem staging command succeeds.
   */
  private static final class AgentShell implements ShellExec {
    private final AtomicBoolean agentAlive;

    AgentShell(AtomicBoolean agentAlive) {
      this.agentAlive = agentAlive;
    }

    @Override
    public Result exec(List<String> command) {
      var joined = String.join(" ", command);
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
