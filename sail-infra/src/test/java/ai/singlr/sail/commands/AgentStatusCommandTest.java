/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class AgentStatusCommandTest {

  @Test
  void helpTextIncludesOptions() {
    var cmd = new CommandLine(new AgentStatusCommand());
    var usage = cmd.getUsageMessage();

    assertTrue(usage.contains("status"));
    assertTrue(usage.contains("--json"));
    assertTrue(usage.contains("--file"));
  }

  @Test
  void helpMentionsOptionalProjectName() {
    var cmd = new CommandLine(new AgentStatusCommand());
    var usage = cmd.getUsageMessage();

    assertTrue(usage.contains("Omit project name"));
  }

  @Test
  void acceptsNoArguments() {
    var cmd = new CommandLine(new AgentStatusCommand());
    var parseResult = cmd.parseArgs();

    assertNotNull(parseResult);
  }
}
