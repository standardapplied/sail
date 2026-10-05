/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.QuietNarrator;
import ai.singlr.sail.api.RunWatch;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class AgentWatchCommandTest {

  private static final String RUN_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String STARTED_AT = "2026-10-04T12:00:00Z";

  @Test
  void helpTextIncludes() {
    var cmd = new CommandLine(new AgentWatchCommand());
    var usage = cmd.getUsageMessage();

    assertTrue(usage.contains("watch"));
    assertTrue(usage.contains("guardrails"));
    assertTrue(usage.contains("--run"));
    assertTrue(usage.contains("--unit"));
    assertTrue(usage.contains("--dry-run"));
    assertTrue(usage.contains("--json"));
    assertTrue(usage.contains("--host"));
    assertTrue(usage.contains("--port"));
    assertFalse(
        usage.contains("--interval"),
        "polling was removed; --interval should no longer be advertised");
  }

  @Test
  void refusesToStartWithoutARunId() {
    var cmd = new CommandLine(new AgentWatchCommand());
    var exitCode = cmd.execute("acme");

    assertNotEquals(0, exitCode);
  }

  @Test
  void parseStartedAtRoundtripsValidIso() {
    var iso = "2026-05-21T12:00:00Z";

    var parsed = AgentWatchCommand.parseStartedAt(iso);

    assertEquals(Instant.parse(iso), parsed);
  }

  @Test
  void aStartThatIsNotAnInstantIsRefusedNamingWhatToPassNeverGuessedAsNow() {
    for (var garbage : new String[] {null, "  ", "not-a-timestamp"}) {
      var refusal =
          assertThrows(
              IllegalArgumentException.class, () -> AgentWatchCommand.parseStartedAt(garbage));

      assertTrue(refusal.getMessage().contains("--started-at"), refusal.getMessage());
      assertTrue(refusal.getMessage().contains("the run row's started_at"), refusal.getMessage());
    }
  }

  /**
   * A container whose one agent runs until it is signalled, and whose session file — which the
   * agent can write — claims the run started in 2099.
   */
  private static ShellExec agentThatLiesAboutItsStart(AtomicBoolean signalled) {
    return new ShellExec() {
      @Override
      public Result exec(List<String> command) {
        var joined = String.join(" ", command);
        if (joined.contains("--signal=SIGTERM")) {
          signalled.set(true);
        }
        if (joined.contains("agent-session.json")) {
          return new Result(0, "started_at: \"2099-01-01T00:00:00Z\"\nrun_id: " + RUN_ID, "");
        }
        if (joined.contains("systemctl --user show")) {
          var state = signalled.get() ? "inactive" : "active";
          return new Result(0, "ActiveState=" + state + "\nExecMainStatus=0\n", "");
        }
        return new Result(0, "", "");
      }

      @Override
      public Result exec(List<String> command, Path workDir, Duration timeout) {
        return exec(command);
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }

  @Test
  void theWatchHoldsItsRunToTheStartItWasGivenWhateverTheAgentsSessionFileSays() throws Exception {
    var command = new AgentWatchCommand();
    new CommandLine(command)
        .parseArgs("acme", "--run", RUN_ID, "--started-at", STARTED_AT, "--max-duration", "45m");
    var start = Instant.parse(STARTED_AT);
    var time = new AtomicReference<>(start);
    var signalled = new AtomicBoolean();
    var stops = new ArrayList<Event>();
    var feed =
        new RunWatch.Feed() {
          @Override
          public Event poll(Duration wait) {
            if (Duration.between(start, time.get()).compareTo(Duration.ofHours(2)) > 0) {
              throw new AssertionError("the watch was still polling two hours in");
            }
            time.updateAndGet(now -> now.plus(wait));
            return null;
          }

          @Override
          public boolean live() {
            return true;
          }

          @Override
          public boolean reopen() {
            return true;
          }
        };

    command
        .watch(
            agentThatLiesAboutItsStart(signalled), feed, stops::add, new QuietNarrator(), time::get)
        .run();

    assertTrue(signalled.get(), "the run was ended for its limit");
    assertEquals("time limit (45m)", stops.getFirst().data().get(Event.WellKnownData.REASON));
    var held = Duration.between(start, time.get());
    assertTrue(
        held.compareTo(Duration.ofMinutes(45)) >= 0 && held.compareTo(Duration.ofMinutes(46)) < 0,
        "45 minutes from the start the spawner passed from the run row, not from the session"
            + " file's 2099: "
            + held);
  }

  private static AgentWatchCommand watching() {
    var command = new AgentWatchCommand();
    new CommandLine(command).parseArgs("acme", "--run", RUN_ID, "--started-at", STARTED_AT);
    return command;
  }

  @Test
  void aRunWhoseContainerGivesNoAnswerAboutItsAgentIsWatched() throws Exception {
    var silent = new ScriptedShellExecutor();
    var stops = new ArrayList<Event>();

    assertTrue(
        watching().watchable(silent, stops::add),
        "nothing says the agent is gone, so it may be at work: the watch starts and asks again");
    assertTrue(stops.isEmpty());
  }

  @Test
  void aRunWhoseAgentIsGoneHasTheStopItsUnitStillTellsOfPublishedAndIsNotWatched()
      throws Exception {
    var ended =
        new ScriptedShellExecutor()
            .onOk("printf gone", "gone")
            .onOk(
                "--property=ActiveState",
                """
                ActiveState=failed
                ExecMainStatus=3
                Environment=SAIL_SPEC_ID=auth SAIL_AGENT=codex SAIL_RUN_ID=%s
                """
                    .formatted(RUN_ID));
    var stops = new ArrayList<Event>();

    assertFalse(watching().watchable(ended, stops::add));

    assertEquals(1, stops.size(), "an agent that died at launch is told ended, with how");
    assertEquals(3, stops.getFirst().data().get(Event.WellKnownData.EXIT_CODE));
    assertEquals(RUN_ID, stops.getFirst().data().get(Event.WellKnownData.RUN_ID));
  }

  @Test
  void aRunWhoseAgentIsGoneAndWhoseUnitTellsNothingOfItWasNeverRunning() {
    var never =
        new ScriptedShellExecutor()
            .onOk("printf gone", "gone")
            .onOk("--property=ActiveState", "ActiveState=inactive\nExecMainStatus=0\n");
    var stops = new ArrayList<Event>();

    var refused =
        assertThrows(IllegalStateException.class, () -> watching().watchable(never, stops::add));

    assertTrue(refused.getMessage().contains("No agent session running"), refused.getMessage());
    assertTrue(stops.isEmpty());
  }

  @Test
  void refusesToStartWithoutTheRunRowsStart() {
    var cmd = new CommandLine(new AgentWatchCommand());
    var err = new StringWriter();
    cmd.setErr(new PrintWriter(err));

    var exitCode = cmd.execute("acme", "--run", RUN_ID);

    assertNotEquals(0, exitCode);
    assertTrue(err.toString().contains("--started-at"), err.toString());
  }

  @Test
  void aStartThatIsNotAnInstantIsRefusedBeforeAnythingIsWatched() {
    var cmd = new CommandLine(new AgentWatchCommand());
    var err = new StringWriter();
    cmd.setErr(new PrintWriter(err));

    var exitCode = cmd.execute("acme", "--run", RUN_ID, "--started-at", "yesterday");

    assertNotEquals(0, exitCode);
    assertTrue(err.toString().contains("'yesterday' is not an ISO-8601 instant"), err.toString());
  }

  @Test
  void theWatchersLimitsComeFromItsCommandLineAndAnInvalidOneIsRefusedNamingTheForms() {
    var cmd = new CommandLine(new AgentWatchCommand());
    var err = new StringWriter();
    cmd.setErr(new PrintWriter(err));

    var exitCode =
        cmd.execute(
            "acme", "--run", RUN_ID, "--started-at", STARTED_AT, "--max-duration", "45 minutes");

    assertNotEquals(0, exitCode);
    assertTrue(err.toString().contains("45 minutes"), err.toString());
    assertTrue(
        err.toString().contains("4h, 90m, 30s"), "the refusal names the accepted forms: " + err);
  }

  @Test
  void aLimitThatNeverRunsOutIsRefusedBeforeAnythingIsWatched() {
    var cmd = new CommandLine(new AgentWatchCommand());
    var err = new StringWriter();
    cmd.setErr(new PrintWriter(err));

    var exitCode =
        cmd.execute("acme", "--run", RUN_ID, "--started-at", STARTED_AT, "--max-idle", "0m");

    assertNotEquals(0, exitCode);
    assertTrue(err.toString().contains("greater than zero"), err.toString());
  }

  @Test
  void anActionSailDoesNotKnowIsRefusedNamingTheOnesItDoes() {
    var cmd = new CommandLine(new AgentWatchCommand());
    var err = new StringWriter();
    cmd.setErr(new PrintWriter(err));

    var exitCode =
        cmd.execute("acme", "--run", RUN_ID, "--started-at", STARTED_AT, "--action", "restart");

    assertNotEquals(0, exitCode);
    assertTrue(err.toString().contains("stop, snapshot-and-stop, notify"), err.toString());
  }
}
