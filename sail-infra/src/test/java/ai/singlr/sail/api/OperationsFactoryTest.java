/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.sync.SyncBox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationsFactoryTest {
  @TempDir Path tempDir;

  @Test
  void factoryPreservesDryRunLaunchesAndPublishesStopEventsOnTheServerBus() throws Exception {
    var descriptor = tempDir.resolve("sail.yaml");
    Files.writeString(descriptor, "name: proj\n");
    var shell =
        new ShellExec() {
          public Result exec(List<String> command) {
            return command.contains("list")
                ? new Result(0, "[{\"name\":\"proj\",\"status\":\"Running\",\"state\":{}}]", "")
                : new Result(1, "", "missing");
          }

          public Result exec(List<String> command, Path workDir, Duration timeout) {
            return exec(command);
          }

          public boolean isDryRun() {
            return false;
          }
        };
    try (var box = new SyncBox("node");
        var bus = new EventBus();
        var operations =
            OperationsFactory.create(
                box.db,
                shell,
                descriptor.toString(),
                bus,
                null,
                SyncScheduler.disabled(),
                SessionYield.NONE)) {
      var preview = new DispatchOperations.AdhocRequest("task", null, null, true, true);
      var plain = operations.startAdhoc("proj", preview, "node");
      var prepared =
          operations.startAdhoc(
              "proj",
              preview,
              "node",
              () -> {
                throw new AssertionError("a dry run must not prepare the workspace");
              });
      assertNotNull(plain.runId());
      assertNotNull(prepared.runId());
      assertTrue(operations.runningRuns("proj", "node").isEmpty());
      assertEquals(0, bus.publishedCount());
      box.specs.create(SyncBox.spec("auth", "Auth", "pending"));
      var runId = DateTimeUtils.newId().toString();
      var runs = new RunStore(box.db);
      runs.create(
          runId,
          "proj",
          "auth",
          "node",
          "node",
          "build",
          "codex",
          "agent/auth",
          "task",
          123,
          null,
          "/home/dev/.sail/runs/" + runId + "/agent.log",
          "sail-agent-" + runId);

      var outcome =
          operations.stop(
              new StopOperations.RunTarget(runId),
              new Actor("node", Role.ADMIN, Actor.Lane.API),
              "node",
              false);

      assertTrue(assertInstanceOf(StopOperations.NotRunning.class, outcome).runReleased());
      assertEquals("stopped", runs.findById(runId).orElseThrow().status());
      assertEquals(1, bus.publishedCount());
    }
  }

  @Test
  void hostFactoryUsesTheConfiguredControlPlaneAndOwnsItsConnection() throws Exception {
    var environment = environment();
    var previous = environment.put("SAIL_DATA_DIR", tempDir.toString());
    try {
      SailOperations closed;
      try (var operations = OperationsFactory.open()) {
        closed = operations;
        assertEquals(0, operations.schemaVersion(), "ordinary opens preserve bootstrap behavior");
        var migrated = operations.initialize();
        assertEquals(0, migrated.before());
        assertTrue(migrated.after() > 0);
        assertEquals(migrated.after(), operations.initialize().before());
        assertTrue(operations.conflicts().isEmpty());
        operations.createToken("test", "admin", null, null);
      }
      assertThrows(IllegalStateException.class, closed::schemaVersion);
      assertTrue(Files.isRegularFile(tempDir.resolve("sail.db")));
      try (var operations = OperationsFactory.open(tempDir.resolve("sail.db"))) {
        assertEquals("test", operations.tokens().getFirst().name());
      }
      var shell = new ShellExecutor(true);
      var hooks =
          new OperationHooks(
              event -> {},
              new WatcherSpawner(shell, WatcherSpawner::spawnProcess),
              DispatchOperations.autoSnapshotter(shell),
              DispatchOperations.shellLauncher(shell),
              DispatchOperations.Listener.NONE,
              StopOperations.Listener.NONE);
      try (var operations =
          OperationsFactory.open(shell, SailPaths.PROJECT_DESCRIPTOR, hooks, SessionYield.NONE)) {
        assertEquals("test", operations.tokens().getFirst().name());
        assertNotNull(operations.syncStatus());
      }
      assertThrows(
          NullPointerException.class,
          () ->
              OperationsFactory.open(shell, SailPaths.PROJECT_DESCRIPTOR, null, SessionYield.NONE));
    } finally {
      if (previous == null) environment.remove("SAIL_DATA_DIR");
      else environment.put("SAIL_DATA_DIR", previous);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> environment() throws Exception {
    var environment = System.getenv();
    var values = environment.getClass().getDeclaredField("m");
    values.setAccessible(true);
    return (Map<String, String>) values.get(environment);
  }
}
