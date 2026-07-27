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
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
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

class SailOperationsTest {

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
    var operations = new SailOperations(new FakeShell(), "sail.yaml");

    assertEquals("ok", get(operations.health(), "status"));
  }

  @Test
  void defaultConstructorSupportsHealthChecks() {
    assertEquals("ok", get(new SailOperations().health(), "status"));
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
        new SailOperations(
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
  @SuppressWarnings("unchecked")
  void fdesReturnsTheRosterSortedByHandle() throws Exception {
    var db = Sqlite.open(tempDir.resolve("roster-" + System.nanoTime() + ".db"));
    new SchemaManager(db).migrate();
    var fdeStore = new FdeStore(db);
    fdeStore.add("bob", "Bob", "bob@x.dev", "member");
    fdeStore.add("ada", "Ada Lovelace", "ada@x.dev", "admin");
    var operations =
        new SailOperations(
            new FakeShell(),
            "sail.yaml",
            null,
            null,
            new SpecStore(db),
            new ReviewStore(db),
            new RunStore(db),
            new ProjectStore(db),
            null,
            fdeStore);

    var roster = (List<Map<String, Object>>) get(operations.fdes(), "fdes");

    assertEquals(
        List.of("ada", "bob"),
        roster.stream().map(row -> row.get("handle")).toList(),
        "the roster is sorted by handle");
    assertEquals("Ada Lovelace", roster.get(0).get("display_name"));
    assertEquals("ada@x.dev", roster.get(0).get("email"));
    assertEquals("admin", roster.get(0).get("role"));
  }

  @Test
  void fdesFailsWhenTheRosterIsNotWired() {
    assertError(ErrorCode.INTERNAL, new SailOperations(new FakeShell(), "sail.yaml").fdes());
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
            SailOperationsTest::seedAuthBillingSetup);

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
            SailOperationsTest::seedAuthBillingSetup);

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
            SailOperationsTest::seedAuthBillingSetup);

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

    var result = dispatch(operations, "acme", request());

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
            SailOperationsTest::seedAuthBillingSetup);

    var result = dispatch(operations, "acme", request("auth", "background", true));

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
        dispatch(
            operations, "acme", new DispatchRequest("auth", "background", true, List.of("web")));

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
            SailOperationsTest::seedAuthBillingSetup);

    var error = dispatch(operations, "acme", request("billing"));

    assertError(ErrorCode.SPEC_NOT_READY, error);
  }

  @Test
  void dispatchRejectsInvalidMode() throws Exception {
    var operations = operations(shell().on("incus list ^acme$", RUNNING_JSON));

    var error = dispatch(operations, "acme", request(null, "sideways", false));

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
            SailOperationsTest::seedAuthBillingSetup);

    var error = dispatch(operations, "acme", request("missing"));

    assertError(ErrorCode.SPEC_NOT_FOUND, error);
  }

  @Test
  void dispatchRejectsRunningAgent() throws Exception {
    var operations =
        operationsWithStores(
            baseYaml(),
            shell().on("incus list ^acme$", RUNNING_JSON),
            null,
            store -> {
              seedSpec(store, "busy", "Busy spec", "in_progress", List.of(), "Do busy");
              seedSpec(store, "auth", "Add auth", "pending", List.of(), "Do auth");
            },
            runs ->
                runs.create(
                    R1,
                    "acme",
                    "busy",
                    LOCAL_HANDLE,
                    "build",
                    "claude-code",
                    "feat/busy",
                    "task",
                    123,
                    null,
                    RUN_LOG,
                    "sail-agent-" + R1));

    var error = dispatch(operations, "acme", request("auth"));

    assertError(ErrorCode.AGENT_ALREADY_RUNNING, error);
    assertTrue(fullError(error).contains(R1), fullError(error));
    assertTrue(fullError(error).contains("busy"), fullError(error));
  }

  @Test
  void aDeadAdHocPidFileNeverBlocksDispatch() throws Exception {
    var stores = new SpecStore[1];
    var operations =
        operationsWithStore(
            multiRepoYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", "123")
                .on("kill -0 123", new ShellExec.Result(1, "", "no such process")),
            store -> {
              stores[0] = store;
              seedAuthBillingSetup(store);
              seedSpec(store, "web-work", "Web work", "pending", List.of(), "Do web");
            });

    var result =
        dispatch(
            operations,
            "acme",
            new DispatchRequest("web-work", "background", true, List.of("web")));

    assertEquals(true, get(result, "dispatched"));
    assertEquals(List.of("web"), stores[0].findById("web-work").orElseThrow().repos());
  }

  @Test
  void aRecordedRunningRunOnADisjointRepoDoesNotBlockDispatch() throws Exception {
    var stores = new SpecStore[1];
    var operations =
        operationsWithStores(
            multiRepoYaml(),
            shell().on("incus list ^acme$", RUNNING_JSON),
            null,
            store -> {
              stores[0] = store;
              seedAuthBillingSetup(store);
              store.updateReposAndStatus("auth", List.of("app"), SpecStatus.IN_PROGRESS, "b1");
              seedSpec(store, "web-work", "Web work", "pending", List.of(), "Do web");
            },
            runs ->
                runs.reserveDispatch(
                    R1,
                    "acme",
                    "auth",
                    LOCAL_HANDLE,
                    "build",
                    List.of("app"),
                    "claude-code",
                    "b1",
                    "task",
                    RUN_LOG,
                    "sail-agent-" + R1));

    var result =
        dispatch(
            operations,
            "acme",
            new DispatchRequest("web-work", "background", true, List.of("web")));

    assertEquals(true, get(result, "dispatched"));
    assertEquals(List.of("web"), stores[0].findById("web-work").orElseThrow().repos());
  }

  @Test
  void aRecordedRunningRunOnAnOverlappingRepoRefusesNamingSpecRunAndRepos() throws Exception {
    var operations =
        operationsWithStores(
            multiRepoYaml(),
            shell().on("incus list ^acme$", RUNNING_JSON),
            null,
            store -> {
              seedAuthBillingSetup(store);
              store.updateReposAndStatus(
                  "auth", List.of("app", "web"), SpecStatus.IN_PROGRESS, "b1");
              seedSpec(store, "web-work", "Web work", "pending", List.of(), "Do web");
            },
            runs ->
                runs.reserveDispatch(
                    R1,
                    "acme",
                    "auth",
                    LOCAL_HANDLE,
                    "build",
                    List.of("app", "web"),
                    "claude-code",
                    "b1",
                    "task",
                    RUN_LOG,
                    "sail-agent-" + R1));

    var error =
        dispatch(
            operations,
            "acme",
            new DispatchRequest("web-work", "background", true, List.of("web")));

    assertError(ErrorCode.AGENT_ALREADY_RUNNING, error);
    assertTrue(fullError(error).contains("auth"), fullError(error));
    assertTrue(fullError(error).contains(R1), fullError(error));
    assertTrue(fullError(error).contains("web"), fullError(error));
  }

  @Test
  void aLiveDispatchRefusesAnOverlapAtomicallyBeforeClaimingTheSpec() throws Exception {
    var stores = new SpecStore[1];
    var runs = new java.util.concurrent.atomic.AtomicReference<RunStore>();
    var operations =
        operationsWithStores(
            multiRepoYaml(),
            shell().on("incus list ^acme$", RUNNING_JSON),
            null,
            store -> {
              stores[0] = store;
              seedAuthBillingSetup(store);
              store.updateReposAndStatus(
                  "auth", List.of("app", "web"), SpecStatus.IN_PROGRESS, "b1");
              seedSpec(store, "web-work", "Web work", "pending", List.of(), "Do web");
            },
            runStore -> {
              runs.set(runStore);
              runStore.reserveDispatch(
                  R1,
                  "acme",
                  "auth",
                  LOCAL_HANDLE,
                  "build",
                  List.of("app", "web"),
                  "claude-code",
                  "b1",
                  "task",
                  RUN_LOG,
                  "sail-agent-" + R1);
            });

    var error =
        dispatch(
            operations,
            "acme",
            new DispatchRequest("web-work", "background", false, List.of("web")));

    assertError(ErrorCode.AGENT_ALREADY_RUNNING, error);
    assertEquals(
        SpecStatus.PENDING,
        stores[0].findById("web-work").orElseThrow().status(),
        "a refused reservation fires before any spec mutation");
    assertEquals(
        1,
        runs.get().listForProject("acme").size(),
        "the refused dispatch must not leave a run row behind");
  }

  @Test
  void aFailedRunReservationAbortsDispatchBeforeAnyMutation() throws Exception {
    var yaml = tempDir.resolve("sail-broken-runs.yaml");
    Files.writeString(yaml, baseYaml());
    try (var db = Sqlite.open(tempDir.resolve("specs-broken-runs.db"))) {
      new SchemaManager(db).migrate();
      var specStore = new SpecStore(db);
      seedAuthBillingSetup(specStore);
      var brokenDb = Sqlite.open(tempDir.resolve("runs-broken.db"));
      new SchemaManager(brokenDb).migrate();
      var brokenRuns = new RunStore(brokenDb);
      brokenDb.close();
      var shell = shell().on("incus list ^acme$", RUNNING_JSON);
      var operations =
          new SailOperations(
              shell,
              yaml.toString(),
              (command, logPath) -> 4242L,
              null,
              null,
              specStore,
              new ReviewStore(db),
              brokenRuns);

      var error = dispatch(operations, "acme", request("auth"));

      assertError(ErrorCode.COMMAND_FAILED, error);
      assertEquals(
          SpecStatus.PENDING,
          specStore.findById("auth").orElseThrow().status(),
          "a dispatch that cannot reserve its run must abort before claiming the spec");
      assertTrue(
          shell.invocations().stream().noneMatch(command -> command.contains("claude")),
          "and must never reach the agent launch");
    }
  }

  @Test
  void aForeignNodesRunningRunNeverBlocksDispatchHere() throws Exception {
    var operations =
        operationsWithStores(
            multiRepoYaml(),
            shell().on("incus list ^acme$", RUNNING_JSON),
            null,
            store -> {
              seedAuthBillingSetup(store);
              store.updateReposAndStatus("auth", List.of("web"), SpecStatus.IN_PROGRESS, "b1");
              seedSpec(store, "web-work", "Web work", "pending", List.of(), "Do web");
            },
            runs ->
                runs.create(
                    R1,
                    "acme",
                    "auth",
                    "raj",
                    "build",
                    "claude-code",
                    "b1",
                    "task",
                    123,
                    null,
                    RUN_LOG,
                    "sail-agent-" + R1));

    var result =
        dispatch(
            operations,
            "acme",
            new DispatchRequest("web-work", "background", true, List.of("web")));

    assertEquals(true, get(result, "dispatched"));
  }

  @Test
  void aFinishedRunNeverBlocksDispatch() throws Exception {
    var operations =
        operationsWithStores(
            baseYaml(),
            shell().on("incus list ^acme$", RUNNING_JSON),
            null,
            SailOperationsTest::seedAuthBillingSetup,
            runs -> {
              runs.create(
                  R1,
                  "acme",
                  "setup",
                  LOCAL_HANDLE,
                  "build",
                  "claude-code",
                  "b0",
                  "task",
                  123,
                  null,
                  RUN_LOG,
                  "sail-agent-" + R1);
              runs.complete(R1, "stopped", 0);
            });

    var result = dispatch(operations, "acme", request("auth", "background", true));

    assertEquals(true, get(result, "dispatched"));
  }

  @Test
  void memberDispatchesOwnSpecSucceeds() throws Exception {
    var operations =
        operationsWithStore(
            baseYaml(), idleShell(), store -> seedAssigned(store, "auth", "pending", LOCAL_HANDLE));

    var member = new Actor(LOCAL_HANDLE, Role.MEMBER, Actor.Lane.API);
    var result =
        operations.dispatch("acme", request("auth", "background", true), member, LOCAL_HANDLE);

    assertEquals(true, get(result, "dispatched"));
  }

  @Test
  void anotherFdesSpecIsRefusedNotYourSpec() throws Exception {
    var operations =
        operationsWithStore(
            baseYaml(), idleShell(), store -> seedAssigned(store, "auth", "pending", LOCAL_HANDLE));

    var otherFde = new Actor("raj", Role.MEMBER, Actor.Lane.API);
    var error = operations.dispatch("acme", request("auth"), otherFde, LOCAL_HANDLE);

    assertError(ErrorCode.NOT_YOUR_SPEC, error);
  }

  @Test
  void specAssignedToAnotherNodeIsRefusedRunsOnOtherNode() throws Exception {
    var operations =
        operationsWithStore(
            baseYaml(), idleShell(), store -> seedAssigned(store, "auth", "pending", "raj"));

    var error = operations.dispatch("acme", request("auth"), ADMIN, LOCAL_HANDLE);

    assertError(ErrorCode.RUNS_ON_OTHER_NODE, error);
    assertTrue(fullError(error).contains("raj"), fullError(error));
  }

  @Test
  void viewerCredentialIsRefusedReadOnly() throws Exception {
    var operations =
        operationsWithStore(
            baseYaml(), idleShell(), store -> seedAssigned(store, "auth", "pending", LOCAL_HANDLE));

    var viewer = new Actor(LOCAL_HANDLE, Role.VIEWER, Actor.Lane.API);
    var error = operations.dispatch("acme", request("auth"), viewer, LOCAL_HANDLE);

    assertError(ErrorCode.READ_ONLY_CREDENTIAL, error);
  }

  @Test
  void blankLocalHandleIsRefusedNodeHandleUnset() throws Exception {
    var operations =
        operationsWithStore(
            baseYaml(), idleShell(), store -> seedAssigned(store, "auth", "pending", LOCAL_HANDLE));

    var error = operations.dispatch("acme", request("auth"), ADMIN, "");

    assertError(ErrorCode.NODE_HANDLE_UNSET, error);
  }

  @Test
  void autoSelectSkipsSpecsAssignedToOtherNodesForAdmin() throws Exception {
    var operations =
        operationsWithStore(
            baseYaml(), idleShell(), store -> seedAssigned(store, "auth", "pending", "raj"));

    var result = operations.dispatch("acme", request(), ADMIN, LOCAL_HANDLE);

    assertEquals(false, get(result, "dispatched"));
    assertEquals("no_pending_specs", get(result, "reason"));
  }

  @Test
  void autoSelectSkipsSpecsAssignedToOtherNodesForMember() throws Exception {
    var operations =
        operationsWithStore(
            baseYaml(), idleShell(), store -> seedAssigned(store, "auth", "pending", "raj"));

    var member = new Actor(LOCAL_HANDLE, Role.MEMBER, Actor.Lane.API);
    var result = operations.dispatch("acme", request(), member, LOCAL_HANDLE);

    assertEquals(false, get(result, "dispatched"));
    assertEquals("no_pending_specs", get(result, "reason"));
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

    var error = operations.agentStatus("acme", LOCAL_HANDLE);

    assertError(ErrorCode.PROJECT_NOT_CREATED, error);
  }

  @Test
  void agentEndpointRejectsContainerErrors() throws Exception {
    var operations =
        operations(shell().on("incus list ^acme$", new ShellExec.Result(1, "", "incus down")));

    var error = operations.agentStatus("acme", LOCAL_HANDLE);

    assertError(ErrorCode.CONTAINER_ERROR, error);
  }

  @Test
  void agentStatusReturnsNotRunning() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing")));

    var result = operations.agentStatus("acme", LOCAL_HANDLE);

    assertEquals(false, get(result, "agent_running"));
  }

  @Test
  void agentStatusReturnsRunningSessionDetails() throws Exception {
    var operations =
        opsWithRun(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/runs/" + R1 + "/agent.pid", "123")
                .on("kill -0 123", "")
                .on(
                    "cat /home/dev/.sail/runs/" + R1 + "/agent-session.json",
                    "{\"task\": \"work\", \"started_at\": \"2026-01-01T00:00:00Z\", \"branch\": \"sail/auth\"}"));

    var result = operations.agentStatus("acme", "node-a");

    assertEquals(true, get(result, "agent_running"));
    assertEquals(123, get(result, "pid"));
    assertEquals("work", get(result, "task"));
  }

  @Test
  void agentStatusMapsQueryFailures() throws Exception {
    var operations =
        opsWithRun(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .throwOn(
                    "cat /home/dev/.sail/runs/" + R1 + "/agent.pid", new IOException("denied")));

    var error = operations.agentStatus("acme", "node-a");

    assertError(ErrorCode.AGENT_STATUS_FAILED, error);
  }

  private static final String R1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String R2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  private static final String R3 = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
  private static final String RUN_LOG = "/home/dev/.sail/runs/" + R1 + "/agent.log";
  private static final String R2_LOG = "/home/dev/.sail/runs/" + R2 + "/agent.log";

  private SailOperations opsWithRun(FakeShell shell) throws Exception {
    return operationsWithStores(
        baseYaml(),
        shell,
        null,
        s -> {},
        runs ->
            runs.create(
                R1,
                "acme",
                "auth",
                "node-a",
                "build",
                "claude-code",
                "feat/auth",
                "do it",
                123,
                null,
                RUN_LOG,
                "sail-agent-" + R1));
  }

  private SailOperations opsWithLocalRunAndSpec(FakeShell shell) throws Exception {
    return operationsWithStores(
        baseYaml(),
        shell,
        null,
        specs -> seedSpec(specs, "auth", "Add auth", "pending", List.of(), "Do auth"),
        runs ->
            runs.create(
                R1,
                "acme",
                "auth",
                "node-a",
                "build",
                "claude-code",
                "feat/auth",
                "do it",
                123,
                null,
                RUN_LOG,
                "sail-agent-" + R1));
  }

  @Test
  void runLogAllowsTheRunSpecAssignee() throws Exception {
    var operations =
        opsWithLocalRunAndSpec(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("tail -n 2 -- " + RUN_LOG, "one\ntwo\n"));

    var assignee = new Actor(LOCAL_HANDLE, Role.MEMBER, Actor.Lane.API);
    var result = operations.runLog(R1, 2, "node-a", assignee);

    assertEquals(List.of("one", "two"), get(result, "lines"));
  }

  @Test
  void runLogRefusesAMemberWhoIsNotTheRunSpecAssignee() throws Exception {
    var operations = opsWithLocalRunAndSpec(shell().on("incus list ^acme$", RUNNING_JSON));

    var other = new Actor("raj", Role.MEMBER, Actor.Lane.API);
    assertError(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, operations.runLog(R1, 200, "node-a", other));
  }

  @Test
  void stopRunRefusesAMemberWhoIsNotTheRunSpecAssignee() throws Exception {
    var operations = opsWithLocalRunAndSpec(shell().on("incus list ^acme$", RUNNING_JSON));

    var other = new Actor("raj", Role.MEMBER, Actor.Lane.API);
    assertError(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, operations.stopRun(R1, "node-a", other));
  }

  @Test
  void runLogTailsTheRunScopedLogForALocalRun() throws Exception {
    var operations =
        opsWithRun(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("tail -n 2 -- " + RUN_LOG, "one\ntwo\n"));

    var result = operations.runLog(R1, 2, "node-a", ADMIN);

    assertEquals(List.of("one", "two"), get(result, "lines"));
    assertEquals(R1, get(result, "run_id"));
  }

  @Test
  void runLogIgnoresAForgedPersistedLogPath() throws Exception {
    var shell =
        shell().on("incus list ^acme$", RUNNING_JSON).on("tail -n 2 -- " + RUN_LOG, "safe\n");
    var operations =
        operationsWithStores(
            baseYaml(),
            shell,
            null,
            s -> {},
            runs ->
                runs.create(
                    R1,
                    "acme",
                    "auth",
                    "node-a",
                    "build",
                    "claude-code",
                    null,
                    null,
                    null,
                    null,
                    "/home/dev/.ssh/id_ed25519",
                    "sail-agent-" + R1));

    assertEquals(List.of("safe"), get(operations.runLog(R1, 2, "node-a", ADMIN), "lines"));
    assertTrue(shell.invocations().stream().noneMatch(cmd -> cmd.contains("id_ed25519")));
  }

  @Test
  void runLogHandlesAMissingLogFile() throws Exception {
    var operations =
        opsWithRun(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("tail -n 200 -- " + RUN_LOG, new ShellExec.Result(1, "", "No such file")));

    assertEquals(
        "No log found for this run.", get(operations.runLog(R1, 200, "node-a", ADMIN), "error"));
  }

  @Test
  void runLogMapsThrownCommandsToApiError() throws Exception {
    var operations =
        opsWithRun(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .throwOn("tail -n 200 -- " + RUN_LOG, new IOException("no shell")));

    assertError(ErrorCode.COMMAND_FAILED, operations.runLog(R1, 200, "node-a", ADMIN));
  }

  @Test
  void runLogRefusesAForeignRunWithStructuredProvenance() throws Exception {
    var operations = opsWithRun(shell().on("incus list ^acme$", RUNNING_JSON));

    var result = operations.runLog(R1, 200, "sumesh", ADMIN);

    assertError(ErrorCode.RUN_ON_OTHER_NODE, result);
    var fields =
        result.fieldErrors().stream()
            .collect(java.util.stream.Collectors.toMap(FieldError::field, FieldError::message));
    assertEquals("node-a", fields.get("node"));
    assertEquals("auth", fields.get("spec"));
    assertEquals("acme", fields.get("project"));
  }

  @Test
  void aRunWithABlankNodeFailsClosedAsForeign() throws Exception {
    var operations =
        operationsWithStores(
            baseYaml(),
            shell(),
            null,
            s -> {},
            runs ->
                runs.create(
                    R2,
                    "acme",
                    "auth",
                    "",
                    "build",
                    "claude-code",
                    null,
                    null,
                    null,
                    null,
                    RUN_LOG,
                    "sail-agent-" + R2));

    assertError(ErrorCode.RUN_ON_OTHER_NODE, operations.runLog(R2, 200, "node-a", ADMIN));
  }

  @Test
  void aBlankNodeRunIsLocalToABoxThatHasNoHandle() throws Exception {
    var operations =
        operationsWithStores(
            baseYaml(),
            shell().on("incus list ^acme$", RUNNING_JSON).on("tail -n 2 -- " + R2_LOG, "hi\n"),
            null,
            s -> {},
            runs ->
                runs.create(
                    R2,
                    "acme",
                    "auth",
                    "",
                    "build",
                    "claude-code",
                    null,
                    null,
                    null,
                    null,
                    RUN_LOG,
                    "sail-agent-" + R2));

    assertEquals(List.of("hi"), get(operations.runLog(R2, 2, "", ADMIN), "lines"));
  }

  @Test
  void aStampedRunIsForeignToABoxThatHasNoHandle() throws Exception {
    var operations = opsWithRun(shell().on("incus list ^acme$", RUNNING_JSON));

    assertError(ErrorCode.RUN_ON_OTHER_NODE, operations.runLog(R1, 200, "", ADMIN));
  }

  @Test
  void runLogUnknownRunIsNotFound() throws Exception {
    var operations = opsWithRun(shell());

    assertError(ErrorCode.RUN_NOT_FOUND, operations.runLog("nope", 200, "node-a", ADMIN));
  }

  @Test
  void runsListAndDetailExposeNodeProvenance() throws Exception {
    var operations = opsWithRun(shell());

    assertEquals(1, ((List<?>) get(operations.runs("acme", null), "runs")).size());
    assertEquals("node-a", get(operations.run(R1), "node"));
    assertError(ErrorCode.RUN_NOT_FOUND, operations.run("nope"));
  }

  @Test
  void stopRunRefusesAForeignRun() throws Exception {
    var operations = opsWithRun(shell().on("incus list ^acme$", RUNNING_JSON));

    assertError(ErrorCode.RUN_ON_OTHER_NODE, operations.stopRun(R1, "sumesh", ADMIN));
  }

  @Test
  void stopRunReturnsNoAgentRunningForALocalRunWithNoLiveProcess() throws Exception {
    var operations =
        opsWithRun(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on(
                    "cat /home/dev/.sail/runs/" + R1 + "/agent.pid",
                    new ShellExec.Result(1, "", "missing")));

    assertEquals(false, get(operations.stopRun(R1, "node-a", ADMIN), "stopped"));
  }

  @Test
  void stopRunOnAnAlreadyFinishedRunKillsNothing() throws Exception {
    var operations =
        operationsWithStores(
            baseYaml(),
            shell().on("incus list ^acme$", RUNNING_JSON),
            null,
            s -> {},
            runs -> {
              runs.create(
                  R1,
                  "acme",
                  "auth",
                  "node-a",
                  "build",
                  "claude-code",
                  "feat/auth",
                  "do it",
                  123,
                  null,
                  RUN_LOG,
                  "sail-agent-" + R1);
              runs.complete(R1, "completed", 0);
            });

    var result = operations.stopRun(R1, "node-a", ADMIN);

    assertEquals(false, get(result, "stopped"));
    assertEquals("run_not_running", get(result, "reason"));
  }

  @Test
  void stopRunHaltsWhateverItsOwnRunScopedPidFileRecords() throws Exception {
    var shell =
        shell()
            .on("incus list ^acme$", RUNNING_JSON)
            .on("cat /home/dev/.sail/runs/" + R1 + "/agent.pid", "999")
            .onSequence(
                "kill -0 999", new ShellExec.Result(0, "", ""), new ShellExec.Result(1, "", ""))
            .on("cat /home/dev/.sail/runs/" + R1 + "/agent-session.json", "{\"task\": \"other\"}");
    var operations = opsWithRun(shell);

    var result = operations.stopRun(R1, "node-a", ADMIN);

    assertEquals(true, get(result, "stopped"), "the run-scoped pid file is the run's identity");
    assertEquals(999, get(result, "pid"));
  }

  @Test
  void stopRunStopsOnlyItsOwnRunsUnit() throws Exception {
    var shell =
        shell()
            .on("incus list ^acme$", RUNNING_JSON)
            .on("runs/" + R1 + "/agent.pid", "123")
            .onSequence(
                "kill -0 123", new ShellExec.Result(0, "", ""), new ShellExec.Result(1, "", ""));
    var operations =
        operationsWithStores(
            baseYaml(),
            shell,
            null,
            s2 -> {},
            runs -> {
              runs.create(
                  R1,
                  "acme",
                  "auth",
                  "node-a",
                  "build",
                  "claude-code",
                  "feat/auth",
                  "do it",
                  123,
                  null,
                  RUN_LOG,
                  "sail-agent-" + R1);
              runs.create(
                  R2,
                  "acme",
                  "billing",
                  "node-a",
                  "build",
                  "claude-code",
                  "feat/billing",
                  "do it",
                  456,
                  null,
                  R2_LOG,
                  "sail-agent-" + R2);
              runs.createReview(
                  R3,
                  "acme",
                  "auth",
                  "node-a",
                  "codex",
                  "feat/auth",
                  "review it",
                  "/home/dev/.sail/runs/" + R3 + "/review.log",
                  "sail-review-" + R3);
            });

    var result = operations.stopRun(R1, "node-a", ADMIN);

    assertEquals(true, get(result, "stopped"));
    assertTrue(
        shell.invocations().stream().anyMatch(cmd -> cmd.contains("runs/" + R1 + "/agent.pid")),
        "the stop reads the run's own pid file");
    assertTrue(
        shell.invocations().stream().noneMatch(cmd -> cmd.contains(R2)),
        "the concurrent run's unit and files are untouched");
    assertTrue(
        shell.invocations().stream().noneMatch(cmd -> cmd.contains("kill 456")),
        "the concurrent run's agent is never signalled");
  }

  @Test
  void agentStatusListsEveryLocalRunningRunProbedOnItsOwnUnit() throws Exception {
    var shell =
        shell()
            .on("incus list ^acme$", RUNNING_JSON)
            .on("runs/" + R1 + "/agent.pid", "123")
            .on("kill -0 123", "")
            .on("runs/" + R2 + "/agent.pid", "456")
            .on("kill -0 456", new ShellExec.Result(1, "", "gone"));
    var operations =
        operationsWithStores(
            baseYaml(),
            shell,
            null,
            s2 -> {},
            runs -> {
              runs.create(
                  R1,
                  "acme",
                  "auth",
                  "node-a",
                  "build",
                  "claude-code",
                  "feat/auth",
                  "do it",
                  123,
                  null,
                  RUN_LOG,
                  "sail-agent-" + R1);
              runs.create(
                  R2,
                  "acme",
                  "billing",
                  "node-a",
                  "build",
                  "claude-code",
                  "feat/billing",
                  "do it",
                  456,
                  null,
                  R2_LOG,
                  "sail-agent-" + R2);
            });

    var result = operations.agentStatus("acme", "node-a");

    assertEquals(true, get(result, "agent_running"));
    var runs = (List<?>) get(result, "runs");
    assertEquals(2, runs.size(), "every local running run is listed");
    var encoded = ApiJson.withSchema(result.orThrow()).toString();
    assertTrue(encoded.contains(R1) && encoded.contains(R2), encoded);
    assertFalse(encoded.contains("review.log"), "build agent status excludes review executions");
  }

  @Test
  void agentStatusReportsNoSessionWhenNoRunIsRunning() throws Exception {
    var operations =
        operationsWithStores(
            baseYaml(), shell().on("incus list ^acme$", RUNNING_JSON), null, s2 -> {}, runs -> {});

    var result = operations.agentStatus("acme", "node-a");

    assertEquals(false, get(result, "agent_running"));
    assertEquals(0, ((List<?>) get(result, "runs")).size());
  }

  @Test
  void agentStatusListsAnAdhocRunLikeAnySession() throws Exception {
    var operations =
        operationsWithStores(
            baseYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/runs/" + R1 + "/agent.pid", "123")
                .on("kill -0 123", "")
                .on(
                    "cat /home/dev/.sail/runs/" + R1 + "/agent-session.json",
                    "{\"task\": \"ad hoc\"}"),
            null,
            s2 -> {},
            runs ->
                runs.reserveDispatch(
                    R1,
                    "acme",
                    "",
                    "node-a",
                    "adhoc",
                    List.of(),
                    "claude-code",
                    null,
                    "ad hoc",
                    RUN_LOG,
                    "sail-agent-" + R1));

    var result = operations.agentStatus("acme", "node-a");

    assertEquals(true, get(result, "agent_running"));
    assertEquals("ad hoc", get(result, "task"));
    assertEquals(1, ((List<?>) get(result, "runs")).size());
  }

  @Test
  void dispatchLaunchesBackgroundAgent() throws Exception {
    var runs = new java.util.concurrent.atomic.AtomicReference<RunStore>();
    var shell =
        shell()
            .on("incus list ^acme$", RUNNING_JSON)
            .on("agent.pid", "4242")
            .on("kill -0 4242", new ShellExec.Result(1, "", "missing"))
            .on("agent-session.json", "{\"task\": \"work\"}")
            .on("mkdir -p /home/dev/workspace/specs", "")
            .on("printf '%s'", "")
            .on("mkdir -p /home/dev/.sail", "")
            .on("claude", "");
    var operations =
        operationsWithStores(
            baseYaml(), shell, null, SailOperationsTest::seedAuthBillingSetup, runs::set);

    var result = dispatch(operations, "acme", request("auth"));

    assertEquals(true, get(result, "dispatched"));
    assertTrue(get(result, "agent").toString().contains("mode=background"));
    var recorded = runs.get().listForProject("acme");
    assertEquals(1, recorded.size(), "a background dispatch records its run in the aggregate");
    assertEquals("running", recorded.getFirst().status());
    assertEquals(4242, recorded.getFirst().pid(), "the launched agent's pid is stamped on the run");
    assertTrue(
        shell.invocations().stream().noneMatch(command -> command.contains("review.log")),
        "dispatch must never touch a review's log: a concurrent pipeline may be mid-review");
  }

  @Test
  void aForegroundRunRemainsUnprobeableWhileItsLauncherOwnsCompletion() throws Exception {
    var runs = new java.util.concurrent.atomic.AtomicReference<RunStore>();
    var shell =
        shell()
            .on("incus list ^acme$", RUNNING_JSON)
            .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
            .on("agent.pid", "123")
            .on("kill -0 123", "")
            .on("agent-session.json", "{\"task\": \"work\"}")
            .on("mkdir -p /home/dev/workspace/specs", "")
            .on("printf '%s'", "")
            .on("mkdir -p /home/dev/.sail", "")
            .on("bash -l -c", "");
    var operations =
        operationsWithStores(
            baseYaml(), shell, null, SailOperationsTest::seedAuthBillingSetup, runs::set);

    dispatch(operations, "acme", request("auth", "foreground", false));

    var recorded = runs.get().listForProject("acme").getFirst();
    assertEquals("", recorded.unit());
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
            .on(
                "codex exec --dangerously-bypass-approvals-and-sandbox --dangerously-bypass-hook-trust --model gpt-5.5",
                "");
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

    var result = dispatch(operations, "acme", request("auth"));

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
                            "codex exec --dangerously-bypass-approvals-and-sandbox --dangerously-bypass-hook-trust --model gpt-5.5")
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
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .on("agent.pid", "123")
                .on("kill -0 123", "")
                .on("agent-session.json", "{\"task\": \"work\"}")
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("mkdir -p /home/dev/.sail", "")
                .on("bash -l -c", ""));

    var result = dispatch(operations, "acme", request("auth", "foreground", false));

    assertTrue(get(result, "agent").toString().contains("mode=foreground"));
    assertTrue(get(result, "agent").toString().contains("pid=123"));
  }

  @Test
  void dispatchMapsUnexpectedFailures() throws Exception {
    var operations = operations(baseYaml(), shell().on("incus list ^acme$", RUNNING_JSON));

    var error = dispatch(operations, "acme", null);

    assertError(ErrorCode.INTERNAL, error);
  }

  @Test
  void serverStartConstructorWiresTheRunLane() throws Exception {
    var yaml = tempDir.resolve("sail-" + System.nanoTime() + ".yaml");
    Files.writeString(yaml, baseYaml());
    var db = Sqlite.open(tempDir.resolve("specs-" + System.nanoTime() + ".db"));
    new SchemaManager(db).migrate();
    var operations =
        new SailOperations(
            shell().on("incus list ^acme$", RUNNING_JSON),
            yaml.toString(),
            new EventBus(),
            null,
            new SpecStore(db),
            new ReviewStore(db),
            new RunStore(db),
            new ProjectStore(db),
            SyncScheduler.disabled(),
            new FdeStore(db));

    var result = operations.runs("acme", null);

    assertTrue(result.isSuccess());
  }

  @Test
  void dispatchRestartWithoutASpecIdIsACallerError() throws Exception {
    var operations =
        operationsWithStore(baseYaml(), idleShell(), SailOperationsTest::seedAuthBillingSetup);

    var error =
        dispatch(operations, "acme", new DispatchRequest(null, "background", false, null, true));

    assertError(ErrorCode.INVALID_REQUEST, error);
    assertTrue(fullError(error).contains("spec id"), fullError(error));
  }

  @Test
  void dispatchWithoutRestartOnANonPendingSpecKeepsTheStructuredRefusal() throws Exception {
    var stores = new SpecStore[1];
    var operations =
        operationsWithStore(
            baseYaml(),
            idleShell(),
            store -> {
              stores[0] = store;
              seedSpec(store, "auth", "Add auth", "review", List.of(), "Do auth");
            });

    var error = dispatch(operations, "acme", request("auth"));

    assertError(ErrorCode.SPEC_NOT_READY, error);
    assertTrue(error.action().contains("restart"), error.action());
    assertFalse(error.action().contains("--restart"), "API callers must see a lane-neutral fix");
    assertEquals(SpecStatus.REVIEW, stores[0].findById("auth").orElseThrow().status());
  }

  @Test
  void serverStartConstructorRestartsADoneSpecOntoItsPriorBranch() throws Exception {
    var yaml = tempDir.resolve("sail-" + System.nanoTime() + ".yaml");
    Files.writeString(yaml, branchYaml());
    var db = Sqlite.open(tempDir.resolve("specs-" + System.nanoTime() + ".db"));
    new SchemaManager(db).migrate();
    var store = new SpecStore(db);
    seedSpec(
        store, "auth", "Add auth", "done", List.of(), "Do auth", null, null, null, "sail/auth");
    var fdeStore = new FdeStore(db);
    fdeStore.add(LOCAL_HANDLE, null, null, "admin");
    var shell =
        shell()
            .on("incus list ^acme$", RUNNING_JSON)
            .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
            .on("mkdir -p /home/dev/workspace/specs", "")
            .on("printf '%s'", "")
            .on("mkdir -p /home/dev/.sail", "")
            .on("test -d /home/dev/workspace/app/.git", "")
            .on("rev-parse --verify --quiet refs/heads/sail/auth", "")
            .on("git -C /home/dev/workspace/app checkout -f sail/auth", "")
            .on("systemd-run --user", "")
            .on("claude", "");
    var operations =
        new SailOperations(
            shell,
            yaml.toString(),
            null,
            null,
            store,
            new ReviewStore(db),
            new RunStore(db),
            new ProjectStore(db),
            SyncScheduler.disabled(),
            fdeStore);

    var result =
        dispatch(operations, "acme", new DispatchRequest("auth", "background", false, null, true));

    assertEquals(true, get(result, "dispatched"));
    assertEquals(true, get(result, "restarted"));
    assertTrue(get(result, "spec").toString().contains("sail/auth"));
    assertEquals(SpecStatus.IN_PROGRESS, store.findById("auth").orElseThrow().status());
    assertTrue(
        shell.invocations().stream().anyMatch(command -> command.contains("checkout -f sail/auth")),
        "a restart force-checks out the prior branch instead of failing on the collision");
    assertFalse(
        shell.invocations().stream().anyMatch(command -> command.contains("checkout -b")),
        "a restart never creates a fresh branch when the prior one exists");
  }

  @Test
  void dispatchDryRunWithRestartClaimsTheSpecLikeAnyDispatchDryRun() throws Exception {
    var stores = new SpecStore[1];
    var operations =
        operationsWithStore(
            branchYaml(),
            idleShell()
                .on("test -d /home/dev/workspace/app/.git", "")
                .on("rev-parse --verify --quiet refs/heads/sail/auth", "")
                .on("git -C /home/dev/workspace/app checkout -f sail/auth", ""),
            store -> {
              stores[0] = store;
              seedSpec(
                  store,
                  "auth",
                  "Add auth",
                  "review",
                  List.of(),
                  "Do auth",
                  null,
                  null,
                  null,
                  "sail/auth");
            });

    var result =
        dispatch(operations, "acme", new DispatchRequest("auth", "background", true, null, true));

    assertEquals(true, get(result, "dispatched"));
    assertEquals(true, get(result, "restarted"));
    assertEquals(
        SpecStatus.IN_PROGRESS,
        stores[0].findById("auth").orElseThrow().status(),
        "a dry run claims the spec — dispatch's existing dry-run semantics, unchanged by restart");
  }

  @Test
  void dispatchOnAnUnconfiguredRepoIsACallerErrorNamingTheRepo() throws Exception {
    var operations =
        operationsWithStore(
            baseYaml(),
            shell().on("incus list ^acme$", RUNNING_JSON),
            SailOperationsTest::seedAuthBillingSetup);

    var error =
        dispatch(
            operations,
            "acme",
            new DispatchRequest("auth", "background", true, List.of("scim-sql")));

    assertError(ErrorCode.INVALID_REQUEST, error);
    assertTrue(fullError(error).contains("scim-sql"));
  }

  @Test
  void dispatchMapsLaunchFailure() throws Exception {
    var runs = new java.util.concurrent.atomic.AtomicReference<RunStore>();
    var operations =
        operationsWithStores(
            baseYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
                .on("mkdir -p /home/dev/workspace/specs", "")
                .on("printf '%s'", "")
                .on("-- mkdir -p /home/dev/.sail", "")
                .on("claude", new ShellExec.Result(1, "", "missing cli")),
            null,
            SailOperationsTest::seedAuthBillingSetup,
            runs::set);

    var error = dispatch(operations, "acme", request("auth"));

    assertError(ErrorCode.AGENT_LAUNCH_FAILED, error);
    var recorded = runs.get().listForProject("acme");
    assertEquals(1, recorded.size(), "a failed launch still leaves its run row, marked failed");
    assertEquals("failed", recorded.getFirst().status());
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

    dispatch(operations, "acme", request("auth"));

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

    var error = dispatch(operations, "acme", request("auth"));

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
            .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
            .on("agent.pid", "123")
            .on("kill -0 123", "")
            .on("agent-session.json", "{\"task\": \"work\"}")
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
          new SailOperations(shell, yaml.toString(), (cmd, log) -> 4242L, bus, null, store, null);

      dispatch(operations, "acme", request("auth"));

      BusTesting.awaitDelivery(latch);
      assertEquals(4242L, started.get().data().get(Event.WellKnownData.WATCHER_PID));
    } finally {
      db.close();
    }
  }

  private static RunStore.RunRow runRow(String runId, String unit) {
    return new RunStore.RunRow(
        runId,
        "acme",
        "auth",
        "node-a",
        "build",
        "claude-code",
        "feat/auth",
        "do it",
        123,
        null,
        "running",
        null,
        "/home/dev/.sail/runs/" + runId + "/agent.log",
        unit,
        "t0",
        null,
        java.util.List.of());
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

    var unit = operations.relaunchWatcher(runRow(R1, "sail-agent-" + R1)).orElseThrow();

    assertEquals(new WatcherSpawner.Unit("sail-watch-" + R1, "user", false), unit);
  }

  @Test
  void relaunchWatcherIsEmptyWhenNoSystemdScopeAccepts() throws Exception {
    var operations =
        operations(
            guardrailsYaml(),
            shell().on("incus list ^acme$", RUNNING_JSON),
            (command, logPath) -> 4242L);

    assertTrue(operations.relaunchWatcher(runRow(R1, "sail-agent-" + R1)).isEmpty());
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

    assertTrue(operations.relaunchWatcher(runRow(R1, "sail-agent-" + R1)).isEmpty());
  }

  @Test
  void dispatchStampsTheComputedBranchOnTheSpec() throws Exception {
    var stores = new SpecStore[1];
    var operations =
        operationsWithStore(
            branchYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing")),
            store -> {
              stores[0] = store;
              seedAuthBillingSetup(store);
            });

    dispatch(operations, "acme", request("auth", "background", true));

    assertEquals("sail/auth", stores[0].findById("auth").orElseThrow().branch());
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

    var result = dispatch(operations, "acme", request("auth", "background", true));

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

    var result = dispatch(operations, "acme", request("auth", "background", true));

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

    var result = dispatch(operations, "acme", request("auth", "background", true));

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

    var error = dispatch(operations, "acme", request("auth", "background", true));

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

    var result = dispatch(operations, "acme", request("auth", "background", true));

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

    var result = dispatch(operations, "acme", request("auth", "background", true));

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

    var result = dispatch(operations, "acme", request("auth", "background", true));

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

    var error = dispatch(operations, "acme", request("auth", "background", true));

    assertError(ErrorCode.SNAPSHOT_FAILED, error);
  }

  @Test
  void stopRunKillsARunningLocalAgent() throws Exception {
    var operations =
        opsWithRun(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/runs/" + R1 + "/agent.pid", "123")
                .onSequence(
                    "kill -0 123",
                    new ShellExec.Result(0, "", ""),
                    new ShellExec.Result(0, "", ""),
                    new ShellExec.Result(1, "", ""))
                .on(
                    "cat /home/dev/.sail/runs/" + R1 + "/agent-session.json",
                    "{\"task\": \"work\"}")
                .on("kill 123", "")
                .on("sleep 3", "")
                .on("kill -9 123", "")
                .on("rm -f /home/dev/.sail/runs/" + R1 + "/agent.pid", ""));

    var result = operations.stopRun(R1, "node-a", ADMIN);

    assertEquals(true, get(result, "stopped"));
    assertEquals(123, get(result, "pid"));
  }

  @Test
  void stopRunCancelsTheInProgressSpecBeforeKilling() throws Exception {
    var operations =
        operationsWithStores(
            baseYaml(),
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/runs/" + R1 + "/agent.pid", "123")
                .onSequence(
                    "kill -0 123",
                    new ShellExec.Result(0, "", ""),
                    new ShellExec.Result(0, "", ""),
                    new ShellExec.Result(1, "", ""))
                .on(
                    "cat /home/dev/.sail/runs/" + R1 + "/agent-session.json",
                    "{\"task\": \"work\"}")
                .on("kill 123", "")
                .on("sleep 3", "")
                .on("kill -9 123", "")
                .on("rm -f /home/dev/.sail/runs/" + R1 + "/agent.pid", ""),
            null,
            s -> seedAssigned(s, "auth", "in_progress", LOCAL_HANDLE),
            runs ->
                runs.create(
                    R1,
                    "acme",
                    "auth",
                    "node-a",
                    "build",
                    "claude-code",
                    "feat/auth",
                    "do it",
                    123,
                    null,
                    RUN_LOG,
                    "sail-agent-" + R1));

    var result = operations.stopRun(R1, "node-a", ADMIN);

    assertEquals(true, get(result, "stopped"));
    assertEquals(true, get(result, "spec_cancelled"));
  }

  @Test
  void stopRunMapsKillFailure() throws Exception {
    var operations =
        opsWithRun(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/runs/" + R1 + "/agent.pid", "123")
                .on("kill -0 123", "")
                .on(
                    "cat /home/dev/.sail/runs/" + R1 + "/agent-session.json",
                    "{\"task\": \"work\"}")
                .throwOn("kill 123", new IOException("permission denied")));

    assertError(ErrorCode.AGENT_STOP_FAILED, operations.stopRun(R1, "node-a", ADMIN));
  }

  @Test
  void agentReportReturnsSummary() throws Exception {
    var operations =
        operations(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing")));

    var result = operations.agentReport("acme", "node-a");

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

    var result = operations.agentReport("acme", "node-a");

    var specs = (List<Map<String, Object>>) get(result, "specs");
    assertEquals(1, specs.size());
    assertEquals("search", specs.getFirst().get("id"));
  }

  @Test
  void agentReportMapsReporterFailure() throws Exception {
    var operations =
        opsWithRun(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .throwOn("cat /home/dev/.sail/runs/" + R1 + "/agent.pid", new IOException("boom")));

    var error = operations.agentReport("acme", "node-a");

    assertError(ErrorCode.AGENT_REPORT_FAILED, error);
  }

  @Test
  void runLogFailureMapsToApiError() throws Exception {
    var operations =
        opsWithRun(
            shell()
                .on("incus list ^acme$", RUNNING_JSON)
                .on("tail -n 200 -- " + RUN_LOG, new ShellExec.Result(1, "", "permission denied")));

    assertError(ErrorCode.AGENT_LOG_FAILED, operations.runLog(R1, 200, "node-a", ADMIN));
  }

  @Test
  void missingDescriptorMapsToNotFound() {
    var operations = new SailOperations(shell(), tempDir.resolve("missail.yaml").toString());

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
      var operations = new SailOperations(shell(), baseYamlPath(tmp).toString(), bus, persister);
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
      var operations = new SailOperations(shell(), baseYamlPath(tmp).toString(), bus, persister);

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
      var operations = new SailOperations(shell(), baseYamlPath(tempDir).toString(), bus, null);

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

    var result = operations.restoreGlobalSpec("auth", new SpecRestoreRequest(rev), ADMIN);

    assertTrue(result.isSuccess());
    assertEquals(rev, get(result, "from_rev"));
  }

  @Test
  void reviewsForSpecIsEmptyWithoutReviews() throws Exception {
    var operations =
        operationsWithStores(
            baseYaml(), shell(), null, SailOperationsTest::seedAuthBillingSetup, s -> {});

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
    assertError(ErrorCode.NOT_FOUND, operations.approveReview("nope", ADMIN));
    assertError(ErrorCode.NOT_FOUND, operations.dismissFinding("nope", "f1", ADMIN));
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
        new SailOperations(
            shell(), yaml.toString(), null, null, null, specStore, reviewStore, null);

    assertTrue(operations.reviewDetail(reviewId).isSuccess());
    assertTrue(operations.dismissFinding(reviewId, findingId, ADMIN).isSuccess());
    assertTrue(operations.approveReview(reviewId, ADMIN).isSuccess());
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
            baseYaml(), shell, null, SailOperationsTest::seedAuthBillingSetup, s -> {});

    var result = dispatch(operations, "acme", request("auth", "foreground", false));

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
              snapshotYaml(), shell, bus, SailOperationsTest::seedAuthBillingSetup, s -> {});

      var result = dispatch(operations, "acme", request("auth", "background", true));

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
            SailOperationsTest::seedAuthBillingSetup,
            s -> {});

    assertError(
        ErrorCode.PROJECT_STOPPED,
        dispatch(operations, "acme", request("auth", "background", false)));
  }

  @Test
  void dispatchRejectsNotCreatedProject() throws Exception {
    var operations =
        operationsWithStores(
            baseYaml(),
            shell().on("incus list ^acme$", "[]"),
            null,
            SailOperationsTest::seedAuthBillingSetup,
            s -> {});

    assertError(
        ErrorCode.PROJECT_NOT_CREATED,
        dispatch(operations, "acme", request("auth", "background", false)));
  }

  @Test
  void dispatchRejectsErroredProject() throws Exception {
    var operations =
        operationsWithStores(
            baseYaml(),
            shell().on("incus list ^acme$", new ShellExec.Result(1, "", "boom")),
            null,
            SailOperationsTest::seedAuthBillingSetup,
            s -> {});

    assertError(
        ErrorCode.CONTAINER_ERROR,
        dispatch(operations, "acme", request("auth", "background", false)));
  }

  @Test
  void runsFailWithoutARunStore() throws Exception {
    var operations = operations(baseYaml(), shell());

    assertError(ErrorCode.INTERNAL, operations.runs("acme", null));
    assertError(ErrorCode.INTERNAL, operations.run(R1));
    assertError(ErrorCode.INTERNAL, operations.runLog(R1, 200, "node-a", ADMIN));
    assertError(ErrorCode.INTERNAL, operations.stopRun(R1, "node-a", ADMIN));
  }

  @Test
  void runLogReportsWhenTheRunHasNoLogFile() throws Exception {
    var operations =
        operationsWithStores(
            baseYaml(),
            shell().on("incus list ^acme$", RUNNING_JSON),
            null,
            s -> {},
            runs ->
                runs.create(
                    R1,
                    "acme",
                    "auth",
                    "node-a",
                    "build",
                    "claude-code",
                    null,
                    null,
                    null,
                    null,
                    null,
                    "sail-agent-" + R1));

    assertEquals(
        "This run has no log file.", get(operations.runLog(R1, 200, "node-a", ADMIN), "error"));
  }

  @Test
  void runsListRunsFromStore() throws Exception {
    var operations =
        operationsWithStores(
            baseYaml(),
            shell(),
            null,
            s -> {},
            runs ->
                runs.create(
                    R1,
                    "acme",
                    "auth",
                    "node-a",
                    "build",
                    "claude-code",
                    "feat/auth",
                    "do it",
                    123,
                    null,
                    RUN_LOG,
                    "sail-agent-" + R1));

    var result = operations.runs("acme", null);

    assertTrue(result.isSuccess());
    @SuppressWarnings("unchecked")
    var runs = (List<Map<String, Object>>) get(result, "runs");
    assertEquals(1, runs.size());
    assertEquals("node-a", runs.getFirst().get("node"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void globalSpecEmbedsTheLatestRunSummaryWithItsNode() throws Exception {
    var operations =
        operationsWithStores(
            baseYaml(),
            shell(),
            null,
            store -> seedSpec(store, "auth", "Add auth", "pending", List.of(), ""),
            runs ->
                runs.create(
                    R1,
                    "acme",
                    "auth",
                    "node-a",
                    "build",
                    "claude-code",
                    null,
                    null,
                    null,
                    null,
                    RUN_LOG,
                    "sail-agent-" + R1));

    var latest = (Map<String, Object>) get(operations.globalSpec("auth"), "latest_run");

    assertEquals(R1, latest.get("id"));
    assertEquals("node-a", latest.get("node"));
    assertEquals("running", latest.get("status"));
  }

  @Test
  void dispatchPublishesAgentSessionStartedWhenRunning() throws Exception {
    try (var bus = new EventBus()) {
      var shell =
          shell()
              .on("incus list ^acme$", RUNNING_JSON)
              .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"))
              .on("agent.pid", "123")
              .on("kill -0 123", "")
              .on("agent-session.json", "{\"task\": \"work\"}")
              .on("mkdir -p /home/dev/workspace/specs", "")
              .on("printf '%s'", "")
              .on("mkdir -p /home/dev/.sail", "")
              .on("bash -l -c", "");
      var operations =
          operationsWithStores(
              baseYaml(), shell, bus, SailOperationsTest::seedAuthBillingSetup, s -> {});

      var result = dispatch(operations, "acme", request("auth", "foreground", false));

      assertTrue(result.isSuccess());
      assertEquals(2L, bus.stats().published());
    }
  }

  @Test
  void eventBusStatsReflectsBusState(@TempDir Path tmp) throws Exception {
    try (var bus = new EventBus()) {
      var persister = new AuditPersister(tmp.resolve("events.jsonl"), 16);
      var operations = new SailOperations(shell(), baseYamlPath(tmp).toString(), bus, persister);
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

  private static String fullError(Result<?> result) {
    return String.valueOf(result.fullError());
  }

  private FakeShell idleShell() {
    return shell()
        .on("incus list ^acme$", RUNNING_JSON)
        .on("cat /home/dev/.sail/agent.pid", new ShellExec.Result(1, "", "missing"));
  }

  private static void seedAssigned(SpecStore store, String id, String status, String assignee) {
    store.create(
        new SpecStore.SpecRow(
            id,
            "acme",
            "Title " + id,
            SpecStatus.fromWire(status),
            assignee,
            null,
            null,
            null,
            null,
            0,
            "me",
            null,
            null,
            "me",
            List.of(),
            List.of()));
    store.setContent(id, "Do " + id, "");
  }

  private static final String LOCAL_HANDLE = "me";
  private static final Actor ADMIN = new Actor(LOCAL_HANDLE, Role.ADMIN, Actor.Lane.API);

  private static Result<DispatchResponse> dispatch(
      SailOperations operations, String project, DispatchRequest request) {
    return operations.dispatch(project, request, ADMIN, LOCAL_HANDLE);
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

  private SailOperations operations(String yamlContent, FakeShell shell) throws Exception {
    return operationsWithStore(yamlContent, shell, SailOperationsTest::seedAuthBillingSetup);
  }

  /** Builds operations backed by a migrated spec database seeded with {@code seed}. */
  private SailOperations operationsWithStore(
      String yamlContent, FakeShell shell, java.util.function.Consumer<SpecStore> seed)
      throws Exception {
    return operationsWithStore(yamlContent, shell, seed, null);
  }

  /** Builds operations backed by a full set of migrated stores (spec, review, session). */
  private SailOperations operationsWithStores(
      String yamlContent,
      FakeShell shell,
      EventBus bus,
      java.util.function.Consumer<SpecStore> seedSpecs,
      java.util.function.Consumer<RunStore> seedSessions)
      throws Exception {
    var yaml = tempDir.resolve("sail-" + System.nanoTime() + ".yaml");
    Files.writeString(yaml, yamlContent);
    var db = Sqlite.open(tempDir.resolve("specs-" + System.nanoTime() + ".db"));
    new SchemaManager(db).migrate();
    var specStore = new SpecStore(db);
    var reviewStore = new ReviewStore(db);
    var sessionStore = new RunStore(db);
    seedSpecs.accept(specStore);
    seedSessions.accept(sessionStore);
    return new SailOperations(
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
  private SailOperations operationsWith(
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
    return new SailOperations(
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

  private SailOperations operationsWithStore(
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
    return new SailOperations(shell, yaml.toString(), fallback, null, null, store, null);
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
            LOCAL_HANDLE,
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

  private SailOperations operations(
      String yamlContent, FakeShell shell, WatcherSpawner.ProcessSpawner watcherLauncher)
      throws Exception {
    return operationsWithStore(
        yamlContent, shell, SailOperationsTest::seedAuthBillingSetup, watcherLauncher);
  }

  private static String baseYaml() {
    return """
        name: acme
        ssh:
          user: dev
        agent:
          type: claude-code
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
          guardrails:
            max_duration: 4h
            action: stop
        """;
  }

  private SailOperations operations(FakeShell shell) throws Exception {
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
