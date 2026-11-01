/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.ProjectDefinition.Section;
import ai.singlr.sail.config.ProjectDefinitionJson;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.OperatorCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** Prints one section of a project's definition as the JSON value it holds, {@code null} unset. */
@Command(
    name = "get",
    description = "Print one section of a project's definition as JSON.",
    mixinStandardHelpOptions = true)
public final class ProjectConfigGetCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Parameters(
      index = "1",
      description =
          "Section: description, image, resources, runtimes, repos, services, processes, packages,"
              + " agent or pipeline.")
  private String section;

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() {
    var operator = OperatorCommand.here();
    NameValidator.requireValidProjectName(name);
    var read = Section.of(section);
    var value = ProjectDefinitionJson.section(ProjectMutations.current(name, operator), read);
    System.out.println(value == null ? "null" : value);
  }
}
