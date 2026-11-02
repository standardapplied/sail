/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ShellExecutor;
import java.nio.file.Path;
import java.util.Optional;
import picocli.CommandLine.Help.Ansi;

/**
 * Resolves a project's container workspace ({@code /home/<user>/workspace}) so commands can read
 * the project's files where they actually live — in the running container — rather than asking the
 * engineer to {@code cd} anywhere, as the account every container runs as ({@link
 * ProjectDefinition#USER}); the directory only resolves when the container is running.
 */
final class ContainerWorkspace {

  private ContainerWorkspace() {}

  /** The workspace directory for a running project, or empty (with a printed reason) otherwise. */
  static Optional<Path> resolve(String project, OperatorCommand operator) {
    try {
      var state = new ContainerManager(new ShellExecutor(false)).queryState(project);
      if (!(state instanceof ContainerState.Running)) {
        System.err.println(
            Banner.errorLine(
                "Project '"
                    + project
                    + "' is not running. Run '"
                    + operator.sail("project switch " + project)
                    + "'.",
                Ansi.AUTO));
        return Optional.empty();
      }
    } catch (Exception e) {
      System.err.println(
          Banner.errorLine(
              "Could not reach project '" + project + "': " + e.getMessage(), Ansi.AUTO));
      return Optional.empty();
    }
    return Optional.of(Path.of("/home/" + ProjectDefinition.USER + "/workspace"));
  }
}
