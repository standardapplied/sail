/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class ProjectServiceCommandTest {

  @Test
  void groupsAddAndRemove() {
    var usage = new CommandLine(new ProjectServiceCommand()).getUsageMessage();
    assertTrue(usage.contains("add"));
    assertTrue(usage.contains("remove"));
  }

  @Test
  void printsUsageWithoutError() {
    assertEquals(0, new CommandLine(new ProjectServiceCommand()).execute());
  }
}
