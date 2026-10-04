/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class AgentWatchCommandTest {

  private static final String RUN_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

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
  void parseStartedAtFallsBackToNowOnNullOrBlank() {
    var before = Instant.now();
    var parsedNull = AgentWatchCommand.parseStartedAt(null);
    var parsedBlank = AgentWatchCommand.parseStartedAt("  ");
    var after = Instant.now();

    assertFalse(parsedNull.isBefore(before));
    assertFalse(parsedNull.isAfter(after));
    assertFalse(parsedBlank.isBefore(before));
    assertFalse(parsedBlank.isAfter(after));
  }

  @Test
  void parseStartedAtFallsBackToNowOnGarbage() {
    var parsed = AgentWatchCommand.parseStartedAt("not-a-timestamp");

    assertTrue(parsed.isAfter(Instant.EPOCH));
  }

  @Test
  void theWatchersLimitsComeFromItsCommandLineAndAnInvalidOneIsRefusedNamingTheForms() {
    var cmd = new CommandLine(new AgentWatchCommand());
    var err = new StringWriter();
    cmd.setErr(new PrintWriter(err));

    var exitCode = cmd.execute("acme", "--run", RUN_ID, "--max-duration", "45 minutes");

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

    var exitCode = cmd.execute("acme", "--run", RUN_ID, "--max-idle", "0m");

    assertNotEquals(0, exitCode);
    assertTrue(err.toString().contains("greater than zero"), err.toString());
  }

  @Test
  void anActionSailDoesNotKnowIsRefusedNamingTheOnesItDoes() {
    var cmd = new CommandLine(new AgentWatchCommand());
    var err = new StringWriter();
    cmd.setErr(new PrintWriter(err));

    var exitCode = cmd.execute("acme", "--run", RUN_ID, "--action", "restart");

    assertNotEquals(0, exitCode);
    assertTrue(err.toString().contains("stop, snapshot-and-stop, notify"), err.toString());
  }
}
