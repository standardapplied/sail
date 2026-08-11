/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.ShellExec;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launch-vs-cancel race: an operator stop that claims a run during launch preparation must win.
 * The launcher discovers the loss when its process stamp refuses to commit on the no-longer-
 * running row, tears down the agent it just started, and surfaces the conflict — it must never
 * overwrite the stop's terminal record or report a successful dispatch.
 */
class DispatchLaunchCancelRaceTest {

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
  private Sqlite db;

  @AfterEach
  void tearDown() {
    if (db != null) {
      db.close();
    }
  }

  @Test
  void aCancelDuringLaunchPreparationTearsDownTheAgentAndKeepsTheStopRecord() throws Exception {
    var yaml = tempDir.resolve("sail.yaml");
    Files.writeString(yaml, YAML);
    db = Sqlite.open(tempDir.resolve("race.db"));
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
    var runStore = new RunStore(db);
    var events = new CopyOnWriteArrayList<Event>();
    var cancelled = new AtomicReference<String>();
    var launchCredential = new AtomicReference<String>();
    var shell = new CancelDuringLaunchShell(cancelled);
    var ops =
        new DispatchOperations(
            shell,
            yaml.toString(),
            specStore,
            new ReviewStore(db),
            runStore,
            new FdeStore(db),
            events::add,
            new WatcherSpawner(shell, (command, logPath) -> 4242L),
            (project, config) -> "",
            command -> {
              launchCredential.set(
                  command.stream()
                      .filter(arg -> arg.startsWith("sailrun_"))
                      .findFirst()
                      .orElse(""));
              var run = runStore.running().getFirst();
              assertTrue(runStore.transition(run.id(), "running", "stopped"));
              cancelled.set(run.id());
              return 0;
            },
            DispatchOperations.Listener.NONE);

    var conflict =
        assertThrows(
            ApiException.class,
            () ->
                ops.dispatch(
                    "acme",
                    new DispatchOperations.Request("auth", "background", false, null, false),
                    ADMIN,
                    HANDLE));

    assertEquals(ErrorCode.CONFLICT, conflict.failure().errorCode());
    var run = runStore.findById(cancelled.get()).orElseThrow();
    assertEquals("stopped", run.status(), "the launcher must not overwrite the stop's record");
    assertNull(run.pid(), "a lost launch must not stamp its process identity");
    assertTrue(
        shell.commandsAfterCancel.stream().anyMatch(c -> c.contains(cancelled.get())),
        "the launcher must attempt to tear down the agent it started");
    assertTrue(
        events.stream().noneMatch(e -> "agent_session_started".equals(e.type())),
        "a cancelled launch must not announce a started session");
    assertTrue(
        launchCredential.get().startsWith("sailrun_"),
        "the launch command carries the run credential into the container env");
    assertTrue(
        runStore.findByCredential(launchCredential.get()).isEmpty(),
        "a launch lost to a cancel leaves no live credential behind");
  }

  /**
   * Answers the prepare-lane probes exactly as the parity fixture does and records every command
   * issued after the cancel landed, so the test can prove the launcher attempted to tear down the
   * agent it started. The cancel itself is injected at the launcher seam, not here.
   */
  private static final class CancelDuringLaunchShell implements ShellExec {
    private final AtomicReference<String> cancelled;
    private final List<String> commandsAfterCancel = new CopyOnWriteArrayList<>();

    CancelDuringLaunchShell(AtomicReference<String> cancelled) {
      this.cancelled = cancelled;
    }

    @Override
    public Result exec(List<String> command) {
      var joined = String.join(" ", command);
      if (cancelled.get() != null) {
        commandsAfterCancel.add(joined);
      }
      if (joined.contains("incus list ^acme$")) {
        return new Result(0, RUNNING_JSON, "");
      }
      if (joined.contains("cat " + ContainerSailSetup.STAMP_PATH)) {
        return new Result(0, ContainerSailSetup.fingerprint(), "");
      }
      if (joined.contains("mkdir -p /home/dev/.sail")
          || joined.contains("printf '%s'")
          || joined.contains("incus config device add")
          || joined.contains("test -d /home/dev/workspace/app/.git")
          || joined.contains("git -C /home/dev/workspace/app checkout -b sail/auth")
          || joined.contains("claude")) {
        return new Result(0, "", "");
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
