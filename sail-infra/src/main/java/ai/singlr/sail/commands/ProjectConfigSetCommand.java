/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.ProjectDefinition.Section;
import ai.singlr.sail.config.ProjectDefinitionJson;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.OperatorCommand;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Writes one section of a project's definition: the JSON value given, or read from stdin for {@code
 * -}; {@code null} unsets it. The definition is checked whole and the write is one revision.
 */
@Command(
    name = "set",
    description = "Write one section of a project's definition from JSON (- reads stdin).",
    mixinStandardHelpOptions = true)
public final class ProjectConfigSetCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Parameters(
      index = "1",
      description =
          "Section: description, image, resources, runtimes, repos, services, processes, packages,"
              + " agent or pipeline.")
  private String section;

  @Parameters(index = "2", description = "The section's JSON value, or - to read it from stdin.")
  private String value;

  @Option(names = "--dry-run", description = "Check the section without writing it.")
  private boolean dryRun;

  private final Supplier<InputStream> stdin;
  private final ProjectMutations mutations;

  public ProjectConfigSetCommand() {
    this(() -> System.in, ProjectMutations.here());
  }

  ProjectConfigSetCommand(Supplier<InputStream> stdin, ProjectMutations mutations) {
    this.stdin = stdin;
    this.mutations = mutations;
  }

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() throws Exception {
    var operator = OperatorCommand.here();
    NameValidator.requireValidProjectName(name);
    var written = Section.of(section);
    var json =
        "-".equals(value)
            ? new String(stdin.get().readAllBytes(), StandardCharsets.UTF_8).strip()
            : value;
    var actor = mutations.writer(name, dryRun);
    var read = mutations.current(name, operator);
    var edited =
        ProjectDefinitionJson.withSection(read, written, "null".equals(json) ? null : json);
    mutations.write(
        written,
        read,
        edited,
        dryRun,
        System.out,
        "write the " + written.key() + " section of '" + name + "'",
        actor);
    if (dryRun) {
      return;
    }
    System.out.println(
        Ansi.AUTO.string(
            "  @|bold,green \u2713|@ Wrote the "
                + written.key()
                + " section of "
                + name
                + ". Bring its container to it with: "
                + operator.sail("project apply " + name)));
  }
}
