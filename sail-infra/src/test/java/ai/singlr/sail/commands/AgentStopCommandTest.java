/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.api.StopOperations;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** M4: the run a stop names, as its options spell it. */
class AgentStopCommandTest {

  private static final String RUN = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

  private static AgentStopCommand parsed(String... args) {
    var command = new AgentStopCommand();
    new CommandLine(command).parseArgs(args);
    return command;
  }

  @Test
  void aRunIdNamesThatRunASpecItsRunAndNeitherTheProjectsOne() {
    assertEquals(new StopOperations.RunTarget(RUN, "acme"), parsed("acme", "--run", RUN).target());
    assertEquals(
        new StopOperations.ProjectTarget("acme", "auth"),
        parsed("acme", "--spec", "auth").target());
    assertEquals(new StopOperations.ProjectTarget("acme", null), parsed("acme").target());
  }

  @Test
  void aRunAndASpecAtOnceAreRefused() {
    var refused =
        assertThrows(
            CliCommand.Refused.class,
            () -> parsed("acme", "--run", RUN, "--spec", "auth").target());

    assertEquals("Name a run with --run or a spec with --spec, not both.", refused.getMessage());
  }
}
