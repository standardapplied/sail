/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.ProjectFiles;
import ai.singlr.sail.api.ProjectSkills;
import ai.singlr.sail.api.ProjectSkills.Folder;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.FilePicker;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.StageSkill;
import ai.singlr.sail.engine.WorkspaceFiles;
import ai.singlr.sail.gen.SpecSkillGenerator;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.BlobStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Manages a project's skills: the folders under {@code .sail/skills/} among its files, which sail
 * installs whole into every harness's skills folder, where the agent invokes one when it judges it
 * relevant. {@code ls} shows each folder and whether it is a skill a launch installs; {@code add}
 * shares a local folder as one; {@code rm} stops sharing one; {@code show} prints a skill's {@code
 * SKILL.md}. Everything is read as a launch reads it, the project's shared files, and a write goes
 * through the same store every shared file does, so it syncs.
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

  /**
   * One folder under {@code .sail/skills/} as {@code ls} shows it.
   *
   * @param status {@code ok}, or {@code invalid: <why>}
   */
  record Row(String name, int files, String status) {

    static Row of(Folder folder) {
      return switch (folder) {
        case Folder.Held held -> new Row(held.name(), held.skill().files().size(), "ok");
        case Folder.Invalid invalid ->
            new Row(invalid.name(), invalid.files(), "invalid: " + invalid.why());
      };
    }
  }

  static List<Row> rows(String project, ProjectSkills skills) {
    return skills.all(project).stream().map(Row::of).toList();
  }

  static String render(String project, List<Row> rows, boolean json) {
    if (json) {
      var list =
          rows.stream()
              .map(
                  row -> {
                    var map = new LinkedHashMap<String, Object>();
                    map.put("name", row.name());
                    map.put("files", row.files());
                    map.put("status", row.status());
                    return (Object) map;
                  })
              .toList();
      return YamlUtil.dumpJson(list) + "\n";
    }
    if (rows.isEmpty()) {
      return Ansi.AUTO.string(
          "  @|faint No skills: share one with 'sail project skills add <folder>'.|@\n");
    }
    var name = width("SKILL", rows.stream().map(Row::name).toList());
    var files = width("FILES", rows.stream().map(row -> String.valueOf(row.files())).toList());
    var format = "  %-" + name + "s  %" + files + "s  %s%n";
    var table = new StringBuilder(Ansi.AUTO.string("  @|bold Skills: " + project + "|@\n"));
    table.append(format.formatted("SKILL", "FILES", "STATUS"));
    rows.forEach(row -> table.append(format.formatted(row.name(), row.files(), row.status())));
    return table.toString();
  }

  private static int width(String header, List<String> cells) {
    return Math.max(header.length(), cells.stream().mapToInt(String::length).max().orElse(0));
  }

  @Command(
      name = "ls",
      description = "List the project's skills and whether each is one a launch installs.",
      mixinStandardHelpOptions = true)
  static final class Ls implements Callable<Integer> {

    @Option(
        names = {"-p", "--project"},
        description = "Project (default: the current project from 'sail project switch').")
    private String project;

    @Option(names = "--json", description = "Output as JSON.")
    private boolean json;

    private final Supplier<HostOperations> operations;

    Ls() {
      this(OperationsFactory::open);
    }

    Ls(Supplier<HostOperations> operations) {
      this.operations = operations;
    }

    @Override
    public Integer call() {
      project = CurrentProject.require(project);
      NameValidator.requireValidProjectName(project);
      try (var operations = this.operations.get()) {
        operations.catalog().definitions().require(project);
        System.out.print(
            render(project, rows(project, new ProjectSkills(operations::projectFiles)), json));
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
        description = "Project (default: the current project from 'sail project switch').")
    private String project;

    @Parameters(index = "0", description = "Local folder holding a SKILL.md and any other files.")
    private Path folder;

    @Option(names = "--name", description = "The skill's name (default: the folder's name).")
    private String name;

    private final Supplier<HostOperations> operations;

    Add() {
      this(OperationsFactory::open);
    }

    Add(Supplier<HostOperations> operations) {
      this.operations = operations;
    }

    @Override
    public Integer call() throws Exception {
      project = CurrentProject.require(project);
      NameValidator.requireValidProjectName(project);
      var source = folder.toAbsolutePath().normalize();
      if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
        System.err.println(Banner.errorLine("Not a folder: " + folder, Ansi.AUTO));
        return 1;
      }
      var skillName = Strings.isNotBlank(name) ? name : source.getFileName().toString();
      ProjectSkills.requireOwnName(skillName);
      var local = read(source, skillName);
      try (var operations = this.operations.get()) {
        var files = operations.projectFiles(project);
        for (var file : local) {
          var problem = files.limits().problem(file.size());
          if (problem.isPresent()) {
            System.err.println(Banner.errorLine(file.path() + ": " + problem.get(), Ansi.AUTO));
            return 1;
          }
        }
        Actor.call(
            operations.identity().operator(),
            () -> {
              for (var file : local) {
                share(files, source, skillName, file);
              }
              return null;
            });
        files.materialize();
      }
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ Shared skill @|bold "
                  + skillName
                  + "|@ ("
                  + local.size()
                  + " file"
                  + (local.size() == 1 ? "" : "s")
                  + ") on @|bold "
                  + project
                  + "|@; it is installed for every harness at the next launch or apply."));
      return 0;
    }

    /**
     * The folder as the skill it would be, refused as {@link StageSkill} refuses one: before any
     * file is shared, so a folder that is no skill shares nothing.
     */
    private static List<StageSkill.File> read(Path source, String skillName) throws IOException {
      var files = new ArrayList<StageSkill.File>();
      String manifest = null;
      try (Stream<Path> walk = Files.walk(source)) {
        for (var path : walk.sorted().toList()) {
          if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            continue;
          }
          var relative = source.relativize(path).toString().replace('\\', '/');
          if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                "Skill '" + skillName + "' holds " + relative + ", which is not a regular file.");
          }
          if (!FilePicker.isShareablePath(relative)) {
            throw new IllegalArgumentException(
                "Skill '"
                    + skillName
                    + "' holds the path '"
                    + relative
                    + "', which cannot be shared.");
          }
          var bytes = Files.readAllBytes(path);
          if (relative.equals(StageSkill.MANIFEST)) {
            manifest = new String(bytes, StandardCharsets.UTF_8);
          }
          files.add(
              new StageSkill.File(
                  relative, BlobStore.hash(bytes), bytes.length, WorkspaceFiles.mode(path)));
        }
      }
      if (manifest == null) {
        throw new IllegalArgumentException(
            "Skill '" + skillName + "' has no " + StageSkill.MANIFEST + ".");
      }
      return StageSkill.of(skillName, manifest, files).files();
    }

    private static void share(
        ProjectFiles files, Path source, String skillName, StageSkill.File file)
        throws IOException {
      var local = source.resolve(file.path());
      try (InputStream input = Files.newInputStream(local)) {
        files.put(
            StageSkill.projectFolder(skillName) + file.path(), input, file.size(), file.mode());
      }
    }
  }

  @Command(
      name = "rm",
      description = "Stop sharing a skill: every file under its folder (propagates the deletion).",
      mixinStandardHelpOptions = true)
  static final class Rm implements Callable<Integer> {

    @Option(
        names = {"-p", "--project"},
        description = "Project (default: the current project from 'sail project switch').")
    private String project;

    @Parameters(index = "0", description = "The skill's name.")
    private String name;

    private final Supplier<HostOperations> operations;

    Rm() {
      this(OperationsFactory::open);
    }

    Rm(Supplier<HostOperations> operations) {
      this.operations = operations;
    }

    @Override
    public Integer call() throws Exception {
      project = CurrentProject.require(project);
      NameValidator.requireValidProjectName(project);
      StageSkill.requireName(name);
      var folder = StageSkill.projectFolder(name);
      try (var operations = this.operations.get()) {
        var files = operations.projectFiles(project);
        var paths =
            files.list().stream()
                .map(row -> row.path())
                .filter(path -> path.startsWith(folder))
                .toList();
        if (paths.isEmpty()) {
          System.err.println(
              Banner.errorLine("No skill '" + name + "' on " + project + ".", Ansi.AUTO));
          return 1;
        }
        Actor.call(
            operations.identity().operator(),
            () -> {
              for (var path : paths) {
                files.remove(path);
              }
              return null;
            });
        System.out.println(
            Ansi.AUTO.string(
                "  @|green ✓|@ Stopped sharing skill @|bold "
                    + name
                    + "|@ ("
                    + paths.size()
                    + " file"
                    + (paths.size() == 1 ? "" : "s")
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
        description = "Project (default: the current project from 'sail project switch').")
    private String project;

    @Parameters(index = "0", description = "The skill's name.")
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
      if (SpecSkillGenerator.NAME.equals(name)) {
        System.out.print(SpecSkillGenerator.skillMd());
        return 0;
      }
      project = CurrentProject.require(project);
      NameValidator.requireValidProjectName(project);
      StageSkill.requireName(name);
      try (var operations = this.operations.get()) {
        var manifest =
            operations
                .projectFiles(project)
                .get(StageSkill.projectFolder(name) + StageSkill.MANIFEST)
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "No skill '"
                                + name
                                + "' on "
                                + project
                                + ": it has no "
                                + StageSkill.projectFolder(name)
                                + StageSkill.MANIFEST
                                + "."));
        try (manifest) {
          manifest.transferTo(System.out);
        }
        System.out.flush();
        return 0;
      }
    }
  }
}
