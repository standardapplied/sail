/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.Actor;
import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.DispatchOperations;
import ai.singlr.sail.api.ErrorCode;
import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.SailOperations;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
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
 * The production-wiring freeze for the CLI lane (#142's regression pattern applied to the second
 * entry point): operations built exactly as {@code sail spec dispatch} builds them must record the
 * run, stamp the branch, enforce the roster guard, and leave rows the server's {@code /v1/runs}
 * route can answer from — no store silently dropped.
 */
class DispatchCommandWiringTest {

  private static final String HANDLE = "me";

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

  private Sqlite db;
  private String yaml;

  private DispatchOperations cliOperations(ShellExec shell, List<Event> events) throws Exception {
    var yamlPath = tempDir.resolve("sail.yaml");
    Files.writeString(yamlPath, YAML);
    yaml = yamlPath.toString();
    db = Sqlite.open(tempDir.resolve("control-plane.db"));
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
    return DispatchCommand.operations(
        db,
        shell,
        yaml,
        events::add,
        new WatcherSpawner(shell, (command, logPath) -> 4242L),
        (project, config) -> "",
        command -> 0,
        DispatchOperations.Listener.NONE);
  }

  private static StubShell shell() {
    return new StubShell()
        .on("incus list ^acme$", RUNNING_JSON)
        .on("mkdir -p /home/dev/.sail", "")
        .on("printf '%s'", "")
        .on("test -d /home/dev/workspace/app/.git", "")
        .on("git -C /home/dev/workspace/app checkout -b sail/auth", "");
  }

  private static DispatchOperations.Request request() {
    return new DispatchOperations.Request("auth", "background", false, null, false);
  }

  @Test
  void cliConstructionPathRecordsTheRunStampsTheBranchAndAnswersRuns() throws Exception {
    var events = new ArrayList<Event>();
    var operations = cliOperations(shell(), events);
    new FdeStore(db).add(HANDLE, null, null, "admin");

    var outcome = operations.dispatch("acme", request(), Actor.cliOperator(HANDLE), HANDLE);

    var dispatched = assertInstanceOf(DispatchOperations.Dispatched.class, outcome);
    assertEquals("sail/auth", dispatched.branch());

    var spec = new SpecStore(db).findById("auth").orElseThrow();
    assertEquals(SpecStatus.IN_PROGRESS, spec.status());
    assertEquals("sail/auth", spec.branch(), "the claim stamps the dispatch branch on the spec");

    var runs = new RunStore(db).listForProject("acme");
    assertEquals(1, runs.size(), "an in-process CLI dispatch records its run");
    assertEquals(HANDLE, runs.getFirst().node(), "the run carries this box's handle as provenance");
    assertEquals("sail/auth", runs.getFirst().branch());
    assertEquals("running", runs.getFirst().status());

    assertEquals(
        List.of(Event.WellKnownTypes.SPEC_DISPATCHED),
        events.stream().map(Event::type).toList(),
        "the CLI lane publishes the same lifecycle events as the server lane");

    var server =
        new SailOperations(
            shell(),
            yaml,
            null,
            null,
            new SpecStore(db),
            new ReviewStore(db),
            new RunStore(db),
            new ProjectStore(db),
            SyncScheduler.disabled(),
            new FdeStore(db));
    var served = server.runs("acme", null);
    assertTrue(
        served.isSuccess(), "/v1/runs answers from the rows a CLI-lane dispatch just recorded");
  }

  @Test
  void cliRestartWithoutASpecIsRefusedByTheSharedExecutor() throws Exception {
    var operations = cliOperations(shell(), new ArrayList<>());
    new FdeStore(db).add(HANDLE, null, null, "admin");
    var request = new DispatchOperations.Request(null, "background", false, null, true);

    var ex =
        assertThrows(
            ApiException.class,
            () -> operations.dispatch("acme", request, Actor.cliOperator(HANDLE), HANDLE));

    assertEquals(ErrorCode.INVALID_REQUEST, ex.failure().errorCode());
    assertTrue(ex.getMessage().contains("spec id"));
  }

  @Test
  void cliRestartRedispatchesAReviewSpecOntoItsPriorBranch() throws Exception {
    var events = new ArrayList<Event>();
    var operations =
        cliOperations(
            shell()
                .on("git -C /home/dev/workspace/app rev-parse --verify --quiet refs/heads/x", "")
                .on("git -C /home/dev/workspace/app checkout -f x", ""),
            events);
    new FdeStore(db).add(HANDLE, null, null, "admin");
    var specStore = new SpecStore(db);
    specStore.updateReposAndStatus("auth", List.of("app"), SpecStatus.REVIEW, "x");
    var request = new DispatchOperations.Request("auth", "background", false, null, true);

    var outcome = operations.dispatch("acme", request, Actor.cliOperator(HANDLE), HANDLE);

    var dispatched = assertInstanceOf(DispatchOperations.Dispatched.class, outcome);
    assertTrue(dispatched.restarted());
    assertEquals("x", dispatched.branch(), "a restart lands on the recorded prior branch");
    assertEquals(SpecStatus.IN_PROGRESS, specStore.findById("auth").orElseThrow().status());
    assertEquals(
        List.of(Event.WellKnownTypes.SPEC_RESTARTED, Event.WellKnownTypes.SPEC_DISPATCHED),
        events.stream().map(Event::type).toList());
    assertEquals(Map.of("note", "restarted from review"), events.getFirst().data());
  }

  @Test
  void cliConstructionPathEnforcesTheRosterGuard() throws Exception {
    var operations = cliOperations(shell(), new ArrayList<>());

    var ex =
        assertThrows(
            ApiException.class,
            () -> operations.dispatch("acme", request(), Actor.cliOperator(HANDLE), HANDLE));

    assertTrue(ex.getMessage().contains("roster"));
    assertEquals(
        SpecStatus.PENDING,
        new SpecStore(db).findById("auth").orElseThrow().status(),
        "a refused dispatch must not claim the spec");
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
