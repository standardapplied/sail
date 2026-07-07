/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.ConnectEnvironment;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SessionStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SailApiOperationsTest {

  private static final String RUNNING_JSON =
      """
      [
        {
          "name": "acme",
          "status": "Running",
          "state": {
            "network": {
              "eth0": {
                "addresses": [
                  {"family": "inet", "address": "10.0.0.42", "scope": "global"}
                ]
              }
            }
          }
        }
      ]
      """;

  private static final String STOPPED_JSON =
      """
      [{"name": "acme", "status": "Stopped", "state": {}}]
      """;

  private static final String RUNNING_NO_IP_JSON =
      """
      [{"name": "acme", "status": "Running", "state": {}}]
      """;

  private static final String LIST_ALL_JSON =
      """
      [
        {
          "name": "acme",
          "status": "Running",
          "state": {
            "network": {
              "eth0": {
                "addresses": [
                  {"family": "inet", "address": "10.0.0.42", "scope": "global"}
                ]
              }
            }
          }
        },
        {"name": "zeta", "status": "Stopped", "state": {}}
      ]
      """;

  private static final String EMPTY_JSON = "[]";

  @TempDir Path tempDir;

  @Test
  void healthReturnsOk() {
    var operations = new SailApiOperations(new FakeShell(), "sail.yaml");

    assertEquals("ok", get(operations.health(), "status"));
  }

  @Test
  void defaultConstructorSupportsHealthChecks() {
    assertEquals("ok", get(new SailApiOperations().health(), "status"));
  }

  @Test
  void projectReturnsContainerAndAgentStatus() throws Exception {
    var operations = operations(shell().on("incus list ^acme$", RUNNING_JSON));

    var result = operations.project("acme");

    assertEquals("acme", get(result, "name"));
    assertEquals("running", get(result, "container_status"));
    assertTrue(get(result, "agent").toString().contains("claude-code"));
  }

  @Test
  void projectMapsStoppedMissingAndErrorStates() throws Exception {
    assertEquals(
        "stopped",
        get(
            operations(shell().on("incus list ^acme$", STOPPED_JSON)).project("acme"),
            "container_status"));
    assertEquals(
        "not_created",
        get(
            operations(shell().on("incus list ^acme$", EMPTY_JSON)).project("acme"),
            "container_status"));
    assertEquals(
        "error",
        get(
            operations(shell().on("incus list ^acme$", new ShellExec.Result(1, "", "boom")))
                .project("acme"),
            "container_status"));
  }

  @Test
  void projectOmitsAgentWhenUnconfigured() throws Exception {
    var operations = operations(noAgentYaml(), shell().on("incus list ^acme$", RUNNING_JSON));

    var result = operations.project("acme");

    assertFalse(containsKey(result, "agent"));
  }

  @Test
  void projectsListsContainersWhenNoCatalogIsWired() throws Exception {
    var operations = operations(shell().on("incus list --format json", LIST_ALL_JSON));

    var result = operations.projects();

    assertEquals(2, get(result, "total"));
    var encoded = ApiJson.withSchema(result.orThrow()).toString();
    assertTrue(encoded.contains("name=acme, container_status=running"));
    assertTrue(encoded.contains("name=zeta, container_status=stopped"));
  }

  @Test
  void projectsMergesCatalogAndContainersSorted() throws Exception {
    var operations =
        operationsWith(
            shell().on("incus list --format json", LIST_ALL_JSON),
            store -> {
              store.upsert("beta", "name: beta", "me");
              store.upsert("acme", "name: acme", "me");
            },
            environment());

    var result = operations.projects();

    assertEquals(3, get(result, "total"));
    var encoded = ApiJson.withSchema(result.orThrow()).toString();
    assertTrue(encoded.contains("name=beta, container_status=not_created"));
    assertTrue(
        encoded.indexOf("name=acme") < encoded.indexOf("name=beta")
            && encoded.indexOf("name=beta") < encoded.indexOf("name=zeta"));
    assertTrue(encoded.contains("name=acme, container_status=running"), "live state wins");
  }

  @Test
  void projectsFailsLegiblyWhenContainerListingFails() throws Exception {
    var result = operations(shell()).projects();

    assertError(ErrorCode.COMMAND_FAILED, result);
  }

  @Test
  void catalogConstructorServesTheProjectList() throws Exception {
    var yaml = baseYamlPath(tempDir);
    var db = Sqlite.open(tempDir.resolve("catalog-" + System.nanoTime() + ".db"));
    new SchemaManager(db).migrate();
    var projectStore = new ProjectStore(db);
    projectStore.upsert("beta", "name: beta", "me");
    var operations =
        new SailApiOperations(
            shell().on("incus list --format json", EMPTY_JSON),
            yaml.toString(),
            null,
            null,
            new SpecStore(db),
            new ReviewStore(db),
            projectStore);

    var result = operations.projects();

    assertEquals(1, get(result, "total"));
  }

  @Test
  void connectReturnsTheTwoHopSshTarget() throws Exception {
    var operations =
        operationsWith(shell().on("incus list ^acme$", RUNNING_JSON), store -> {}, environment());

    var result = operations.connect("acme");

    assertEquals("acme", get(result, "project"));
    assertEquals("203.0.113.7", get(result, "server_ip"));
    assertEquals("uday", get(result, "server_user"));
    assertEquals("10.0.0.42", get(result, "container_ip"));
    assertEquals("dev", get(result, "container_user"));
    assertEquals(true, get(result, "workstation_key_set"));
  }

  @Test
  void connectRejectsAProjectThatIsNotRunning() throws Exception {
    assertError(
        ErrorCode.PROJECT_STOPPED,
        operationsWith(shell().on("incus list ^acme$", STOPPED_JSON), store -> {}, environment())
            .connect("acme"));
    assertError(
        ErrorCode.PROJECT_NOT_CREATED,
        operationsWith(shell().on("incus list ^acme$", EMPTY_JSON), store -> {}, environment())
            .connect("acme"));
    assertError(
        ErrorCode.CONTAINER_ERROR,
        operationsWith(
                shell().on("incus list ^acme$", new ShellExec.Result(1, "", "boom")),
                store -> {},
                environment())
            .connect("acme"));
  }

  @Test
  void connectReportsAPendingContainerIp() throws Exception {
    var operations =
        operationsWith(
            shell().on("incus list ^acme$", RUNNING_NO_IP_JSON), store -> {}, environment());

    assertError(ErrorCode.CONTAINER_IP_UNAVAILABLE, operations.connect("acme"));
  }

  @Test
  void connectReportsAnUnconfiguredServerIp() throws Exception {
    var operations =
        operationsWith(
            shell().on("incus list ^acme$", RUNNING_JSON),
            store -> {},
            new ConnectEnvironment(null, "uday", false));

    assertError(ErrorCode.SERVER_IP_NOT_CONFIGURED, operations.connect("acme"));
  }

  @Test
  void specsReturnsBoardSummary() throws Exception {
    var operations =
        operationsWithStore(
            baseYaml(),
            shell().on("incus list ^acme$", RUNNING_JSON),
            SailApiOperationsTest::seedAuthBillingSetup);

    var result = operations.specs("acme");

    assertEquals("acme", get(result, "name"));
    assertTrue(get(result, "specs") instanceof List<?>);
    assertTrue(ApiJson.withSchema(result.orThrow()).toString().contains("next_ready_id=auth"));
  }

  @Test
  void specsWorkEvenWhenTheContainerIsStopped() throws Exception {
    var operations =
        operationsWithStore(
            baseYaml(),
            shell().on("incus list ^acme$", STOPPED_JSON),
            SailApiOperationsTest::seedAuthBillingSetup);

    var result = operations.specs("acme");

    assertEquals("acme", get(result, "name"));
    assertTrue(get(result, "specs") instanceof List<?>);
  }

  @Test
  void specReturnsContentWhenPresent() throws Exception {
    var operations =
        operationsWithStore(
            baseYaml(),
            shell().on("incus list ^acme$", RUNNING_JSON),
            store -> seedSpec(store, "auth", "Add auth", "pending", List.of(), "# Auth"));

    var result = operations.spec("acme", "auth");

    assertEquals(true, get(result, "content_available"));
    assertEquals("# Auth", get(result, "content"));
  }

  @Test
  void specReturnsNotFoundForUnknownSpec() throws Exception {
    var operations =
        operationsWithStore(
            baseYaml(),
            shell().on("incus list ^acme$", RUNNING_JSON),
            SailApiOperationsTest::seedAuthBillingSetup);

    var error = operations.spec("acme", "missing");

    assertError(ErrorCode.SPEC_NOT_FOUND, error);
  }

  @Test
  void specAllowsMissingContent() throws Exception {
    var operations =
        operationsWithStore(
            baseYaml(),
            shell().on("incus list ^acme$", RUNNING_JSON),
            store -> seedSpec(store, "auth", "Add auth", "pending", List.of(), ""));

    var result = operations.spec("acme", "auth");

    assertEquals(false, get(result, "content_available"));
    assertFalse(containsKey(result, "content"));
  }

  @Test
  void dispatchReturnsNoPendingSpecs() throws Exception {
    var operations =
        operationsWithStore(
            baseYaml(),
            shell().on("incus list ^acme$", RUNNING_JSON),
            store -> seedSpec(store, "done", "Done", "done", List.of(), ""));

    var result = operations.dispatch("acme", request());

    assertEquals(false, get(result, "dispatched"));
    assertEquals("no_pending_specs", get(result, "reason"));
  }

  @Test
  void dispatchDryRunUpdatesSpecAndReturnsStructuredResult() throws Exception {
    var operations =
        operationsWithStore(
            baseYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing")),
            SailApiOperationsTest::seedAuthBillingSetup);

    var result = operations.dispatch("acme", request("auth", "background", true));

    assertEquals(true, get(result, "dispatched"));
    assertTrue(get(result, "spec").toString().contains("status=in_progress"));
    assertTrue(get(result, "agent").toString().contains("running=false"));
  }

  @Test
  void dispatchPersistsResolvedRepoOverridesOnTheSpec() throws Exception {
    var stores = new SpecStore[1];
    var operations =
        operationsWithStore(
            multiRepoYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing")),
            store -> {
              stores[0] = store;
              seedAuthBillingSetup(store);
            });

    var result =
        operations.dispatch(
            "acme", new DispatchRequest("auth", "background", true, List.of("web")));

    assertEquals(true, get(result, "dispatched"));
    var persisted = stores[0].findById("auth").orElseThrow();
    assertEquals(SpecStatus.IN_PROGRESS, persisted.status());
    assertEquals(List.of("web"), persisted.repos());
  }

  @Test
  void dispatchRejectsBlockedSpec() throws Exception {
    var operations =
        operationsWithStore(
            baseYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing")),
            SailApiOperationsTest::seedAuthBillingSetup);

    var error = operations.dispatch("acme", request("billing"));

    assertError(ErrorCode.SPEC_NOT_READY, error);
  }

  @Test
  void dispatchRejectsInvalidMode() throws Exception {
    var operations = operations(shell().on("incus list ^acme$", RUNNING_JSON));

    var error = operations.dispatch("acme", request(null, "sideways", false));

    assertError(ErrorCode.INVALID_MODE, error);
  }

  @Test
  void dispatchRejectsUnknownSpec() throws Exception {
    var operations =
        operationsWithStore(
            baseYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing")),
            SailApiOperationsTest::seedAuthBillingSetup);

    var error = operations.dispatch("acme", request("missing"));

    assertError(ErrorCode.SPEC_NOT_FOUND, error);
  }

  @Test
  void dispatchRejectsRunningAgent() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", "123")
                .on("kill -0 123", "")
                .on("cat /home/dev/.sail/agent-session.json", "{\"task\": \"work\"}"));

    var error = operations.dispatch("acme", request());

    assertError(ErrorCode.AGENT_ALREADY_RUNNING, error);
  }

  @Test
  void projectMissingMapsToNotFound() throws Exception {
    var operations = operations(shell().on("incus list ^acme$", EMPTY_JSON));

    var error = operations.specs("acme");

    assertError(ErrorCode.PROJECT_NOT_CREATED, error);
  }

  @Test
  void projectErrorMapsToContainerError() throws Exception {
    var operations =
        operations(shell().on("incus list ^acme$", new ShellExec.Result(1, "", "incus down")));

    var error = operations.specs("acme");

    assertError(ErrorCode.CONTAINER_ERROR, error);
  }

  @Test
  void agentEndpointRejectsMissingProject() throws Exception {
    var operations = operations(shell().on("incus list ^acme$", EMPTY_JSON));

    var error = operations.agentStatus("acme");

    assertError(ErrorCode.PROJECT_NOT_CREATED, error);
  }

  @Test
  void agentEndpointRejectsContainerErrors() throws Exception {
    var operations =
        operations(shell().on("incus list ^acme$", new ShellExec.Result(1, "", "incus down")));

    var error = operations.agentStatus("acme");

    assertError(ErrorCode.CONTAINER_ERROR, error);
  }

  @Test
  void agentStatusReturnsNotRunning() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing")));

    var result = operations.agentStatus("acme");

    assertEquals(false, get(result, "agent_running"));
  }

  @Test
  void agentStatusReturnsRunningSessionDetails() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", "123")
                .on("kill -0 123", "")
                .on(
                    "cat /home/dev/.sail/agent-session.json",
                    "{\"task\": \"work\", \"started_at\": \"2026-01-01T00:00:00Z\", \"branch\": \"sail/auth\"}"));

    var result = operations.agentStatus("acme");

    assertEquals(true, get(result, "agent_running"));
    assertEquals(123, get(result, "pid"));
    assertEquals("work", get(result, "task"));
  }

  @Test
  void agentStatusMapsQueryFailures() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .throwOn("cat /home/dev/.sail/agent.pid", new IOException("denied")));

    var error = operations.agentStatus("acme");

    assertError(ErrorCode.AGENT_STATUS_FAILED, error);
  }

  @Test
  void agentLogHandlesMissingLog() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on(
                    "tail -n 200 /home/dev/.sail/agent.log",
                    new ShellExec.Result(1, "", "No such file")));

    var result = operations.agentLog("acme", 200);

    assertEquals("No agent log found", get(result, "error"));
  }

  @Test
  void agentLogReturnsLines() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("tail -n 2 /home/dev/.sail/agent.log", "one\ntwo\n"));

    var result = operations.agentLog("acme", 2);

    assertEquals(List.of("one", "two"), get(result, "lines"));
  }

  @Test
  void agentLogMapsThrownCommandsToApiError() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .throwOn("tail -n 200 /home/dev/.sail/agent.log", new IOException("no shell")));

    var error = operations.agentLog("acme", 200);

    assertError(ErrorCode.COMMAND_FAILED, error);
  }

  @Test
  void stopAgentReturnsNoAgentRunning() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing")));

    var result = operations.stopAgent("acme");

    assertEquals(false, get(result, "stopped"));
  }

  @Test
  void dispatchLaunchesBackgroundAgent() throws Exception {
    var operations =
        operations(
            baseYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("mkdir -p /home/dev/.sail", "")
                .on("claude", ""));

    var result = operations.dispatch("acme", request("auth"));

    assertEquals(true, get(result, "dispatched"));
    assertTrue(get(result, "agent").toString().contains("mode=background"));
  }

  @Test
  void dispatchUsesSpecAgentWhenPresent() throws Exception {
    var shell =
        shell()
            .on("incus list ^acme$", RUNNING_JSON)
            .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
            .on("mkdir -p /home/dev/workspace/specs", "")
            .on("printf '%s'", "")
            .on("mkdir -p /home/dev/.sail", "")
            .on("codex exec --dangerously-bypass-approvals-and-sandbox --model gpt-5.5", "");
    var operations =
        operationsWithStore(
            baseYaml(),
            shell,
            store ->
                seedSpec(
                    store,
                    "auth",
                    "Add auth",
                    "pending",
                    List.of(),
                    "Do auth",
                    "codex",
                    "gpt-5.5",
                    "high",
                    null));

    var result = operations.dispatch("acme", request("auth"));

    assertEquals(true, get(result, "dispatched"));
    assertTrue(get(result, "spec").toString().contains("agent=codex"));
    assertTrue(get(result, "spec").toString().contains("model=gpt-5.5"));
    assertTrue(get(result, "spec").toString().contains("reasoning_effort=high"));
    assertTrue(get(result, "agent").toString().contains("type=codex"));
    assertTrue(
        shell.invocations().stream()
            .anyMatch(
                command ->
                    command.contains(
                            "codex exec --dangerously-bypass-approvals-and-sandbox --model gpt-5.5")
                        && command.contains("model_reasoning_effort='\"high\"'")));
    assertFalse(
        shell.invocations().stream().anyMatch(command -> command.contains("claude --print")));
  }

  @Test
  void dispatchLaunchesForegroundAgentAndReturnsSessionDetails() throws Exception {
    var operations =
        operations(
            baseYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", "123")
                .on("kill -0 123", new ShellExec.Result(1, "", "missing"))
                .on("cat /home/dev/.sail/agent-session.json", "{\"task\": \"work\"}")
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("mkdir -p /home/dev/.sail", "")
                .on("bash -l -c", ""));

    var result = operations.dispatch("acme", request("auth", "foreground", false));

    assertTrue(get(result, "agent").toString().contains("mode=foreground"));
    assertTrue(get(result, "agent").toString().contains("pid=123"));
  }

  @Test
  void dispatchMapsUnexpectedFailures() throws Exception {
    var operations = operations(baseYaml(), shell().on("incus list ^acme$", RUNNING_JSON));

    var error = operations.dispatch("acme", null);

    assertError(ErrorCode.INTERNAL, error);
  }

  @Test
  void dispatchMapsLaunchFailure() throws Exception {
    var operations =
        operations(
            baseYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("-- mkdir -p /home/dev/.sail", "")
                .on("claude", new ShellExec.Result(1, "", "missing cli")));

    var error = operations.dispatch("acme", request("auth"));

    assertError(ErrorCode.AGENT_LAUNCH_FAILED, error);
  }

  @Test
  void dispatchLaunchesWatcherWhenGuardrailsAreConfigured() throws Exception {
    var launched = new LinkedHashMap<String, Object>();
    var operations =
        operations(
            guardrailsYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("-- mkdir -p /home/dev/.sail", "")
                .on("claude", ""),
            (command, logPath) -> {
              launched.put("command", command);
              launched.put("log_path", logPath.toString());
              return 4242L;
            });

    operations.dispatch("acme", request("auth"));

    assertTrue(launched.get("command").toString().contains("agent, watch, acme"));
    assertTrue(launched.get("log_path").toString().endsWith("watch.log"));
  }

  @Test
  void dispatchMapsWatcherFailureToLaunchFailure() throws Exception {
    var operations =
        operations(
            guardrailsYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("-- mkdir -p /home/dev/.sail", "")
                .on("claude", ""),
            (command, logPath) -> {
              throw new IOException("watch failed");
            });

    var error = operations.dispatch("acme", request("auth"));

    assertError(ErrorCode.AGENT_LAUNCH_FAILED, error);
  }

  @Test
  void dispatchRecordsTheWatcherPidOnTheSessionStartedEvent() throws Exception {
    var yaml = tempDir.resolve("sail-watcher-pid.yaml");
    Files.writeString(yaml, guardrailsYaml());
    var db = Sqlite.open(tempDir.resolve("watcher-pid.db"));
    new SchemaManager(db).migrate();
    var store = new SpecStore(db);
    seedAuthBillingSetup(store);
    var shell =
        shell()
            .on("incus list ^acme$", RUNNING_JSON)
            .on("cat /home/dev/.sail/agent.pid", "123")
            .onSequence(
                "kill -0 123", new ShellExec.Result(1, "", ""), new ShellExec.Result(0, "", ""))
            .on("cat /home/dev/.sail/agent-session.json", "{\"task\": \"work\"}")
            .on("mkdir -p /home/dev/workspace/specs", "")
            .on("printf '%s'", "")
            .on("-- mkdir -p /home/dev/.sail", "")
            .on("claude", "");
    try (var bus = new EventBus()) {
      var started = new java.util.concurrent.atomic.AtomicReference<Event>();
      var latch = new java.util.concurrent.CountDownLatch(1);
      bus.subscribe(
          BusTesting.latching(
              new EventSubscriber() {
                @Override
                public String name() {
                  return "capture";
                }

                @Override
                public Predicate<Event> filter() {
                  return e -> Event.WellKnownTypes.AGENT_SESSION_STARTED.equals(e.type());
                }

                @Override
                public void onEvent(Event event) {
                  started.set(event);
                }
              },
              latch));
      var operations =
          new SailApiOperations(
              shell, yaml.toString(), (cmd, log) -> 4242L, bus, null, store, null);

      operations.dispatch("acme", request("auth"));

      BusTesting.awaitDelivery(latch);
      assertEquals(4242L, started.get().data().get(Event.WellKnownData.WATCHER_PID));
    } finally {
      db.close();
    }
  }

  @Test
  void relaunchWatcherLaunchesAUnitAndNeverFallsBackToAPlainProcess() throws Exception {
    var operations =
        operations(
            guardrailsYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("systemd-run --user", new ShellExec.Result(0, "", "")),
            (command, logPath) -> {
              throw new IOException("relaunch must never use the process fallback");
            });

    var unit = operations.relaunchWatcher("acme").orElseThrow();

    assertEquals(new WatcherSpawner.Unit("sail-watch-acme", "user", false), unit);
  }

  @Test
  void relaunchWatcherIsEmptyWhenNoSystemdScopeAccepts() throws Exception {
    var operations =
        operations(
            guardrailsYaml(),
            shell().on("incus list ^acme$", RUNNING_JSON),
            (command, logPath) -> 4242L);

    assertTrue(operations.relaunchWatcher("acme").isEmpty());
  }

  @Test
  void relaunchWatcherIsEmptyWhenTheProjectDeclaresNoAgent() throws Exception {
    var operations =
        operations(
            """
            name: acme
            ssh:
              user: dev
            """,
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("systemd-run --user", new ShellExec.Result(0, "", "")),
            (command, logPath) -> 4242L);

    assertTrue(operations.relaunchWatcher("acme").isEmpty());
  }

  @Test
  void dispatchCreatesBranchWhenConfigured() throws Exception {
    var operations =
        operations(
            branchYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("test -d /home/dev/workspace/app/.git", "")
                .on("git -C /home/dev/workspace/app checkout -b sail/auth", ""));

    var result = operations.dispatch("acme", request("auth", "background", true));

    assertEquals(true, get(result, "branch_created"));
    assertTrue(get(result, "spec").toString().contains("sail/auth"));
  }

  @Test
  void dispatchUsesSpecBranchWhenProvided() throws Exception {
    var operations =
        operationsWithStore(
            branchYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("test -d /home/dev/workspace/app/.git", "")
                .on("git -C /home/dev/workspace/app checkout -b feat/custom", ""),
            store ->
                seedSpec(
                    store,
                    "auth",
                    "Add auth",
                    "pending",
                    List.of(),
                    "Do auth",
                    null,
                    null,
                    null,
                    "feat/custom"));

    var result = operations.dispatch("acme", request("auth", "background", true));

    assertTrue(get(result, "spec").toString().contains("feat/custom"));
  }

  @Test
  void dispatchSkipsBranchWhenRepoIsMissing() throws Exception {
    var operations =
        operations(
            branchYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on(
                    "test -d /home/dev/workspace/app/.git",
                    new ShellExec.Result(1, "", "missing")));

    var result = operations.dispatch("acme", request("auth", "background", true));

    assertEquals(false, get(result, "branch_created"));
  }

  @Test
  void dispatchMapsBranchFailure() throws Exception {
    var operations =
        operations(
            branchYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("test -d /home/dev/workspace/app/.git", "")
                .on(
                    "git -C /home/dev/workspace/app checkout -b sail/auth",
                    new ShellExec.Result(1, "", "exists")));

    var error = operations.dispatch("acme", request("auth", "background", true));

    assertError(ErrorCode.BRANCH_CREATE_FAILED, error);
  }

  @Test
  void dispatchCreatesSnapshotWhenConfigured() throws Exception {
    var operations =
        operations(
            snapshotYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("incus snapshot list acme --format json", "[]")
                .on("incus snapshot create acme", ""));

    var result = operations.dispatch("acme", request("auth", "background", true));

    assertFalse(get(result, "snapshot").toString().isBlank());
  }

  @Test
  void dispatchSkipsRecentSnapshot() throws Exception {
    var snapshots = "[{\"name\": \"snap\", \"created_at\": \"" + Instant.now() + "\"}]";
    var operations =
        operations(
            snapshotYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("incus snapshot list acme --format json", snapshots));

    var result = operations.dispatch("acme", request("auth", "background", true));

    assertEquals("", get(result, "snapshot"));
  }

  @Test
  void dispatchCreatesSnapshotWhenLatestTimestampIsInvalid() throws Exception {
    var operations =
        operations(
            snapshotYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on(
                    "incus snapshot list acme --format json",
                    "[{\"name\": \"snap\", \"created_at\": \"bad\"}]")
                .on("incus snapshot create acme", ""));

    var result = operations.dispatch("acme", request("auth", "background", true));

    assertFalse(get(result, "snapshot").toString().isBlank());
  }

  @Test
  void dispatchMapsSnapshotFailure() throws Exception {
    var operations =
        operations(
            snapshotYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("incus snapshot list acme --format json", "[]")
                .on("incus snapshot create acme", new ShellExec.Result(1, "", "no space")));

    var error = operations.dispatch("acme", request("auth", "background", true));

    assertError(ErrorCode.SNAPSHOT_FAILED, error);
  }

  @Test
  void stopAgentKillsRunningAgent() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", "123")
                .on("kill -0 123", "")
                .on("cat /home/dev/.sail/agent-session.json", "{\"task\": \"work\"}")
                .on("kill 123", "")
                .on("sleep 3", "")
                .on("kill -9 123", "")
                .on("rm -f /home/dev/.sail/agent.pid", ""));

    var result = operations.stopAgent("acme");

    assertEquals(true, get(result, "stopped"));
    assertEquals(123, get(result, "pid"));
  }

  @Test
  void stopAgentMapsKillFailure() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", "123")
                .on("kill -0 123", "")
                .on("cat /home/dev/.sail/agent-session.json", "{\"task\": \"work\"}")
                .throwOn("kill 123", new IOException("permission denied")));

    var error = operations.stopAgent("acme");

    assertError(ErrorCode.AGENT_STOP_FAILED, error);
  }

  @Test
  void agentReportReturnsSummary() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing")));

    var result = operations.agentReport("acme");

    assertEquals("acme", get(result, "name"));
    assertEquals("No session", get(result, "session_status"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void agentReportIncludesSpecsFromDatabase() throws Exception {
    var operations =
        operationsWithStore(
            baseYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing")),
            store -> seedSpec(store, "search", "Add search", "done", List.of(), "Do search"));

    var result = operations.agentReport("acme");

    var specs = (List<Map<String, Object>>) get(result, "specs");
    assertEquals(1, specs.size());
    assertEquals("search", specs.getFirst().get("id"));
  }

  @Test
  void agentReportMapsReporterFailure() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .throwOn("cat /home/dev/.sail/agent.pid", new IOException("boom")));

    var error = operations.agentReport("acme");

    assertError(ErrorCode.AGENT_REPORT_FAILED, error);
  }

  @Test
  void agentLogFailureMapsToApiError() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on(
                    "tail -n 200 /home/dev/.sail/agent.log",
                    new ShellExec.Result(1, "", "permission denied")));

    var error = operations.agentLog("acme", 200);

    assertError(ErrorCode.AGENT_LOG_FAILED, error);
  }

  @Test
  void missingDescriptorMapsToNotFound() {
    var operations = new SailApiOperations(shell(), tempDir.resolve("missail.yaml").toString());

    var error = operations.project("acme");

    assertError(ErrorCode.PROJECT_DESCRIPTOR_NOT_FOUND, error);
  }

  @Test
  void malformedDescriptorMapsToProjectLoadFailure() throws Exception {
    var operations = operations("name: [", shell().on("incus list ^acme$", RUNNING_JSON));

    var error = operations.project("acme");

    assertError(ErrorCode.PROJECT_LOAD_FAILED, error);
  }

  @Test
  void publishEventFailsWhenBusNotWired() throws Exception {
    var operations = operations(baseYaml(), shell());
    var result =
        operations.publishEvent(Event.of("acme", null, "spec_dispatched", "sail", "host-01"));
    assertError(ErrorCode.INTERNAL, result);
  }

  @Test
  void publishEventReturnsStampedIdWhenBusWired(@TempDir Path tmp) throws Exception {
    try (var bus = new EventBus()) {
      var persister = new AuditPersister(tmp.resolve("events.jsonl"), 16);
      var operations = new SailApiOperations(shell(), baseYamlPath(tmp).toString(), bus, persister);
      var result =
          operations.publishEvent(Event.of("acme", null, "spec_dispatched", "sail", "host-01"));
      assertTrue(result.isSuccess());
      assertEquals(1L, get(result, "id"));
      assertNotNull(get(result, "event"));
    }
  }

  @Test
  void recentEventsRejectsBadLimit() throws Exception {
    var operations = operations(baseYaml(), shell());
    assertError(ErrorCode.INVALID_REQUEST, operations.recentEvents(0));
    assertError(ErrorCode.INVALID_REQUEST, operations.recentEvents(-1));
    assertError(ErrorCode.INVALID_REQUEST, operations.recentEvents(99999));
  }

  @Test
  void recentEventsEmptyWhenPersisterMissing() throws Exception {
    var operations = operations(baseYaml(), shell());
    var result = operations.recentEvents(10);
    assertTrue(result.isSuccess());
    assertEquals(10, get(result, "limit"));
    assertEquals(0, get(result, "returned"));
  }

  @Test
  void recentEventsReplaysFromPersister(@TempDir Path tmp) throws Exception {
    try (var bus = new EventBus()) {
      var persister = new AuditPersister(tmp.resolve("events.jsonl"), 16);
      var latch = new java.util.concurrent.CountDownLatch(2);
      bus.subscribe(BusTesting.latching(persister, latch));
      var operations = new SailApiOperations(shell(), baseYamlPath(tmp).toString(), bus, persister);

      operations.publishEvent(Event.of("acme", null, "spec_dispatched", "sail", "h"));
      operations.publishEvent(Event.of("acme", null, "snapshot_created", "sail", "h"));

      BusTesting.awaitDelivery(latch);
      var result = operations.recentEvents(5);
      assertTrue(result.isSuccess());
    }
  }

  @Test
  void eventBusStatsEmptyWithoutBus() throws Exception {
    var operations = operations(baseYaml(), shell());
    var result = operations.eventBusStats();
    assertTrue(result.isSuccess());
    assertEquals(0L, get(result, "published"));
  }

  @Test
  void eventBusStatsListsSubscribers() throws Exception {
    try (var bus = new EventBus()) {
      bus.subscribe(
          new EventSubscriber() {
            @Override
            public String name() {
              return "test-subscriber";
            }

            @Override
            public Predicate<Event> filter() {
              return EventSubscriber.all();
            }

            @Override
            public void onEvent(Event event) {}
          });
      var operations = new SailApiOperations(shell(), baseYamlPath(tempDir).toString(), bus, null);

      var result = operations.eventBusStats();

      assertTrue(result.isSuccess());
      @SuppressWarnings("unchecked")
      var subs = (List<Map<String, Object>>) get(result, "subscribers");
      assertEquals(1, subs.size());
    }
  }

  @Test
  void globalSpecHistoryReturnsRevisions() throws Exception {
    var operations = operations(baseYaml(), shell());

    var result = operations.globalSpecHistory("auth");

    assertTrue(result.isSuccess());
    assertEquals("auth", get(result, "spec_id"));
    assertFalse(result.orThrow().revisions().isEmpty());
  }

  @Test
  void restoreGlobalSpecBringsBackARevision() throws Exception {
    var operations = operations(baseYaml(), shell());
    var rev = operations.globalSpecHistory("auth").orThrow().revisions().getLast().rev();

    var result = operations.restoreGlobalSpec("auth", new SpecRestoreRequest(rev));

    assertTrue(result.isSuccess());
    assertEquals(rev, get(result, "from_rev"));
  }

  @Test
  void reviewsForSpecIsEmptyWithoutReviews() throws Exception {
    var operations =
        operationsWithStores(
            baseYaml(), shell(), null, SailApiOperationsTest::seedAuthBillingSetup, s -> {});

    var result = operations.reviewsForSpec("auth");

    assertTrue(result.isSuccess());
    @SuppressWarnings("unchecked")
    var reviews = (List<Map<String, Object>>) get(result, "reviews");
    assertTrue(reviews.isEmpty());
  }

  @Test
  void reviewOperationsRejectUnknownIds() throws Exception {
    var operations = operationsWithStores(baseYaml(), shell(), null, s -> {}, s -> {});

    assertError(ErrorCode.NOT_FOUND, operations.reviewDetail("nope"));
    assertError(ErrorCode.NOT_FOUND, operations.approveReview("nope", "me"));
    assertError(ErrorCode.NOT_FOUND, operations.dismissFinding("nope", "f1"));
  }

  @Test
  void reviewDetailDismissAndApproveSucceedForSeededReview() throws Exception {
    var yaml = tempDir.resolve("sail-" + System.nanoTime() + ".yaml");
    Files.writeString(yaml, baseYaml());
    var db = Sqlite.open(tempDir.resolve("specs-" + System.nanoTime() + ".db"));
    new SchemaManager(db).migrate();
    var specStore = new SpecStore(db);
    var reviewStore = new ReviewStore(db);
    seedAuthBillingSetup(specStore);
    var reviewId = reviewStore.createReview("auth", 1);
    var stageId = reviewStore.createStage(reviewId, "human", "human");
    reviewStore.startStage(stageId, "uday");
    reviewStore.addFinding(
        stageId,
        Finding.create(
            Finding.Severity.HIGH,
            Finding.Category.SECURITY,
            "Auth.java",
            10,
            12,
            "Issue",
            "Description",
            "Evidence",
            new Finding.Suggestion("bad", "good", "why"),
            0.8));
    var findingId = reviewStore.findingsForReview(reviewId).getFirst().id();
    var operations =
        new SailApiOperations(
            shell(), yaml.toString(), null, null, null, specStore, reviewStore, null);

    assertTrue(operations.reviewDetail(reviewId).isSuccess());
    assertTrue(operations.dismissFinding(reviewId, findingId).isSuccess());
    assertTrue(operations.approveReview(reviewId, "uday").isSuccess());
  }

  @Test
  void dispatchWithoutBusStillCompletesWhenAgentRuns() throws Exception {
    var shell =
        shell()
            .on("incus list ^acme$", RUNNING_JSON)
            .onSequence(
                "cat /home/dev/.sail/agent.pid",
                new ShellExec.Result(1, "", "missing"),
                new ShellExec.Result(0, "123", ""))
            .on("kill -0 123", "")
            .on("cat /home/dev/.sail/agent-session.json", "{\"task\": \"work\"}")
            .on("mkdir -p /home/dev/workspace/specs", "")
            .on("printf '%s'", "")
            .on("mkdir -p /home/dev/.sail", "")
            .on("bash -l -c", "");
    var operations =
        operationsWithStores(
            baseYaml(), shell, null, SailApiOperationsTest::seedAuthBillingSetup, s -> {});

    var result = operations.dispatch("acme", request("auth", "foreground", false));

    assertTrue(result.isSuccess());
  }

  @Test
  void dispatchPublishesSnapshotCreatedWithBus() throws Exception {
    try (var bus = new EventBus()) {
      var shell =
          shell()
              .on("incus list ^acme$", RUNNING_JSON)
              .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
              .on("mkdir -p /home/dev/workspace/specs", "")
              .on("printf '%s'", "")
              .on("incus snapshot list acme --format json", "[]")
              .on("incus snapshot create acme", "");
      var operations =
          operationsWithStores(
              snapshotYaml(), shell, bus, SailApiOperationsTest::seedAuthBillingSetup, s -> {});

      var result = operations.dispatch("acme", request("auth", "background", true));

      assertTrue(result.isSuccess());
      assertFalse(get(result, "snapshot").toString().isBlank());
    }
  }

  @Test
  void dispatchRejectsStoppedProject() throws Exception {
    var operations =
        operationsWithStores(
            baseYaml(),
            shell().on("incus list ^acme$", STOPPED_JSON),
            null,
            SailApiOperationsTest::seedAuthBillingSetup,
            s -> {});

    assertError(
        ErrorCode.PROJECT_STOPPED,
        operations.dispatch("acme", request("auth", "background", false)));
  }

  @Test
  void dispatchRejectsNotCreatedProject() throws Exception {
    var operations =
        operationsWithStores(
            baseYaml(),
            shell().on("incus list ^acme$", "[]"),
            null,
            SailApiOperationsTest::seedAuthBillingSetup,
            s -> {});

    assertError(
        ErrorCode.PROJECT_NOT_CREATED,
        operations.dispatch("acme", request("auth", "background", false)));
  }

  @Test
  void dispatchRejectsErroredProject() throws Exception {
    var operations =
        operationsWithStores(
            baseYaml(),
            shell().on("incus list ^acme$", new ShellExec.Result(1, "", "boom")),
            null,
            SailApiOperationsTest::seedAuthBillingSetup,
            s -> {});

    assertError(
        ErrorCode.CONTAINER_ERROR,
        operations.dispatch("acme", request("auth", "background", false)));
  }

  @Test
  void agentSessionsFailWithoutSessionStore() throws Exception {
    var operations = operations(baseYaml(), shell());

    assertError(ErrorCode.INTERNAL, operations.agentSessions("acme"));
  }

  @Test
  void agentSessionsListsSessionsFromStore() throws Exception {
    var operations =
        operationsWithStores(
            baseYaml(),
            shell(),
            null,
            s -> {},
            sessions -> sessions.create("acme", "auth", "claude-code", "feat/auth", "do it", 123));

    var result = operations.agentSessions("acme");

    assertTrue(result.isSuccess());
    @SuppressWarnings("unchecked")
    var sessions = (List<Map<String, Object>>) get(result, "sessions");
    assertEquals(1, sessions.size());
  }

  @Test
  void dispatchPublishesAgentSessionStartedWhenRunning() throws Exception {
    try (var bus = new EventBus()) {
      var shell =
          shell()
              .on("incus list ^acme$", RUNNING_JSON)
              .onSequence(
                  "cat /home/dev/.sail/agent.pid",
                  new ShellExec.Result(1, "", "missing"),
                  new ShellExec.Result(0, "123", ""))
              .on("kill -0 123", "")
              .on("cat /home/dev/.sail/agent-session.json", "{\"task\": \"work\"}")
              .on("mkdir -p /home/dev/workspace/specs", "")
              .on("printf '%s'", "")
              .on("mkdir -p /home/dev/.sail", "")
              .on("bash -l -c", "");
      var operations =
          operationsWithStores(
              baseYaml(), shell, bus, SailApiOperationsTest::seedAuthBillingSetup, s -> {});

      var result = operations.dispatch("acme", request("auth", "foreground", false));

      assertTrue(result.isSuccess());
      assertEquals(2L, bus.stats().published());
    }
  }

  @Test
  void eventBusStatsReflectsBusState(@TempDir Path tmp) throws Exception {
    try (var bus = new EventBus()) {
      var persister = new AuditPersister(tmp.resolve("events.jsonl"), 16);
      var operations = new SailApiOperations(shell(), baseYamlPath(tmp).toString(), bus, persister);
      operations.publishEvent(Event.of("acme", null, "spec_dispatched", "sail", "h"));
      var result = operations.eventBusStats();
      assertTrue(result.isSuccess());
      assertEquals(1L, get(result, "published"));
    }
  }

  private Path baseYamlPath(Path dir) throws IOException {
    var yaml = dir.resolve("sail.yaml");
    Files.writeString(yaml, baseYaml());
    return yaml;
  }

  private static Object get(Result<?> result, String key) {
    return ApiJson.withSchema(result.orThrow()).get(key);
  }

  private static boolean containsKey(Result<?> result, String key) {
    return ApiJson.withSchema(result.orThrow()).containsKey(key);
  }

  private static void assertError(ErrorCode errorCode, Result<?> result) {
    assertTrue(result.isFailure());
    assertEquals(errorCode, result.errorCode());
  }

  private static DispatchRequest request() {
    return request(null, "background", false);
  }

  private static DispatchRequest request(String specId) {
    return request(specId, "background", false);
  }

  private static DispatchRequest request(String specId, String mode, boolean dryRun) {
    return new DispatchRequest(specId, mode, dryRun);
  }

  private SailApiOperations operations(String yamlContent, FakeShell shell) throws Exception {
    return operationsWithStore(yamlContent, shell, SailApiOperationsTest::seedAuthBillingSetup);
  }

  /** Builds operations backed by a migrated spec database seeded with {@code seed}. */
  private SailApiOperations operationsWithStore(
      String yamlContent, FakeShell shell, java.util.function.Consumer<SpecStore> seed)
      throws Exception {
    return operationsWithStore(yamlContent, shell, seed, null);
  }

  /** Builds operations backed by a full set of migrated stores (spec, review, session). */
  private SailApiOperations operationsWithStores(
      String yamlContent,
      FakeShell shell,
      EventBus bus,
      java.util.function.Consumer<SpecStore> seedSpecs,
      java.util.function.Consumer<SessionStore> seedSessions)
      throws Exception {
    var yaml = tempDir.resolve("sail-" + System.nanoTime() + ".yaml");
    Files.writeString(yaml, yamlContent);
    var db = Sqlite.open(tempDir.resolve("specs-" + System.nanoTime() + ".db"));
    new SchemaManager(db).migrate();
    var specStore = new SpecStore(db);
    var reviewStore = new ReviewStore(db);
    var sessionStore = new SessionStore(db);
    seedSpecs.accept(specStore);
    seedSessions.accept(sessionStore);
    return new SailApiOperations(
        shell,
        yaml.toString(),
        (command, logPath) -> 4242L,
        bus,
        null,
        specStore,
        reviewStore,
        sessionStore);
  }

  /** Builds operations with a seeded project catalog and a fixed connect environment. */
  private SailApiOperations operationsWith(
      FakeShell shell,
      java.util.function.Consumer<ProjectStore> seedProjects,
      ConnectEnvironment environment)
      throws Exception {
    var yaml = tempDir.resolve("sail-" + System.nanoTime() + ".yaml");
    Files.writeString(yaml, baseYaml());
    var db = Sqlite.open(tempDir.resolve("specs-" + System.nanoTime() + ".db"));
    new SchemaManager(db).migrate();
    var specStore = new SpecStore(db);
    seedAuthBillingSetup(specStore);
    var projectStore = new ProjectStore(db);
    seedProjects.accept(projectStore);
    return new SailApiOperations(
        shell,
        yaml.toString(),
        null,
        null,
        null,
        specStore,
        null,
        null,
        projectStore,
        () -> environment);
  }

  private static ConnectEnvironment environment() {
    return new ConnectEnvironment("203.0.113.7", "uday", true);
  }

  private SailApiOperations operationsWithStore(
      String yamlContent,
      FakeShell shell,
      java.util.function.Consumer<SpecStore> seed,
      WatcherSpawner.ProcessSpawner watcher)
      throws Exception {
    var yaml = tempDir.resolve("sail-" + System.nanoTime() + ".yaml");
    Files.writeString(yaml, yamlContent);
    var db = Sqlite.open(tempDir.resolve("specs-" + System.nanoTime() + ".db"));
    new SchemaManager(db).migrate();
    var store = new SpecStore(db);
    seed.accept(store);
    WatcherSpawner.ProcessSpawner fallback =
        watcher != null ? watcher : (command, logPath) -> 4242L;
    return new SailApiOperations(shell, yaml.toString(), fallback, null, null, store, null);
  }

  /**
   * Seeds setup (done), auth (pending, ready because setup is done, body "Do auth"), and billing
   * (pending but blocked because it depends on the still-pending auth). The DB enforces that a
   * dependency references a real spec, so "blocked" is modelled with a real not-done dependency.
   */
  private static void seedAuthBillingSetup(SpecStore store) {
    seedSpec(store, "setup", "Setup project", "done", List.of(), "");
    seedSpec(store, "auth", "Add auth", "pending", List.of("setup"), "Do auth");
    seedSpec(store, "billing", "Add billing", "pending", List.of("auth"), "");
  }

  private static void seedSpec(
      SpecStore store, String id, String title, String status, List<String> deps, String body) {
    seedSpec(store, id, title, status, deps, body, null, null, null, null);
  }

  private static void seedSpec(
      SpecStore store,
      String id,
      String title,
      String status,
      List<String> deps,
      String body,
      String agent,
      String model,
      String effort,
      String branch) {
    store.create(
        new SpecStore.SpecRow(
            id,
            "acme",
            title,
            SpecStatus.fromWire(status),
            null,
            agent,
            model,
            effort,
            branch,
            0,
            "me",
            null,
            null,
            "me",
            deps,
            List.of()));
    if (!body.isEmpty()) {
      store.setContent(id, body, "");
    }
  }

  private SailApiOperations operations(
      String yamlContent, FakeShell shell, WatcherSpawner.ProcessSpawner watcherLauncher)
      throws Exception {
    return operationsWithStore(
        yamlContent, shell, SailApiOperationsTest::seedAuthBillingSetup, watcherLauncher);
  }

  private static String baseYaml() {
    return """
        name: acme
        ssh:
          user: dev
        agent:
          type: claude-code
          specs_dir: specs
        """;
  }

  private static String multiRepoYaml() {
    return """
        name: acme
        ssh:
          user: dev
        repos:
          - url: https://github.com/acme/app.git
            path: app
          - url: https://github.com/acme/web.git
            path: web
        agent:
          type: claude-code
          specs_dir: specs
        """;
  }

  private static String noAgentYaml() {
    return """
        name: acme
        ssh:
          user: dev
        """;
  }

  private static String branchYaml() {
    return """
        name: acme
        ssh:
          user: dev
        repos:
          - url: https://github.com/acme/app.git
            path: app
        agent:
          type: claude-code
          specs_dir: specs
          auto_branch: true
          branch_prefix: sail/
        """;
  }

  private static String snapshotYaml() {
    return """
        name: acme
        ssh:
          user: dev
        agent:
          type: claude-code
          specs_dir: specs
          auto_snapshot: true
        """;
  }

  private static String guardrailsYaml() {
    return """
        name: acme
        ssh:
          user: dev
        agent:
          type: claude-code
          specs_dir: specs
          guardrails:
            max_duration: 4h
            action: stop
        """;
  }

  private SailApiOperations operations(FakeShell shell) throws Exception {
    return operations(baseYaml(), shell);
  }

  private static FakeShell shell() {
    return new FakeShell();
  }

  private static final class FakeShell implements ShellExec {
    private final Map<String, Result> scripts = new LinkedHashMap<>();
    private final Map<String, Deque<Result>> sequences = new LinkedHashMap<>();
    private final Map<String, Exception> failures = new LinkedHashMap<>();
    private final List<String> invocations = new ArrayList<>();

    FakeShell on(String pattern, String stdout) {
      return on(pattern, new Result(0, stdout, ""));
    }

    FakeShell on(String pattern, Result result) {
      scripts.put(pattern, result);
      return this;
    }

    /** Returns each result in turn for successive matches, then repeats the last one. */
    FakeShell onSequence(String pattern, Result... results) {
      sequences.put(pattern, new ArrayDeque<>(List.of(results)));
      return this;
    }

    FakeShell throwOn(String pattern, Exception failure) {
      failures.put(pattern, failure);
      return this;
    }

    @Override
    public Result exec(List<String> command) throws IOException {
      var joined = String.join(" ", command);
      invocations.add(joined);
      for (var entry : failures.entrySet()) {
        if (joined.contains(entry.getKey())) {
          throw (IOException) entry.getValue();
        }
      }
      for (var entry : sequences.entrySet()) {
        if (joined.contains(entry.getKey())) {
          var queue = entry.getValue();
          return queue.size() > 1 ? queue.poll() : queue.peek();
        }
      }
      for (var entry : scripts.entrySet()) {
        if (joined.contains(entry.getKey())) {
          return entry.getValue();
        }
      }
      return new Result(1, "", "no script for " + joined);
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) throws IOException {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }

    List<String> invocations() {
      return List.copyOf(invocations);
    }
  }
}
