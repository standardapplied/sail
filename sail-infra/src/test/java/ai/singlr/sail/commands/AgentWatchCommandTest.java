/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.QuietNarrator;
import ai.singlr.sail.api.RunWatch;
import ai.singlr.sail.config.Guardrails;
import ai.singlr.sail.config.Notifications;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.ClosesWhatItOpens;
import ai.singlr.sail.engine.GuardrailChecker;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class AgentWatchCommandTest {

  private static final String RUN_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String STARTED_AT = "2026-10-04T12:00:00Z";

  @TempDir Path dir;

  @Test
  void theNotificationsAreTheCatalogRows() {
    var dbPath =
        seeded(
            "acme",
            "name: acme\nagent:\n  type: claude-code\n  notifications:\n"
                + "    url: https://203.0.113.1/acme\n    events: [guardrail_triggered]\n");
    var command = new AgentWatchCommand(() -> OperationsFactory.open(dbPath));
    new CommandLine(command)
        .parseArgs(
            "acme",
            "--run",
            RUN_ID,
            "--unit",
            "sail-agent-" + RUN_ID,
            "--started-at",
            STARTED_AT,
            "--action",
            "stop");

    assertEquals("https://203.0.113.1/acme", command.notifications().url());
  }

  @Test
  void aProjectWithNoRowFailsSayingSoAndOneWithNoNotificationsHasNone() {
    var dbPath = seeded("quiet", "name: quiet\nagent:\n  type: claude-code\n");
    var quiet = new AgentWatchCommand(() -> OperationsFactory.open(dbPath));
    new CommandLine(quiet).parseArgs("quiet", "--run", RUN_ID, "--started-at", STARTED_AT);
    var absent = new AgentWatchCommand(() -> OperationsFactory.open(dbPath));
    new CommandLine(absent).parseArgs("absent", "--run", RUN_ID, "--started-at", STARTED_AT);

    assertNull(quiet.notifications());
    var refused = assertThrows(ApiException.class, () -> absent.notifications());
    assertEquals("Project 'absent' is not in the catalog.", refused.getMessage());
  }

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

  /**
   * The report a watch names when its run ends reaches that run however many are live: by its spec
   * for a spec's run, by its id for one of no spec, as {@code agent report} reads it.
   */
  @Test
  void theReportAWatchNamesIsTheCommandThatReportsItsRun() {
    var spec = new AgentWatchCommand();
    new CommandLine(spec)
        .parseArgs("acme", "--run", RUN_ID, "--spec", "auth", "--started-at", STARTED_AT);

    for (var watched : List.of(watching(), spec)) {
      var report = watched.report();
      assertEquals("agent report", report.verb());
      var parsed = new AgentReportCommand();
      new CommandLine(parsed).parseArgs(report.args().toArray(String[]::new));
      assertEquals(report.args().getFirst(), "acme");
    }
    assertEquals(List.of("acme", "--run", RUN_ID), watching().report().args());
    assertEquals(List.of("acme", "--spec", "auth"), spec.report().args());

    var sent = new ArrayList<String>();
    spec.handleAgentExited((event, title, message) -> sent.add(event + " " + message));
    spec.notifySessionDone((event, title, message) -> sent.add(event + " " + message));
    assertEquals(
        List.of(
            "agent_session_stopped Agent process is no longer running. Run: sail agent report acme"
                + " --spec auth",
            "agent_session_completed Agent session ended. Run: sail agent report acme --spec auth"),
        sent,
        "the report each notification names is the one that reaches the run");
  }

  /**
   * A spec's run lives in its own workspace, which the watch asks about; one of no spec is refused
   * while its project's container is not running.
   */
  @Test
  void aWatchOfARunOfNoSpecNeedsItsProjectsContainerRunning() throws Exception {
    var stopped =
        new ScriptedShellExecutor()
            .onOk(
                "incus list ^acme$",
                "[{\"name\": \"acme\", \"status\": \"Stopped\", \"state\": {}}]");
    var spec = new AgentWatchCommand();
    new CommandLine(spec)
        .parseArgs("acme", "--run", RUN_ID, "--spec", "auth", "--started-at", STARTED_AT);

    spec.requireRunning(stopped);
    assertTrue(stopped.invocations().isEmpty(), "a spec's workspace is the watch's to ask later");
    var refused =
        assertThrows(IllegalStateException.class, () -> watching().requireRunning(stopped));
    assertTrue(refused.getMessage().contains("acme"), refused.getMessage());
    watching()
        .requireRunning(
            new ScriptedShellExecutor()
                .onOk(
                    "incus list ^acme$",
                    "[{\"name\": \"acme\", \"status\": \"Running\", \"state\": {}}]"));
  }

  /** The watch says each way its run ends, and sends each where the project's definition says. */
  @Test
  void theWatchSaysEachEndOfItsRun() {
    var out = System.out;
    var captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      var narrator = watching().narrator(null, null);
      narrator.tripped(
          new GuardrailChecker.GuardrailResult.Triggered("stall", "no output for 3s", "stop", "2s"),
          Duration.ofSeconds(3),
          "");
      narrator.exited();
      narrator.ended();
    } finally {
      System.setOut(out);
    }

    var said = captured.toString(StandardCharsets.UTF_8);
    assertTrue(said.contains("stall"), said);
    assertTrue(said.contains("Agent exited. Watch complete."), said);
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

  /**
   * A watch closes the notifier and the publisher it holds once it ends, however it ends: one whose
   * run never ran leaves neither client open.
   */
  @Test
  void aWatchClosesItsNotifierAndPublisherHoweverItEnds() throws Exception {
    var never =
        new ScriptedShellExecutor()
            .onOk("printf gone", "gone")
            .onOk("--property=ActiveState", "ActiveState=inactive\nExecMainStatus=0\n");
    var command = watching();
    System.setProperty("SAIL_SERVER", "http://127.0.0.1:9");
    System.setProperty("SAIL_TOKEN", "tok-watch");
    try {
      var before = ClosesWhatItOpens.baseline();

      assertThrows(
          IllegalStateException.class,
          () ->
              command.supervise(
                  never,
                  new Notifications("https://203.0.113.1/acme", List.of("guardrail_triggered")),
                  Guardrails.of(null, null, "stop"),
                  Instant.parse(STARTED_AT)));

      ClosesWhatItOpens.assertClosed("a watch whose run never ran", before);
    } finally {
      System.clearProperty("SAIL_SERVER");
      System.clearProperty("SAIL_TOKEN");
    }
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

    assertEquals(
        "No agent session running. Launch one with: sail agent run acme --background --task '...'",
        refused.getMessage());
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

  private Path seeded(String project, String definition) {
    var dbPath = dir.resolve("control-plane.db");
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      Acting.system(() -> new ProjectStore(db).upsert(TestDefinitions.yaml(definition)));
    }
    return dbPath;
  }
}
