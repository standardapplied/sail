/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AbstractIncusIT;
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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Two specs on disjoint repos dispatched into <strong>one real incus container</strong>: both are
 * admitted concurrently — the repo-overlap gate does not refuse the second — and each launches
 * under its own {@code sail-agent-<runId>} unit and run-scoped files, so the two agents coexist
 * without colliding on a unit or session file. A fake {@code codex} binary stands in for the agent
 * (sleeps) — no LLM, no API key. The stop/review lifecycle is covered deterministically by the unit
 * suite and the multi-node fleet harness, not here, so this IT stays free of agent-execution
 * timing. Runs only under the {@code integration} profile against a real incus daemon; skips
 * elsewhere.
 */
class ConcurrentDispatchIT extends AbstractIncusIT {

  private static final String CONTAINER = "sail-it-concurrent-dispatch";
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
    launchPrepared(CONTAINER);
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
    stateDir = Files.createTempDirectory("concurrent-dispatch-it");
  }

  @AfterEach
  void cleanup() {
    deleteContainerQuietly(CONTAINER);
    if (stateDir != null) {
      deleteRecursively(stateDir);
    }
  }

  @Test
  void twoDisjointRepoSpecsAreAdmittedConcurrentlyUnderDistinctUnits() throws Exception {
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
          - url: https://example.invalid/web.git
            path: web
        agent:
          type: codex
        """
            .formatted(CONTAINER));
    try (var db = Sqlite.open(stateDir.resolve("it.db"))) {
      new SchemaManager(db).migrate();
      var specStore = new SpecStore(db);
      var reviewStore = new ReviewStore(db);
      var runStore = new RunStore(db);
      new FdeStore(db).add(HANDLE, null, null, "admin");
      seedSpec(specStore, "spec-app", List.of("app"));
      seedSpec(specStore, "spec-web", List.of("web"));
      var events = new CopyOnWriteArrayList<Event>();
      var dispatchOps =
          new DispatchOperations(
              shell,
              yaml.toString(),
              specStore,
              reviewStore,
              runStore,
              new FdeStore(db),
              events::add,
              new WatcherSpawner(refusingShell(), (command, logPath) -> 4242L),
              (project, config) -> "",
              DispatchOperations.shellLauncher(shell),
              DispatchOperations.Listener.NONE);

      var first = dispatchBackground(dispatchOps, "spec-app");
      var second = dispatchBackground(dispatchOps, "spec-web");

      var runs = runStore.listForProject(CONTAINER);
      assertEquals(2, runs.size(), "both dispatches recorded their runs");
      assertTrue(
          runs.stream().allMatch(run -> "running".equals(run.status())),
          "the second dispatch was admitted while the first agent was still running");
      assertNotEquals(
          runs.get(0).unit(), runs.get(1).unit(), "each run owns its own recorded unit");
      assertEquals(
          Set.copyOf(List.of("sail-agent-" + first.runId(), "sail-agent-" + second.runId())),
          Set.copyOf(List.of(runs.get(0).unit(), runs.get(1).unit())));
    }
  }

  private DispatchOperations.Dispatched dispatchBackground(DispatchOperations ops, String specId) {
    var outcome =
        ops.dispatch(
            CONTAINER,
            new DispatchOperations.Request(specId, "background", false, null, false),
            OPERATOR,
            HANDLE);
    return assertInstanceOf(DispatchOperations.Dispatched.class, outcome);
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

  /**
   * A shell that refuses every command: the watcher spawner falls straight to its fake process
   * fallback instead of launching host-side systemd units the CI runner would have to clean up.
   */
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
