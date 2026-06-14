/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import picocli.CommandLine;

class ShellCommandTest {

  @org.junit.jupiter.api.Test
  void helpTextIncludesDescription() {
    var cmd = new CommandLine(new ShellCommand());
    var usage = cmd.getUsageMessage();

    assertTrue(usage.contains("interactive shell"));
    assertTrue(usage.contains("Project name"));
  }

  @org.junit.jupiter.api.Test
  void failsWithoutProjectName() {
    var cmd = new CommandLine(new ShellCommand());
    cmd.setErr(new PrintWriter(new StringWriter()));
    cmd.setOut(new PrintWriter(new StringWriter()));
    var exitCode = cmd.execute();

    assertNotEquals(0, exitCode);
  }
}
