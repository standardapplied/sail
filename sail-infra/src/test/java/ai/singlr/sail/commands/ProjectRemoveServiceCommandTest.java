/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class ProjectRemoveServiceCommandTest {

  @Test
  void helpTextIncludesOptions() {
    var cmd = new CommandLine(new ProjectRemoveServiceCommand());
    var usage = cmd.getUsageMessage();

    assertTrue(usage.contains("--json"));
    assertTrue(usage.contains("--dry-run"));
    assertTrue(usage.contains("--file"));
    assertTrue(usage.contains("Remove an infrastructure service"));
  }

  @Test
  void requiresProjectNameAndServiceName() {
    var cmd = new CommandLine(new ProjectRemoveServiceCommand());
    cmd.setErr(new java.io.PrintWriter(new java.io.StringWriter()));
    var exitCode = cmd.execute();

    assertNotEquals(0, exitCode);
  }
}
