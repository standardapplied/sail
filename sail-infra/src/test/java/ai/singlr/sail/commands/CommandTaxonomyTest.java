/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.*;

import ai.singlr.sail.Sail;
import java.util.Set;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class CommandTaxonomyTest {

  @Test
  void rootCommandsAreWorkflowGroups() {
    var command = new CommandLine(new Sail());

    assertEquals(
        Set.of("init", "host", "project", "server", "spec", "agent", "api", "events", "upgrade"),
        command.getSubcommands().keySet());
  }

  @Test
  void projectCommandsOwnProjectLifecycleAndContainerOperations() {
    var project = new CommandLine(new ProjectCommand());

    assertTrue(
        project
            .getSubcommands()
            .keySet()
            .containsAll(
                Set.of(
                    "init",
                    "create",
                    "apply",
                    "start",
                    "stop",
                    "restart",
                    "list",
                    "containers",
                    "config",
                    "logs",
                    "exec",
                    "shell",
                    "connect",
                    "snapshot",
                    "resources")));
  }

  @Test
  void projectSnapshotCommandsAreNestedUnderSnapshot() {
    var snapshot = new CommandLine(new ProjectSnapshotCommand());

    assertEquals(
        Set.of("create", "list", "restore", "delete", "rm", "prune"),
        snapshot.getSubcommands().keySet());
  }

  @Test
  void specCommandsIncludeDispatch() {
    var spec = new CommandLine(new SpecCommand());

    assertEquals(
        Set.of("list", "show", "create", "edit", "content", "delete", "board", "dispatch"),
        spec.getSubcommands().keySet());
  }

  @Test
  void agentCommandsExposeClearLifecycle() {
    var agent = new CommandLine(new AgentCommand());

    assertTrue(
        agent
            .getSubcommands()
            .keySet()
            .containsAll(
                Set.of(
                    "start", "run", "status", "stop", "logs", "report", "review", "audit", "sweep",
                    "context", "watch")));
  }

  @Test
  void projectContainersKeepsShortExpertAlias() {
    var project = new CommandLine(new ProjectCommand());

    assertSame(project.getSubcommands().get("containers"), project.getSubcommands().get("ps"));
  }

  @Test
  void agentStartAndLogsKeepExpertAliases() {
    var agent = new CommandLine(new AgentCommand());

    assertSame(agent.getSubcommands().get("start"), agent.getSubcommands().get("launch"));
    assertSame(agent.getSubcommands().get("logs"), agent.getSubcommands().get("log"));
  }

  @Test
  void oldProjectRootVerbsAreNotRootCommands() {
    var command = new CommandLine(new Sail());

    for (var oldRoot :
        Set.of(
            "up",
            "down",
            "switch",
            "ps",
            "logs",
            "snap",
            "snaps",
            "snaps-prune",
            "restore",
            "exec",
            "shell",
            "connect",
            "dispatch",
            "run")) {
      assertFalse(command.getSubcommands().containsKey(oldRoot), oldRoot);
    }
  }
}
