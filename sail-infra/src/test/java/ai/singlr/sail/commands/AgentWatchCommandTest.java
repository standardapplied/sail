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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
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
