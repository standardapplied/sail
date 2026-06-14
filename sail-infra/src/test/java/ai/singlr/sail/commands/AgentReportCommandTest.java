/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class AgentReportCommandTest {

  @Test
  void helpTextIncludesOptions() {
    var cmd = new CommandLine(new AgentReportCommand());
    var usage = cmd.getUsageMessage();

    assertTrue(usage.contains("report"));
    assertTrue(usage.contains("morning-after"));
    assertTrue(usage.contains("--json"));
    assertTrue(usage.contains("--file"));
  }

  @Test
  void requiresProjectName() {
    var cmd = new CommandLine(new AgentReportCommand());
    cmd.setErr(new java.io.PrintWriter(new java.io.StringWriter()));
    var exitCode = cmd.execute();

    assertNotEquals(0, exitCode);
  }
}
