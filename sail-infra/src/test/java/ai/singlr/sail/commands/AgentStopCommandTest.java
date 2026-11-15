/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.api.StopOperations;
import ai.singlr.sail.config.YamlUtil;
import java.util.Map;
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

  /**
   * Low 2: under {@code --json} a refused stop is JSON in the API's error envelope, its code in it.
   */
  @Test
  void aRefusedStopUnderJsonIsJsonWithItsCode() {
    var ran = SailCli.run(new AgentStopCommand(), "Not_A_Project!", "--json");

    assertEquals(1, ran.exit());
    var error = (Map<?, ?>) YamlUtil.parseMap(ran.out()).get("error");
    assertEquals("invalid_request", error.get("code"), ran.out());
    assertEquals("", ran.err(), "nothing but the JSON");
  }
}
