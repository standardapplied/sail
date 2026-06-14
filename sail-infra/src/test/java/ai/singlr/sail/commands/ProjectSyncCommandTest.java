/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class ProjectSyncCommandTest {

  @Test
  void registeredUnderProject() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    cmd.execute("project", "--help");

    assertTrue(sw.toString().contains("sync"));
  }

  @Test
  void helpAdvertisesAllFlags() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exit = cmd.execute("project", "sync", "--help");

    assertEquals(0, exit);
    var help = sw.toString();
    assertTrue(help.contains("--all"));
    assertTrue(help.contains("--dry-run"));
    assertTrue(help.contains("--json"));
  }

  @Test
  void rejectsBareInvocationWithoutNameOrAll() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setErr(new PrintWriter(sw));

    var exit = cmd.execute("project", "sync");

    assertNotEquals(0, exit);
  }

  @Test
  void rejectsNameAndAllTogether() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setErr(new PrintWriter(sw));

    var exit = cmd.execute("project", "sync", "light-grid", "--all");

    assertNotEquals(0, exit);
  }
}
