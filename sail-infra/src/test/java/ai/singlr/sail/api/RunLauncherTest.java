/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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
@ActingAs
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
    return runningShell(new ArrayList<>());
  }

  private static ShellExec runningShell(List<String> commands) {
    return shell(
        command -> {
          var joined = String.join(" ", command);
          commands.add(joined);
          if (joined.contains("cat") && joined.contains("pid")) {
            return new ShellExec.Result(0, "12345\n", "");
          }
          return new ShellExec.Result(0, "", "");
        });
  }

  /** The agent answers its first liveness probe and is gone at every one after. */
  private static ShellExec endingShell(List<String> commands) {
    var probes = new AtomicInteger();
    return shell(
        command -> {
          var joined = String.join(" ", command);
          commands.add(joined);
          if (joined.contains("cat") && joined.contains("pid")) {
            return new ShellExec.Result(0, "12345\n", "");
          }
          if (joined.contains("kill -0")) {
            return new ShellExec.Result(probes.getAndIncrement() == 0 ? 0 : 1, "", "");
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
        command -> 0,
        DispatchOperations.Listener.NONE,
        null,
        store,
        events::add,
        StageSkills::builtInOnly);
  }

  private static RunLauncher.RunContext ctx(boolean background) {
    return new RunLauncher.RunContext(
        "proj", AgentUnit.forRun(RUN_ID), RUN_ID, "spec", "codex", "build", background);
  }

  private static RunLauncher.LaunchOutcome outcome() {
    return new RunLauncher.LaunchOutcome(0, Optional.empty());
  }

  private void seedRunningRun() {
    Acting.system(
        () -> {
          runStore.create(
              RUN_ID, "proj", "spec", "node", "build", "codex", "b", "t", null, null, "log",
              "unit");
        });
  }

  @Test
  void finishLaunchIsANoOpBookkeepingWhenTheBoxKeepsNoRunStore() {
    var status = launcher(quietShell(), null).finishLaunch(ctx(false), outcome());
    assertNull(status, "an unreadable process reports no status");
  }

  @Test
  void aRunWhoseRowIsGoneMidLaunchTearsDownAndConflicts() {
    var ex =
        assertThrows(
            ApiException.class,
            () -> launcher(quietShell(), runStore).finishLaunch(ctx(false), outcome()));
    assertEquals(
        ErrorCode.CONFLICT,
        ex.failure().errorCode(),
        "no row to stamp means the run was taken from under its launch");
  }

  @Test
  void aRunAnOperatorStoppedMidLaunchTearsDownItsAgentAndConflicts() {
    seedRunningRun();
    Acting.system(() -> assertTrue(runStore.claimStop(RUN_ID, "stopped", () -> {})));
    var commands = new ArrayList<String>();

    var ex =
        assertThrows(
            ApiException.class,
            () -> launcher(runningShell(commands), runStore).finishLaunch(ctx(true), outcome()));

    assertEquals(ErrorCode.CONFLICT, ex.failure().errorCode());
    assertTrue(
        commands.stream().anyMatch(command -> command.contains("systemctl --user kill")),
        "the agent the cancelled launch started is torn down: " + commands);
    assertTrue(events.isEmpty());
  }

  @Test
  void aRunThatEndedBetweenItsStatusReadAndItsStampLaunchedAndEndedAndIsNotALostLaunch() {
    seedRunningRun();
    Acting.system(() -> assertTrue(runStore.transition(RUN_ID, "running", "stopped", 0)));
    var commands = new ArrayList<String>();

    var status = launcher(endingShell(commands), runStore).finishLaunch(ctx(true), outcome());

    assertTrue(
        status.running(),
        "the status was read while the agent still ran; its own stop then finished the row, which"
            + " is not a cancel to tear down and refuse");
    assertTrue(
        commands.stream().noneMatch(command -> command.contains("systemctl --user kill")),
        "nothing is torn down: " + commands);
    assertTrue(events.isEmpty(), "no session is announced for a run that is already over");
  }

  @Test
  void aRunFinishedUnderItsLaunchWhoseAgentIsStillRunningIsTornDownAndConflicts() {
    seedRunningRun();
    Acting.system(() -> assertTrue(runStore.transition(RUN_ID, "running", "stopped")));
    var commands = new ArrayList<String>();

    var ex =
        assertThrows(
            ApiException.class,
            () -> launcher(runningShell(commands), runStore).finishLaunch(ctx(true), outcome()));

    assertEquals(ErrorCode.CONFLICT, ex.failure().errorCode());
    assertTrue(ex.getMessage().contains("was ended while its launch was preparing"));
    assertTrue(
        commands.stream().anyMatch(command -> command.contains("systemctl --user kill")),
        "an agent still running against a row that says it is over did not end itself, and must"
            + " not run on unrecorded with its reservation freed: "
            + commands);
  }

  /**
   * A live agent whose unit answers {@code show} as {@code unitState} says, and whose kill is
   * delivered or not.
   */
  private static ShellExec haltedShell(String unitState, boolean signalDelivered) {
    return shell(
        command -> {
          var joined = String.join(" ", command);
          if (joined.contains("cat") && joined.contains("pid")) {
            return new ShellExec.Result(0, "12345\n", "");
          }
          if (joined.contains("systemctl --user kill")) {
            return new ShellExec.Result(signalDelivered ? 0 : 1, "", "");
          }
          if (joined.contains("systemctl --user show")) {
            return new ShellExec.Result(0, "ActiveState=" + unitState + "\n", "");
          }
          return new ShellExec.Result(0, "", "");
        });
  }

  private ApiException lostLaunch(ShellExec shell) {
    seedRunningRun();
    Acting.system(() -> assertTrue(runStore.transition(RUN_ID, "running", "stopped")));
    return assertThrows(
        ApiException.class, () -> launcher(shell, runStore).finishLaunch(ctx(true), outcome()));
  }

  @Test
  void aLostLaunchWhoseAgentTheContainerSaysEndedIsSaidTornDown() {
    var ex = lostLaunch(haltedShell("inactive", true));

    assertEquals(ErrorCode.CONFLICT, ex.failure().errorCode());
    assertTrue(ex.getMessage().contains("the agent was torn down"), ex.getMessage());
  }

  @Test
  void aLostLaunchWhoseAgentSurvivedItsHaltIsNeverSaidTornDown() {
    var ex = lostLaunch(haltedShell("active", true));

    assertEquals(ErrorCode.CONFLICT, ex.failure().errorCode());
    assertTrue(ex.getMessage().contains("its agent survived its halt"), ex.getMessage());
    assertTrue(
        ex.failure().action().contains(AgentUnit.forRun(RUN_ID).unitName()),
        "the operator is told which unit is still to be stopped: " + ex.failure().action());
  }

  @Test
  void aLostLaunchWhoseHaltNothingAnsweredForIsNeverSaidTornDown() {
    var ex = lostLaunch(haltedShell("active", false));

    assertEquals(ErrorCode.CONFLICT, ex.failure().errorCode());
    assertTrue(ex.getMessage().contains("could not be confirmed stopped"), ex.getMessage());
  }

  @Test
  void aLostLaunchWhoseHaltThrewIsNeverSaidTornDown() {
    var live = haltedShell("active", true);
    var ex =
        lostLaunch(
            shell(
                command -> {
                  if (String.join(" ", command).contains("systemctl --user kill")) {
                    throw new UncheckedIOException(new IOException("incus exec failed"));
                  }
                  try {
                    return live.exec(command);
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                }));

    assertEquals(ErrorCode.CONFLICT, ex.failure().errorCode());
    assertTrue(ex.getMessage().contains("could not be confirmed stopped"), ex.getMessage());
  }

  @Test
  void aRunFinishedUnderItsLaunchInAContainerThatGivesNoAnswerIsNotTakenForOneThatEndedItself() {
    seedRunningRun();
    Acting.system(() -> assertTrue(runStore.transition(RUN_ID, "running", "stopped", 0)));

    var ex =
        assertThrows(
            ApiException.class,
            () -> launcher(quietShell(), runStore).finishLaunch(ctx(true), outcome()));

    assertEquals(
        ErrorCode.CONFLICT,
        ex.failure().errorCode(),
        "nothing says the agent is gone, so the launch is not reported as one that launched and"
            + " ended");
    assertTrue(ex.getMessage().contains("could not be confirmed stopped"), ex.getMessage());
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
