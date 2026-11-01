/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class CommandTaxonomyTest {

  @Test
  void noFlagThatIsOnByDefaultLacksTheSpellingThatTurnsItOff() {
    var unspellable = new ArrayList<String>();
    collectUnspellableFlags(new CommandLine(new Sail()), unspellable);

    assertEquals(List.of(), unspellable);
  }

  /**
   * A boolean option true by default that is not negatable is toggled off by naming it, so the flag
   * does the opposite of what it says.
   */
  private static void collectUnspellableFlags(CommandLine command, List<String> found) {
    for (var option : command.getCommandSpec().options()) {
      var type = option.type();
      if ((type == boolean.class || type == Boolean.class)
          && "true".equals(option.defaultValue())
          && !option.negatable()) {
        found.add(command.getCommandSpec().qualifiedName() + " " + option.longestName());
      }
    }
    command.getSubcommands().values().stream()
        .distinct()
        .forEach(sub -> collectUnspellableFlags(sub, found));
  }

  @Test
  void rootCommandsAreWorkflowGroups() {
    var command = new CommandLine(new Sail());

    var userFacing =
        command.getSubcommands().keySet().stream()
            .filter(name -> !name.startsWith("_"))
            .collect(java.util.stream.Collectors.toSet());
    assertEquals(
        Set.of(
            "init",
            "client",
            "host",
            "project",
            "server",
            "fde",
            "login",
            "enroll",
            "spec",
            "session",
            "agent",
            "events",
            "migrate",
            "upgrade",
            "sync",
            "conflicts",
            "join"),
        userFacing);
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
  void applyIsTheOneConvergenceVerb() {
    var project = new CommandLine(new ProjectCommand());

    for (var deleted : Set.of("init", "reconfigure", "sync")) {
      assertFalse(
          project.getSubcommands().containsKey(deleted),
          deleted + " must no longer parse: create writes the catalog, apply converges");
    }
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
        Set.of(
            "list",
            "show",
            "create",
            "update",
            "edit",
            "content",
            "delete",
            "board",
            "history",
            "restore",
            "prune",
            "engage",
            "disengage",
            "comment",
            "comments",
            "dispatch"),
        spec.getSubcommands().keySet());
  }

  @Test
  void specUpdateKeepsEditAsAlias() {
    var spec = new CommandLine(new SpecCommand());

    assertSame(spec.getSubcommands().get("update"), spec.getSubcommands().get("edit"));
  }

  @Test
  void agentCommandsExposeClearLifecycle() {
    var agent = new CommandLine(new AgentCommand());

    assertTrue(
        agent
            .getSubcommands()
            .keySet()
            .containsAll(
                Set.of("run", "status", "stop", "logs", "report", "review", "sweep", "watch")));
  }

  @Test
  void projectContainersKeepsShortExpertAlias() {
    var project = new CommandLine(new ProjectCommand());

    assertSame(project.getSubcommands().get("containers"), project.getSubcommands().get("ps"));
  }

  @Test
  void agentLogsKeepsExpertAlias() {
    var agent = new CommandLine(new AgentCommand());

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
