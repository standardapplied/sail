/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.ConnectEnvironment;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lane-parity freeze: a CLI-constructed {@link DispatchOperations} and the server's {@link
 * SailOperations} dispatching the same seeded spec must produce the same spec row, the same run
 * row, and the same event sequence — one executor, two thin callers.
 */
class DispatchLaneParityTest {

  private static final String HANDLE = "me";
  private static final Actor ADMIN = Actor.cliOperator(HANDLE);

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
        auto_branch: true
        branch_prefix: sail/
      """;

  @TempDir Path tempDir;

  private record Lane(
      SpecStore specStore, RunStore runStore, List<Event> events, Sqlite db, String yaml) {}

  private Lane lane(String name) throws IOException {
    var yaml = tempDir.resolve(name + "-sail.yaml");
    Files.writeString(yaml, YAML);
    var db = Sqlite.open(tempDir.resolve(name + ".db"));
    new SchemaManager(db).migrate();
    var specStore = new SpecStore(db);
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
            "me",
            null,
            null,
            "me",
            List.of(),
            List.of()));
    specStore.setContent("auth", "Do auth", "");
    new FdeStore(db).add(HANDLE, null, null, "admin");
    return new Lane(specStore, new RunStore(db), new CopyOnWriteArrayList<>(), db, yaml.toString());
  }

  private static StubShell shell() {
    return new StubShell()
        .on("incus list ^acme$", RUNNING_JSON)
        .on("mkdir -p /home/dev/.sail", "")
        .on("printf '%s'", "")
        .on("test -d /home/dev/workspace/app/.git", "")
        .on("git -C /home/dev/workspace/app checkout -b sail/auth", "")
        .on("claude", "");
  }

  @Test
  void bothLanesProduceTheSameSpecRowRunRowsAndEventSequence() throws Exception {
    var cli = lane("cli");
    var cliOps =
        new DispatchOperations(
            shell(),
            cli.yaml(),
            cli.specStore(),
            new ReviewStore(cli.db()),
            cli.runStore(),
            new FdeStore(cli.db()),
            cli.events()::add,
            new WatcherSpawner(shell(), (command, logPath) -> 4242L),
            (project, config) -> "",
            command -> 0,
            DispatchOperations.Listener.NONE);
    var cliOutcome =
        cliOps.dispatch(
            "acme",
            new DispatchOperations.Request("auth", "background", false, null, false),
            ADMIN,
            HANDLE);
    assertInstanceOf(DispatchOperations.Dispatched.class, cliOutcome);
    completeLatestRun(cli);
    cli.specStore().updateStatus("auth", SpecStatus.REVIEW);
    var cliRestart =
        cliOps.dispatch(
            "acme",
            new DispatchOperations.Request("auth", "background", false, null, true),
            ADMIN,
            HANDLE);
    assertTrue(
        assertInstanceOf(DispatchOperations.Dispatched.class, cliRestart).restarted(),
        "the CLI lane reports the re-dispatch as a restart");

    var api = lane("api");
    try (var bus = new EventBus()) {
      var delivered = new CountDownLatch(2);
      bus.subscribe(
          new EventSubscriber() {
            @Override
            public String name() {
              return "capture";
            }

            @Override
            public Predicate<Event> filter() {
              return EventSubscriber.all();
            }

            @Override
            public void onEvent(Event event) {
              api.events().add(event);
              if (Event.WellKnownTypes.SPEC_DISPATCHED.equals(event.type())) {
                delivered.countDown();
              }
            }
          });
      var apiOps =
          new SailOperations(
              shell(),
              api.yaml(),
              (command, logPath) -> 4242L,
              bus,
              null,
              api.specStore(),
              new ReviewStore(api.db()),
              api.runStore(),
              null,
              ConnectEnvironment::detect,
              SyncScheduler.disabled(),
              new FdeStore(api.db()));

      var result =
          apiOps.dispatch(
              "acme", new DispatchRequest("auth", "background", false, null), ADMIN, HANDLE);
      assertTrue(result.isSuccess(), () -> String.valueOf(result.fullError()));

      completeLatestRun(api);
      api.specStore().updateStatus("auth", SpecStatus.REVIEW);
      var restartResult =
          apiOps.dispatch(
              "acme", new DispatchRequest("auth", "background", false, null, true), ADMIN, HANDLE);
      assertTrue(restartResult.isSuccess(), () -> String.valueOf(restartResult.fullError()));
      assertTrue(
          restartResult.orThrow().restarted(), "the API lane reports the re-dispatch as a restart");
      assertTrue(
          delivered.await(10, TimeUnit.SECONDS), "both spec_dispatched events must reach the bus");
    }

    assertEquals(specRow(cli), specRow(api), "one claim, one branch stamp, on either lane");
    assertEquals(runRows(cli), runRows(api), "one run recorder, on either lane");
    assertEquals(eventShapes(cli), eventShapes(api), "one event sequence, on either lane");
    assertTrue(
        eventShapes(cli).stream()
            .anyMatch(
                shape ->
                    Event.WellKnownTypes.SPEC_RESTARTED.equals(shape.get("type"))
                        && Map.of("note", "restarted from review").equals(shape.get("data"))),
        "the restart round records its lifecycle event with the note payload");
  }

  private static void completeLatestRun(Lane lane) {
    var run = lane.runStore().listForProject("acme").getFirst();
    lane.runStore().complete(run.id(), "stopped", 0);
  }

  private static Map<String, Object> specRow(Lane lane) {
    var row = lane.specStore().findById("auth").orElseThrow();
    var shape = new LinkedHashMap<String, Object>();
    shape.put("status", row.status());
    shape.put("branch", row.branch());
    shape.put("repos", row.repos());
    return shape;
  }

  private static List<Map<String, Object>> runRows(Lane lane) {
    var runs = lane.runStore().listForProject("acme");
    assertEquals(2, runs.size(), "each lane records one run per dispatch");
    return runs.stream().map(DispatchLaneParityTest::runShape).toList();
  }

  private static Map<String, Object> runShape(RunStore.RunRow run) {
    var shape = new LinkedHashMap<String, Object>();
    shape.put("project", run.project());
    shape.put("spec", run.specId());
    shape.put("node", run.node());
    shape.put("role", run.role());
    shape.put("agent", run.agent());
    shape.put("branch", run.branch());
    shape.put("status", run.status());
    shape.put("task", run.task());
    shape.put("pid", run.pid());
    shape.put("watcher_pid", run.watcherPid());
    shape.put("exit_code", run.exitCode());
    return shape;
  }

  private static List<Map<String, Object>> eventShapes(Lane lane) {
    return lane.events().stream()
        .map(
            event ->
                Map.<String, Object>of(
                    "type", event.type(),
                    "project", event.project(),
                    "spec", event.spec(),
                    "data", event.data()))
        .toList();
  }

  private static final class StubShell implements ShellExec {
    private final Map<String, Result> scripts = new LinkedHashMap<>();

    /** Every launch reconciles the in-container sail helpers; answer as already installed. */
    StubShell() {
      on("incus config device add", "");
      on("grep -qsF", "");
    }

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
