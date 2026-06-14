/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class SpecListCommandTest {

  @Test
  void specCommandRegisteredInSing() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    cmd.execute("--help");

    assertTrue(sw.toString().contains("spec"));
  }

  @Test
  void specListHelpShowsDescription() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("spec", "list", "--help");

    assertEquals(0, exitCode);
    assertTrue(sw.toString().contains("List specs"));
  }

  @Test
  void specHelpListsStructuredSubcommands() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("spec", "--help");

    assertEquals(0, exitCode);
    var help = sw.toString();
    assertTrue(help.contains("list"));
    assertTrue(help.contains("show"));
    assertTrue(help.contains("create"));
    assertTrue(help.contains("edit"));
    assertTrue(help.contains("board"));
  }

  @Test
  void specListAcceptsJsonFlag() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("spec", "list", "--help");

    assertEquals(0, exitCode);
    assertTrue(sw.toString().contains("--json"));
  }

  @Test
  void specListAcceptsServerFlag() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("spec", "list", "--help");

    assertEquals(0, exitCode);
    assertTrue(sw.toString().contains("--server"));
  }

  @Test
  void specCreateHelpShowsStructuredOptions() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("spec", "create", "--help");

    assertEquals(0, exitCode);
    var help = sw.toString();
    assertTrue(help.contains("--title"));
    assertTrue(help.contains("--id"));
    assertTrue(help.contains("--depends-on"));
    assertTrue(help.contains("--json"));
  }

  @Test
  void specShowHelpShowsJsonFlag() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("spec", "show", "--help");

    assertEquals(0, exitCode);
    assertTrue(sw.toString().contains("--json"));
  }

  @Test
  void parseSnapshotTimeHandlesIso8601() {
    var instant = SnapsPruneCommand.parseSnapshotTime("2026-04-07T03:58:31.123456789Z");

    assertNotNull(instant);
    assertTrue(instant.isBefore(Instant.now()));
  }

  @Test
  void parseSnapshotTimeHandlesOffsetDateTime() {
    var instant = SnapsPruneCommand.parseSnapshotTime("2026-04-07T03:58:31+00:00");

    assertNotNull(instant);
  }

  @Test
  void parseSnapshotTimeReturnsNullForNull() {
    assertNull(SnapsPruneCommand.parseSnapshotTime(null));
  }

  @Test
  void parseSnapshotTimeReturnsNullForBlank() {
    assertNull(SnapsPruneCommand.parseSnapshotTime(""));
  }

  @Test
  void parseSnapshotTimeReturnsNullForGarbage() {
    assertNull(SnapsPruneCommand.parseSnapshotTime("not-a-date"));
  }
}
