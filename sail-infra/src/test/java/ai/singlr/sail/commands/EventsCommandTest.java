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

class EventsCommandTest {

  @Test
  void registeredAsRootSubcommand() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    cmd.execute("--help");

    assertTrue(sw.toString().contains("events"));
  }

  @Test
  void helpAdvertisesKeyFlags() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exit = cmd.execute("events", "--help");

    assertEquals(0, exit);
    var help = sw.toString();
    assertTrue(help.contains("--follow"));
    assertTrue(help.contains("--lines"));
    assertTrue(help.contains("--project"));
    assertTrue(help.contains("--type"));
    assertTrue(help.contains("--json"));
    assertTrue(help.contains("--host"));
    assertTrue(help.contains("--port"));
  }

  @Test
  void rejectsNonPositiveLines() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setErr(new PrintWriter(sw));

    var exit = cmd.execute("events", "-n", "0", "--port", "65000");

    assertNotEquals(0, exit);
  }
}
