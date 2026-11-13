/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * W10 for every verb that addresses a container by a project's name: each passes through the one
 * guard, {@code ContainerManager.queryState} or {@code queryInfo} (which refuse an instance of that
 * name that is a workspace, a template or a copy, {@code ContainerManagerTest}), or asks {@code
 * BoxInstances.requireNotTakenFor} itself, as the verbs that read no state do.
 */
class ProjectVerbsTakeNoWorkspaceTest {

  /** Each verb, by its class, and the guard it passes through. */
  private static final Map<Class<?>, String> VERBS =
      Map.ofEntries(
          Map.entry(UpCommand.class, "queryState("),
          Map.entry(DownCommand.class, "queryState("),
          Map.entry(SwitchCommand.class, "queryState("),
          Map.entry(PsCommand.class, "queryState("),
          Map.entry(LogsCommand.class, "queryState("),
          Map.entry(ExecCommand.class, "queryState("),
          Map.entry(ShellCommand.class, "queryState("),
          Map.entry(ConnectCommand.class, "queryState("),
          Map.entry(SnapCommand.class, "queryState("),
          Map.entry(SnapsCommand.class, "queryState("),
          Map.entry(RestoreCommand.class, "queryState("),
          Map.entry(SnapDeleteCommand.class, "queryState("),
          Map.entry(SnapsPruneCommand.class, "requireNotTakenFor("),
          Map.entry(ProjectRenameCommand.class, "queryState("),
          Map.entry(ProjectResourcesSetCommand.class, "queryInfo("),
          Map.entry(ProjectConfigShowCommand.class, "queryInfo("),
          Map.entry(ProjectInstallAgentCommand.class, "queryState("),
          Map.entry(ProjectAddRepoCommand.class, "queryState("),
          Map.entry(ProjectAddServiceCommand.class, "queryState("),
          Map.entry(ProjectRemoveServiceCommand.class, "queryState("),
          Map.entry(ProjectDemoCommand.class, "queryState("),
          Map.entry(ProjectApplyCommand.class, "queryState("),
          Map.entry(ProjectDestroyCommand.class, "requireNotTakenFor("),
          Map.entry(ProjectCreateCommand.class, "requireNotTakenFor("),
          Map.entry(RunCommand.class, "queryState("),
          Map.entry(AgentAttachCommand.class, "queryState("),
          Map.entry(AgentLogCommand.class, "queryState("),
          Map.entry(AgentReportCommand.class, "queryState("),
          Map.entry(AgentStatusCommand.class, "queryState("),
          Map.entry(AgentSweepCommand.class, "queryState("),
          Map.entry(AgentWatchCommand.class, "queryState("));

  @Test
  void everyVerbThatAddressesAContainerByAProjectsNamePassesThroughTheGuard() throws IOException {
    var unguarded = new ArrayList<String>();
    for (var verb : VERBS.entrySet()) {
      var source =
          Files.readString(
              Path.of("src", "main", "java", "ai", "singlr", "sail", "commands")
                  .resolve(verb.getKey().getSimpleName() + ".java"));
      if (!source.contains(verb.getValue())) {
        unguarded.add(verb.getKey().getSimpleName());
      }
    }

    assertEquals(List.of(), unguarded);
  }

  @Test
  void everyVerbOfTheTableIsOneSailHas() {
    var reached = new ArrayList<Class<?>>();
    collect(new CommandLine(new Sail()), reached);

    for (var verb : VERBS.keySet()) {
      assertTrue(reached.contains(verb), verb.getSimpleName() + " is no verb of sail's");
    }
  }

  @Test
  void theFilesVerbAndTheApiDoorsPassThroughTheGuardToo() throws IOException {
    var root = Path.of("src", "main", "java", "ai", "singlr", "sail");
    assertTrue(
        Files.readString(root.resolve("commands").resolve("ContainerWorkspace.java"))
            .contains("queryState("));
    assertTrue(
        Files.readString(root.resolve("api").resolve("ProjectLoader.java"))
            .contains("queryState("));
  }

  private static void collect(CommandLine command, List<Class<?>> reached) {
    reached.add(command.getCommandSpec().userObject().getClass());
    for (var sub : command.getSubcommands().values()) {
      collect(sub, reached);
    }
  }
}
