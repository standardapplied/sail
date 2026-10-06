/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.ReviewWiring;
import ai.singlr.sail.api.StageSkills;
import ai.singlr.sail.api.StageSkills.Standing;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.StageSkill;
import ai.singlr.sail.gen.BuiltInSkills;
import ai.singlr.sail.store.FileStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Shows what each stage of the loop will be told: the skill the project's definition names for the
 * build, for each agent review stage and for the fix agent, and whether a launch would find it.
 * {@code show} prints a skill's {@code SKILL.md}, so a project starts its own from sail's default.
 * Everything is read as a launch reads it — the catalog's definition and the project's shared files
 * — and nothing is written.
 */
@Command(
    name = "skills",
    description = "Show the skill each stage of the loop runs under (list, show).",
    mixinStandardHelpOptions = true,
    subcommands = {ProjectSkillsCommand.Show.class})
public final class ProjectSkillsCommand implements Callable<Integer> {

  static final String BUILD_STAGE = "build";
  static final String FIX_STAGE = "fix";

  @Option(
      names = {"-p", "--project"},
      description = "Project (default: the current project from 'sail project switch').")
  private String project;

  @Option(names = "--json", description = "Output as JSON.")
  private boolean json;

  private final Supplier<HostOperations> operations;

  public ProjectSkillsCommand() {
    this(OperationsFactory::open);
  }

  ProjectSkillsCommand(Supplier<HostOperations> operations) {
    this.operations = operations;
  }

  /**
   * One stage and the skill it runs under.
   *
   * @param source {@code built-in}, {@code project}, {@code missing} or {@code invalid: <why>}
   * @param files how many files the skill's folder holds: 1 for a built-in, 0 for a missing one
   */
  record Row(String stage, String skill, String source, int files) {

    static Row of(String stage, String skill, Standing standing) {
      return switch (standing) {
        case Standing.BuiltIn builtIn -> new Row(stage, skill, "built-in", 1);
        case Standing.Held held -> new Row(stage, skill, "project", held.skill().files().size());
        case Standing.Missing missing -> new Row(stage, skill, "missing", 0);
        case Standing.Invalid invalid ->
            new Row(stage, skill, "invalid: " + invalid.why(), invalid.files());
      };
    }

    /** The source as a person reads it: a project's skill says how many files it holds. */
    String shown() {
      return source.equals("project") ? "project (" + files + " files)" : source;
    }
  }

  @Override
  public Integer call() {
    project = CurrentProject.require(project);
    NameValidator.requireValidProjectName(project);
    try (var operations = this.operations.get()) {
      var config = operations.catalog().definitions().require(project);
      var files = operations.projectFiles(project);
      var rows = rows(project, config, new StageSkills(name -> files));
      reserved(files.list()).forEach(System.err::println);
      System.out.print(render(project, rows, json));
      return 0;
    }
  }

  /**
   * The stages of {@code config}'s loop in the order they run — the build, each agent stage of the
   * pipeline the loop resolves, the fix — each with how its skill stands.
   */
  static List<Row> rows(String project, SailYaml config, StageSkills skills) {
    var pipeline = ReviewWiring.configResolver(name -> config).apply(project);
    var build = config.agent() == null ? StageSkill.BUILD : config.agent().buildSkill();
    var rows = new ArrayList<Row>();
    rows.add(Row.of(BUILD_STAGE, build, skills.standing(project, build)));
    for (var stage : pipeline.agentStages()) {
      rows.add(Row.of(stage.name(), stage.skill(), skills.standing(project, stage.skill())));
    }
    rows.add(Row.of(FIX_STAGE, pipeline.fixSkill(), skills.standing(project, pipeline.fixSkill())));
    return List.copyOf(rows);
  }

  /**
   * A warning for each folder under {@code .sail/skills/} whose name starts {@code sail-}: those
   * names are sail's, so no stage can name such a skill and its files are never used.
   */
  static List<String> reserved(List<FileStore.FileRow> files) {
    var root = StageSkill.PROJECT_ROOT + StageSkill.RESERVED;
    return files.stream()
        .map(FileStore.FileRow::path)
        .filter(path -> path.startsWith(root) && path.indexOf('/', root.length()) > 0)
        .map(path -> path.substring(0, path.indexOf('/', root.length()) + 1))
        .distinct()
        .map(
            folder ->
                Ansi.AUTO.string(
                    "  @|yellow ⚠|@ "
                        + folder
                        + " is reserved and unused: skill names starting "
                        + StageSkill.RESERVED
                        + " are sail's own, so no stage can name it. Move its files under another"
                        + " name."))
        .toList();
  }

  static String render(String project, List<Row> rows, boolean json) {
    if (json) {
      var list =
          rows.stream()
              .map(
                  row -> {
                    var map = new LinkedHashMap<String, Object>();
                    map.put("stage", row.stage());
                    map.put("skill", row.skill());
                    map.put("source", row.source());
                    map.put("files", row.files());
                    return (Object) map;
                  })
              .toList();
      return YamlUtil.dumpJson(list) + "\n";
    }
    var stage = width("STAGE", rows.stream().map(Row::stage).toList());
    var skill = width("SKILL", rows.stream().map(Row::skill).toList());
    var format = "  %-" + stage + "s  %-" + skill + "s  %s%n";
    var table = new StringBuilder(Ansi.AUTO.string("  @|bold Stage skills: " + project + "|@\n"));
    table.append(format.formatted("STAGE", "SKILL", "SOURCE"));
    rows.forEach(row -> table.append(format.formatted(row.stage(), row.skill(), row.shown())));
    return table.toString();
  }

  private static int width(String header, List<String> cells) {
    return Math.max(header.length(), cells.stream().mapToInt(String::length).max().orElse(0));
  }

  @Command(
      name = "show",
      description = "Print a skill's SKILL.md: one of sail's defaults, or the project's own.",
      mixinStandardHelpOptions = true)
  static final class Show implements Callable<Integer> {

    @Option(
        names = {"-p", "--project"},
        description = "Project (default: the current project from 'sail project switch').")
    private String project;

    @Parameters(index = "0", description = "The skill's name, e.g. sail-review.")
    private String name;

    private final Supplier<HostOperations> operations;

    Show() {
      this(OperationsFactory::open);
    }

    Show(Supplier<HostOperations> operations) {
      this.operations = operations;
    }

    @Override
    public Integer call() throws IOException {
      var builtIn = BuiltInSkills.text(name);
      if (builtIn.isPresent()) {
        System.out.print(builtIn.get());
        return 0;
      }
      project = CurrentProject.require(project);
      NameValidator.requireValidProjectName(project);
      try (var operations = this.operations.get()) {
        var files = operations.projectFiles(project);
        var skills = new StageSkills(ignored -> files);
        var skill = skills.resolve(project, name);
        var manifest =
            skill.files().stream()
                .filter(file -> file.path().equals(StageSkill.MANIFEST))
                .findFirst()
                .orElseThrow();
        try (var text = skills.open(project, skill, manifest)) {
          text.transferTo(System.out);
        }
        System.out.flush();
        return 0;
      }
    }
  }
}
