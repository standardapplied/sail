/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.ErrorCode;
import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SkillOperations;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.gen.SpecSkillGenerator;
import ai.singlr.sail.identity.Actor;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Manages a project's skills on this box: the folders under {@code .sail/skills/} among its files,
 * which sail installs whole into every harness's skills folder, where the agent invokes one when it
 * judges it relevant. {@code ls} shows every skill an agent of the project can see; {@code add}
 * shares a local folder as one; {@code rm} stops sharing one; {@code show} prints a skill's {@code
 * SKILL.md}. Each is the one {@link SkillOperations} verb the container's {@code skill} also runs,
 * so a skill shared from either door is the same write, and it syncs.
 */
@Command(
    name = "skills",
    description = "Manage the project's skills (ls, add, rm, show).",
    mixinStandardHelpOptions = true,
    subcommands = {
      ProjectSkillsCommand.Ls.class,
      ProjectSkillsCommand.Add.class,
      ProjectSkillsCommand.Rm.class,
      ProjectSkillsCommand.Show.class
    })
public final class ProjectSkillsCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  private static OperatorCommand operator() {
    return OperatorCommand.here();
  }

  static String render(
      String project, SkillOperations.Rows rows, boolean json, OperatorCommand operator) {
    if (json) {
      return YamlUtil.dumpJson(rows.toMap()) + "\n";
    }
    var table = new StringBuilder(Ansi.AUTO.string("  @|bold Skills: " + project + "|@\n"));
    var cells =
        rows.rows().stream()
            .map(
                row ->
                    List.of(
                        row.name(),
                        row.kind().name().toLowerCase(Locale.ROOT),
                        String.join(", ", row.agents()),
                        row.share().isEmpty()
                            ? row.status()
                            : row.status()
                                + ": share it with '"
                                + row.share()
                                + "' in the container"))
            .toList();
    var header = List.of("SKILL", "FROM", "AGENTS", "STATUS");
    var widths = new int[3];
    for (var column = 0; column < 3; column++) {
      widths[column] = header.get(column).length();
      for (var row : cells) {
        widths[column] = Math.max(widths[column], row.get(column).length());
      }
    }
    var format = "  %-" + widths[0] + "s  %-" + widths[1] + "s  %-" + widths[2] + "s  %s%n";
    table.append(format.formatted(header.toArray()));
    cells.forEach(row -> table.append(format.formatted(row.toArray())));
    rows.note().ifPresent(note -> table.append(Ansi.AUTO.string("  @|faint " + note + "|@\n")));
    if (rows.rows().stream().noneMatch(row -> row.kind() == SkillOperations.Kind.SHARED)) {
      table.append(
          Ansi.AUTO.string(
              "  @|faint Share one with '"
                  + operator.sail("project skills add <folder>")
                  + "'.|@\n"));
    }
    return table.toString();
  }

  @Command(
      name = "ls",
      description = "List every skill an agent of the project can see, and where each comes from.",
      mixinStandardHelpOptions = true)
  static final class Ls implements Callable<Integer> {

    @Option(
        names = {"-p", "--project"},
        description = "Project (default: the current project, set by project switch).")
    private String project;

    @Option(names = "--json", description = "Output as JSON.")
    private boolean json;

    private final Function<OperatorCommand, HostOperations> operations;

    Ls() {
      this(operator -> OperationsFactory.open(operator::warn));
    }

    Ls(Function<OperatorCommand, HostOperations> operations) {
      this.operations = operations;
    }

    @Override
    public Integer call() throws Exception {
      var operator = operator();
      project = CurrentProject.require(project);
      NameValidator.requireValidProjectName(project);
      try (var operations = this.operations.apply(operator)) {
        System.out.print(render(project, operations.skills().rows(project), json, operator));
        return 0;
      }
    }
  }

  @Command(
      name = "add",
      description =
          "Share a local folder as a skill: every file under it becomes .sail/skills/<name>/<path>.",
      mixinStandardHelpOptions = true)
  static final class Add implements Callable<Integer> {

    @Option(
        names = {"-p", "--project"},
        description = "Project (default: the current project, set by project switch).")
    private String project;

    @Parameters(index = "0", description = "Local folder holding a SKILL.md and any other files.")
    private Path folder;

    @Option(names = "--name", description = "The skill's name (default: the folder's name).")
    private String name;

    private final Function<OperatorCommand, HostOperations> operations;

    Add() {
      this(operator -> OperationsFactory.open(operator::warn));
    }

    Add(Function<OperatorCommand, HostOperations> operations) {
      this.operations = operations;
    }

    @Override
    public Integer call() throws Exception {
      var operator = operator();
      project = CurrentProject.require(project);
      NameValidator.requireValidProjectName(project);
      try (var operations = this.operations.apply(operator)) {
        var shared =
            Actor.call(
                operations.identity().operator(),
                () -> operations.skills().shareFromHost(project, folder, name));
        System.out.println(
            Ansi.AUTO.string(
                "  @|green ✓|@ Shared skill @|bold "
                    + shared.name()
                    + "|@ ("
                    + files(shared.files())
                    + ") on @|bold "
                    + project
                    + "|@; it is installed for "
                    + agents(shared.agents())
                    + " at the next launch or apply."));
        shared.advice().ifPresent(advice -> System.out.println(Banner.warnLine(advice, Ansi.AUTO)));
      }
      return 0;
    }
  }

  @Command(
      name = "rm",
      description = "Stop sharing a skill: every file under its folder (propagates the deletion).",
      mixinStandardHelpOptions = true)
  static final class Rm implements Callable<Integer> {

    @Option(
        names = {"-p", "--project"},
        description = "Project (default: the current project, set by project switch).")
    private String project;

    @Parameters(index = "0", description = "The skill's name.")
    private String name;

    private final Function<OperatorCommand, HostOperations> operations;

    Rm() {
      this(operator -> OperationsFactory.open(operator::warn));
    }

    Rm(Function<OperatorCommand, HostOperations> operations) {
      this.operations = operations;
    }

    @Override
    public Integer call() throws Exception {
      var operator = operator();
      project = CurrentProject.require(project);
      NameValidator.requireValidProjectName(project);
      try (var operations = this.operations.apply(operator)) {
        int removed;
        try {
          removed =
              Actor.call(
                  operations.identity().operator(),
                  () -> operations.skills().remove(project, name));
        } catch (ApiException absent) {
          if (absent.failure().errorCode() != ErrorCode.NOT_FOUND) {
            throw absent;
          }
          System.err.println(Banner.errorLine(absent.getMessage(), Ansi.AUTO));
          return 1;
        }
        System.out.println(
            Ansi.AUTO.string(
                "  @|green ✓|@ Stopped sharing skill @|bold "
                    + name
                    + "|@ ("
                    + files(removed)
                    + "); its folder is removed at the next launch or apply."));
        return 0;
      }
    }
  }

  @Command(
      name = "show",
      description = "Print a skill's SKILL.md: the project's own, or sail's spec-board.",
      mixinStandardHelpOptions = true)
  static final class Show implements Callable<Integer> {

    @Option(
        names = {"-p", "--project"},
        description = "Project (default: the current project, set by project switch).")
    private String project;

    @Parameters(index = "0", description = "The skill's name.")
    private String name;

    private final Function<OperatorCommand, HostOperations> operations;

    Show() {
      this(operator -> OperationsFactory.open(operator::warn));
    }

    Show(Function<OperatorCommand, HostOperations> operations) {
      this.operations = operations;
    }

    @Override
    public Integer call() throws Exception {
      var operator = operator();
      if (!SpecSkillGenerator.NAME.equals(name)) {
        project = CurrentProject.require(project);
        NameValidator.requireValidProjectName(project);
      }
      try (var operations = this.operations.apply(operator)) {
        System.out.print(operations.skills().manifest(project, name));
        System.out.flush();
        return 0;
      }
    }
  }

  private static String files(int count) {
    return count + " file" + (count == 1 ? "" : "s");
  }

  private static String agents(List<String> agents) {
    if (agents.isEmpty()) {
      return "no agent (the project installs none)";
    }
    if (agents.size() == 1) {
      return agents.getFirst();
    }
    return String.join(", ", agents.subList(0, agents.size() - 1)) + " and " + agents.getLast();
  }
}
