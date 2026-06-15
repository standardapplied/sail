/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class ProjectRepoCommandTest {

  @Test
  void groupsAdd() {
    var usage = new CommandLine(new ProjectRepoCommand()).getUsageMessage();
    assertTrue(usage.contains("add"));
  }

  @Test
  void printsUsageWithoutError() {
    assertEquals(0, new CommandLine(new ProjectRepoCommand()).execute());
  }
}
