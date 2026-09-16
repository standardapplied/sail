/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.ShellExecutor;
import java.nio.file.Path;
import java.util.Optional;
import picocli.CommandLine.Help.Ansi;

/**
 * Resolves a project's container workspace ({@code /home/<user>/workspace}) so commands can read
 * the project's files where they actually live — in the running container — rather than asking the
 * engineer to {@code cd} anywhere. The user is read from the project's database-backed descriptor;
 * the directory only resolves when the container is running.
 */
final class ContainerWorkspace {

  private ContainerWorkspace() {}

  /** The workspace directory for a running project, or empty (with a printed reason) otherwise. */
  static Optional<Path> resolve(String project) {
    try {
      var state = new ContainerManager(new ShellExecutor(false)).queryState(project);
      if (!(state instanceof ContainerState.Running)) {
        System.err.println(
            Banner.errorLine(
                "Project '"
                    + project
                    + "' is not running. Run 'sail project switch "
                    + project
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
    return Optional.of(Path.of("/home/" + sshUser(project) + "/workspace"));
  }

  private static String sshUser(String project) {
    try (var operations = OperationsFactory.open()) {
      return operations
          .catalogProject(project)
          .map(row -> SailYaml.fromMap(YamlUtil.parseMap(row.definition())).sshUser())
          .orElse("dev");
    } catch (Exception e) {
      return "dev";
    }
  }
}
