/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launch engine's post-launch tail — the defensive branches the four lane suites exercise the
 * happy path of, driven here directly: a queried status of {@code null}, a cancel that lost the
 * launch, a foreground completion, and a status query that fails.
 */
class RunLauncherTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private RunStore runStore;
  private final List<Event> events = new ArrayList<>();
  private static final String RUN_ID = DateTimeUtils.newId().toString();

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    runStore = new RunStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private static ShellExec shell(Function<List<String>, ShellExec.Result> handler) {
    return new ShellExec() {
      @Override
      public ShellExec.Result exec(List<String> command) throws IOException {
        return handler.apply(command);
      }

      @Override
      public ShellExec.Result exec(List<String> command, Path workDir, Duration timeout)
          throws IOException {
        return handler.apply(command);
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }

  private static ShellExec quietShell() {
    return shell(command -> new ShellExec.Result(1, "", ""));
  }

  private static ShellExec runningShell() {
    return shell(
        command -> {
          var joined = String.join(" ", command);
          if (joined.contains("cat") && joined.contains("pid")) {
            return new ShellExec.Result(0, "12345\n", "");
          }
          return new ShellExec.Result(0, "", "");
        });
  }

  private static ShellExec throwingShell() {
    return new ShellExec() {
      @Override
      public ShellExec.Result exec(List<String> command) throws IOException {
        throw new IOException("container gone");
      }

      @Override
      public ShellExec.Result exec(List<String> command, Path workDir, Duration timeout)
          throws IOException {
        throw new IOException("container gone");
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }

  private RunLauncher launcher(ShellExec shell, RunStore store) {
    return new RunLauncher(
        shell,
        "sail.yaml",
        command -> 0,
        DispatchOperations.Listener.NONE,
        null,
        store,
        events::add);
  }

  private static RunLauncher.RunContext ctx(boolean background) {
    return new RunLauncher.RunContext(
        "proj", AgentUnit.forRun(RUN_ID), RUN_ID, "spec", "codex", "build", background);
  }

  private static RunLauncher.LaunchOutcome outcome() {
    return new RunLauncher.LaunchOutcome(0, Optional.empty());
  }

  private void seedRunningRun() {
    runStore.create(
        RUN_ID, "proj", "spec", "node", "node", "build", "codex", "b", "t", null, null, "log",
        "unit");
  }

  @Test
  void finishLaunchIsANoOpBookkeepingWhenTheBoxKeepsNoRunStore() {
    var status = launcher(quietShell(), null).finishLaunch(ctx(false), outcome());
    assertNull(status, "an unreadable process reports no status");
  }

  @Test
  void aRunCancelledMidLaunchTearsDownAndConflicts() {
    var ex =
        assertThrows(
            ApiException.class,
            () -> launcher(quietShell(), runStore).finishLaunch(ctx(false), outcome()));
    assertEquals(
        ErrorCode.CONFLICT,
        ex.failure().errorCode(),
        "no running row to update means the cancel already claimed the run");
  }

  @Test
  void aForegroundRunIsCompletedWithItsExitCode() {
    seedRunningRun();
    launcher(quietShell(), runStore).finishLaunch(ctx(false), outcome());
    assertEquals("completed", runStore.findById(RUN_ID).orElseThrow().status());
  }

  @Test
  void aStatusQueryThatFailsSurfacesAsAgentStatusFailed() {
    var ex =
        assertThrows(
            ApiException.class,
            () -> launcher(throwingShell(), null).finishLaunch(ctx(false), outcome()));
    assertEquals(ErrorCode.AGENT_STATUS_FAILED, ex.failure().errorCode());
  }

  @Test
  void aLiveRunStampsItsProcessAndWatcherAndAnnouncesTheSession() {
    seedRunningRun();
    var launch = new RunLauncher.LaunchOutcome(0, Optional.of(new WatcherSpawner.Fallback(9999)));

    launcher(runningShell(), runStore).finishLaunch(ctx(true), launch);

    assertEquals(
        1,
        events.stream()
            .filter(e -> Event.WellKnownTypes.AGENT_SESSION_STARTED.equals(e.type()))
            .count());
    var row = runStore.findById(RUN_ID).orElseThrow();
    assertEquals(12345, row.pid());
    assertEquals(9999, row.watcherPid());
  }
}
