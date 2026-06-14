/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Set;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class HostServiceCommandTest {

  @Test
  void serviceIsRegisteredUnderHost() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    cmd.execute("host", "--help");

    assertTrue(sw.toString().contains("service"));
  }

  @Test
  void serviceExposesAllSubcommands() {
    var service = new CommandLine(new HostServiceCommand());

    assertEquals(
        Set.of("install", "uninstall", "start", "stop", "restart", "status", "logs"),
        service.getSubcommands().keySet());
  }

  @Test
  void installHelpAdvertisesHostAndPortFlags() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exitCode = cmd.execute("host", "service", "install", "--help");

    assertEquals(0, exitCode);
    var help = sw.toString();
    assertTrue(help.contains("--host"));
    assertTrue(help.contains("--port"));
    assertTrue(help.contains("--dry-run"));
  }

  @Test
  void statusHelpAdvertisesJsonAndShowUnit() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    cmd.execute("host", "service", "status", "--help");

    var help = sw.toString();
    assertTrue(help.contains("--json"));
    assertTrue(help.contains("--show-unit"));
  }

  @Test
  void logsHelpAdvertisesLineCount() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    cmd.execute("host", "service", "logs", "--help");

    assertTrue(sw.toString().contains("-n"));
  }
}
