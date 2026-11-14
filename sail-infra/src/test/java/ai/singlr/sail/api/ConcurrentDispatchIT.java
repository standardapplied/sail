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
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WatcherSpawner;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Two specs on <strong>one repo</strong> dispatched one after the other on a real incus box with a
 * ZFS pool: both are admitted at once, each in its own workspace cloned from the project's template
 * (scenario 2, R5), and each launches under its own {@code sail-agent-<runId>} unit there. A fake
 * {@code codex} binary stands in for the agent (sleeps) — no LLM, no API key. The stop/review
 * lifecycle is covered deterministically by the unit suite and the multi-node fleet harness, not
 * here, so this IT stays free of agent-execution timing. Runs only under the {@code integration}
 * profile against a real incus daemon; skips elsewhere. What a create took is printed as a record,
 * not a gate.
 */
class ConcurrentDispatchIT extends AbstractIncusIT {

  private static final String PROJECT = "sail-it-concurrent-dispatch";
  private static final String HANDLE = "it-node";
  private static final Actor OPERATOR = new Actor(HANDLE, Role.ADMIN, Actor.Lane.CLI);

  private static final String FAKE_AGENT =
      """
      #!/usr/bin/env bash
      sleep 30
      """;

  private static final String YAML =
      """
      name: %s
      repos:
        - url: https://example.invalid/app.git
          path: app
      agent:
        type: codex
      """
          .formatted(PROJECT);

  private Path stateDir;

  @BeforeEach
  void provision() throws Exception {
    ensureIncusOrSkip();
    stateDir = Files.createTempDirectory("concurrent-dispatch-it");
    Files.writeString(stateDir.resolve("sail.yaml"), YAML);
    specBox(
        TestProjects.reading(stateDir.resolve("sail.yaml")).require(PROJECT),
        template ->
            ContainerFilePush.push(
                shell, template, "/usr/local/bin/codex", FAKE_AGENT, List.of("--mode", "0755")));
  }

  @AfterEach
  void cleanup() {
    removeSpecBox(PROJECT);
    if (stateDir != null) {
      deleteRecursively(stateDir);
    }
  }

  @Test
  void twoSpecsOnOneRepoRunAtOnceEachInItsOwnWorkspaceUnderItsOwnUnit() throws Exception {
    try (var db = Sqlite.open(stateDir.resolve("it.db"))) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, PROJECT);
      var specStore = new SpecStore(db);
      var reviewStore = new ReviewStore(db);
      var runStore = new RunStore(db);
      new FdeStore(db).add(HANDLE, null, null, "admin");
      TestLogins.store(db, HANDLE);
      seedSpec(specStore, "spec-a", List.of("app"));
      seedSpec(specStore, "spec-b", List.of("app"));
      var events = new CopyOnWriteArrayList<Event>();
      var dispatchOps =
          new DispatchOperations(
              shell,
              TestProjects.reading(stateDir.resolve("sail.yaml")),
              specStore,
              reviewStore,
              runStore,
              new FdeStore(db),
              events::add,
              new WatcherSpawner(refusingShell(), (command, logPath) -> 4242L),
              TestWorkspaces.over(shell),
              DispatchOperations.shellLauncher(shell),
              DispatchOperations.Listener.NONE,
              SessionYield.NONE);

      var first = dispatchBackground(dispatchOps, "spec-a");
      var second = dispatchBackground(dispatchOps, "spec-b");
      for (var dispatched : List.of(first, second)) {
        System.out.println(
            "  [it] workspace "
                + dispatched.workspace().instance()
                + " made in "
                + dispatched.workspace().timings().totalMs()
                + " ms: "
                + dispatched.workspace().timings());
      }

      var runs = runStore.listForProject(PROJECT);
      assertEquals(2, runs.size(), "both dispatches recorded their runs");
      assertTrue(
          runs.stream().allMatch(run -> "running".equals(run.status())),
          "the second dispatch was admitted while the first agent was still running");
      assertEquals(
          Set.of(workspaceOf(PROJECT, "spec-a"), workspaceOf(PROJECT, "spec-b")),
          Set.of(first.workspace().instance(), second.workspace().instance()));
      for (var dispatched : List.of(first, second)) {
        var unit = "sail-agent-" + dispatched.runId();
        var active =
            exec(
                dispatched.workspace().instance(),
                List.of(
                    "runuser",
                    "-u",
                    "dev",
                    "--",
                    "env",
                    "XDG_RUNTIME_DIR=/run/user/1000",
                    "systemctl",
                    "--user",
                    "is-active",
                    unit));
        assertEquals(
            "active",
            active.stdout().strip(),
            unit + " runs in " + dispatched.workspace().instance() + ": " + active.stderr());
        assertEquals(
            "agent/" + dispatched.taskSpec().id(),
            exec(
                    dispatched.workspace().instance(),
                    List.of(
                        "runuser",
                        "-u",
                        "dev",
                        "--",
                        "git",
                        "-C",
                        "/home/dev/workspace/app",
                        "rev-parse",
                        "--abbrev-ref",
                        "HEAD"))
                .stdout()
                .strip(),
            "each tree is on its own spec's branch");
      }
      assertNotEquals(
          runs.get(0).unit(), runs.get(1).unit(), "each run owns its own recorded unit");
    }
  }

  private DispatchOperations.Dispatched dispatchBackground(DispatchOperations ops, String specId) {
    var outcome =
        Acting.by(
            OPERATOR,
            () ->
                ops.dispatch(
                    PROJECT,
                    new DispatchOperations.Request(specId, "background", false, null, false),
                    HANDLE));
    return assertInstanceOf(DispatchOperations.Dispatched.class, outcome);
  }

  private static void seedSpec(SpecStore store, String id, List<String> repos) {
    Acting.as(
        HANDLE,
        () -> {
          store.create(
              new SpecStore.SpecRow(
                  id,
                  PROJECT,
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
        });
  }

  /**
   * A shell that refuses every command: the watcher spawner falls straight to its fake process
   * fallback instead of launching host-side systemd units the CI runner would have to clean up.
   */
  private static ShellExec refusingShell() {
    return new ShellExec() {
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
}
