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

class ProjectRenameCommandTest {

  @Test
  void registeredUnderProject() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    cmd.execute("project", "--help");

    assertTrue(sw.toString().contains("rename"));
  }

  @Test
  void helpAdvertisesArgsAndFlags() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exit = cmd.execute("project", "rename", "--help");

    assertEquals(0, exit);
    var help = sw.toString();
    assertTrue(help.contains("--dry-run"));
    assertTrue(help.contains("--json"));
    assertTrue(help.toLowerCase().contains("local only"));
  }

  @Test
  void rejectsRenamingToTheSameName() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setErr(new PrintWriter(sw));

    var exit = cmd.execute("project", "rename", "web", "web");

    assertNotEquals(0, exit, "renaming a project to its own name is rejected before any work");
  }

  @Test
  void rejectsMissingNewName() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setErr(new PrintWriter(sw));

    var exit = cmd.execute("project", "rename", "web");

    assertNotEquals(0, exit);
  }
}
