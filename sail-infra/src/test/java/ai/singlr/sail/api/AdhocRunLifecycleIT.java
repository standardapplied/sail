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

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerFilePush;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The whole ad-hoc lifecycle against <strong>one real incus container</strong>: {@code startAdhoc}
 * reserves the container and launches the agent under its own {@code sail-agent-<runId>} unit, a
 * concurrent dispatch is refused while it is live, and the shared clean-stop procedure halts the
 * agent, verifies the kill, and releases the reservation — the run id addressing every step. A fake
 * {@code codex} binary stands in for the agent (sleeps) — no LLM, no API key. Runs only under the
 * {@code integration} profile against a real incus daemon; skips elsewhere.
 */
class AdhocRunLifecycleIT extends AbstractIncusIT {

  private static final String CONTAINER = "sail-it-adhoc-run";
  private static final String HANDLE = "it-node";
  private static final Actor OPERATOR = Actor.cliOperator(HANDLE);

  private static final String FAKE_AGENT =
      """
      #!/usr/bin/env bash
      sleep 30
      """;

  private Path stateDir;

  @BeforeEach
  void provision() throws Exception {
    ensureIncusOrSkip();
    launch(CONTAINER);
    var setup =
        exec(
            CONTAINER,
            List.of(
                "bash",
                "-c",
                "set -e;"
                    + " userdel -r ubuntu 2>/dev/null || true;"
                    + " id -u dev >/dev/null 2>&1 || useradd -m -u 1000 -s /bin/bash dev;"
                    + " mkdir -p /home/dev/.sail /home/dev/workspace;"
                    + " chown -R dev:dev /home/dev;"
                    + " for i in $(seq 1 30); do"
                    + " loginctl enable-linger dev 2>/dev/null && exit 0; sleep 1; done;"
                    + " loginctl enable-linger dev"));
    assertTrue(setup.ok(), "container provisioning failed: " + setup.stderr());
    awaitUserManager();
    ContainerFilePush.push(
        shell, CONTAINER, "/usr/local/bin/codex", FAKE_AGENT, List.of("--mode", "0755"));
    stateDir = Files.createTempDirectory("adhoc-run-it");
  }

  @AfterEach
  void cleanup() {
    deleteContainerQuietly(CONTAINER);
    if (stateDir != null) {
      deleteRecursively(stateDir);
    }
  }

  @Test
  void anAdhocSessionLivesStopsAndReleasesThroughItsRunIdentity() throws Exception {
    var yaml = stateDir.resolve("sail.yaml");
    Files.writeString(
        yaml,
        """
        name: %s
        ssh:
          user: dev
        repos:
          - url: https://example.invalid/app.git
            path: app
        agent:
          type: codex
        """
            .formatted(CONTAINER));
    try (var db = Sqlite.open(stateDir.resolve("it.db"))) {
      new SchemaManager(db).migrate();
      var specStore = new SpecStore(db);
      var runStore = new RunStore(db);
      new FdeStore(db).add(HANDLE, null, null, "admin");
      seedSpec(specStore, "spec-app", List.of("app"));
      var events = new CopyOnWriteArrayList<Event>();
      var dispatchOps =
          new DispatchOperations(
              shell,
              yaml.toString(),
              specStore,
              new ReviewStore(db),
              runStore,
              new FdeStore(db),
              events::add,
              new WatcherSpawner(refusingShell(), (command, logPath) -> 4242L),
              (project, config) -> "",
              DispatchOperations.shellLauncher(shell),
              DispatchOperations.Listener.NONE);

      var session =
          dispatchOps.startAdhoc(
              CONTAINER,
              new DispatchOperations.AdhocRequest("sleep for a while", null, null, true, false),
              HANDLE);

      var run = runStore.findById(session.runId()).orElseThrow();
      assertEquals("adhoc", run.role());
      assertEquals("", run.specId());
      assertEquals("sail-agent-" + session.runId(), run.unit());
      assertNotNull(run.pid(), "the launched agent's pid is stamped on the run");
      var live =
          new AgentSession(shell).queryStatus(CONTAINER, AgentUnit.recorded(run.id(), run.unit()));
      assertTrue(live != null && live.running(), "the ad-hoc agent runs under its own unit");

      var dispatchRefusal =
          assertThrows(
              ApiException.class,
              () ->
                  dispatchOps.dispatch(
                      CONTAINER,
                      new DispatchOperations.Request("spec-app", "background", false, null, false),
                      OPERATOR,
                      HANDLE));
      assertEquals(ErrorCode.AGENT_ALREADY_RUNNING, dispatchRefusal.failure().errorCode());
      assertEquals(
          SpecStatus.PENDING,
          specStore.findById("spec-app").orElseThrow().status(),
          "the refused dispatch claims nothing");

      var stopOps =
          new StopOperations(
              shell,
              yaml.toString(),
              specStore,
              runStore,
              events::add,
              StopOperations.sessionHalter(shell),
              StopOperations.Listener.NONE);
      var outcome =
          stopOps.stop(new StopOperations.ProjectTarget(CONTAINER), OPERATOR, HANDLE, false);

      var stopped = assertInstanceOf(StopOperations.Stopped.class, outcome);
      assertEquals(session.runId(), stopped.runId());
      assertEquals("stopped", runStore.findById(session.runId()).orElseThrow().status());
      var afterStop =
          new AgentSession(shell).queryStatus(CONTAINER, AgentUnit.recorded(run.id(), run.unit()));
      assertTrue(afterStop == null || !afterStop.running(), "the verified halt left no process");
      assertInstanceOf(
          DispatchOperations.Dispatched.class,
          dispatchOps.dispatch(
              CONTAINER,
              new DispatchOperations.Request("spec-app", "background", false, null, false),
              OPERATOR,
              HANDLE),
          "the released reservation admits the next dispatch");
    }
  }

  private static void seedSpec(SpecStore store, String id, List<String> repos) {
    store.create(
        new SpecStore.SpecRow(
            id,
            CONTAINER,
            "Title " + id,
            SpecStatus.PENDING,
            HANDLE,
            "codex",
            null,
            null,
            null,
            0,
            HANDLE,
            null,
            null,
            HANDLE,
            List.of(),
            repos));
    store.setContent(id, "Do " + id, "");
  }

  private static ai.singlr.sail.engine.ShellExec refusingShell() {
    return new ai.singlr.sail.engine.ShellExec() {
      @Override
      public Result exec(List<String> command) {
        return new Result(1, "", "refused");
      }

      @Override
      public Result exec(List<String> command, Path workDir, Duration timeout) {
        return new Result(1, "", "refused");
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }

  private void awaitUserManager() throws Exception {
    var deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (Instant.now().isBefore(deadline)) {
      var probe = exec(CONTAINER, List.of("test", "-S", "/run/user/1000/bus"));
      if (probe.ok()) {
        return;
      }
      Thread.sleep(Duration.ofSeconds(1));
    }
    throw new AssertionError("dev user's systemd manager (bus) never came up in " + CONTAINER);
  }
}
