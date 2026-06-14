/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.IntStream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "file",
    description = "Capture workspace files from a running project for sharing.",
    mixinStandardHelpOptions = true)
public final class ProjectAddFileCommand implements Runnable {

  static final List<String> DEFAULT_PATTERNS =
      List.of(
          ".env",
          ".env.*",
          "*.properties",
          "*.conf",
          "*.sh",
          "docker-compose*.yml",
          "docker-compose*.yaml");

  static final List<String> EXCLUDED_DIRS =
      List.of(".git", "node_modules", "target", ".m2", "build", ".cache", "vendor", ".gradle");

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Parameters(
      index = "1..*",
      arity = "0..*",
      description = "File paths relative to ~/workspace/ (interactive if omitted).")
  private List<String> paths;

  @Option(
      names = {"-f", "--file"},
      description = "Path to sail.yaml project descriptor.",
      defaultValue = "sail.yaml")
  private String file;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Option(names = "--dry-run", description = "Print what would be pulled without writing.")
  private boolean dryRun;

  @Option(
      names = "--pattern",
      description = "Custom search pattern for file discovery (e.g. *.json, setup.*).")
  private String pattern;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidProjectName(name);
    var ansi = Ansi.AUTO;
    var out = System.out;

    var singYamlPath = SailPaths.resolveSailYaml(name, file);
    if (!Files.exists(singYamlPath)) {
      throw new IllegalStateException(
          "Project descriptor not found: "
              + singYamlPath.toAbsolutePath()
              + "\n  Create a sail.yaml in the current directory, or specify one with --file.");
    }

    var config = SailYaml.fromMap(YamlUtil.parseFile(singYamlPath));
    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);
    var state = mgr.queryState(name);

    ContainerStateGuard.requireRunning(state, name);

    var sshUser = config.sshUser();
    var workspaceDir = "/home/" + sshUser + "/workspace";
    var localFilesDir = singYamlPath.toAbsolutePath().getParent().resolve("files");

    List<String> selectedPaths;
    if (paths != null && !paths.isEmpty()) {
      selectedPaths = paths;
    } else {
      if (!json) {
        Banner.printBranding(out, ansi);
        out.println();
        out.println(ansi.string("  @|bold Add workspace files to|@ " + name));
        out.println();
      }
      selectedPaths = discoverInteractively(out, ansi, sshUser, workspaceDir);
      if (selectedPaths.isEmpty()) {
        if (!json) {
          out.println(ansi.string("  @|faint No files selected.|@"));
        }
        return;
      }
    }

    var pulled = pullFiles(shell, out, ansi, selectedPaths, workspaceDir, localFilesDir);

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("action", "add-file");
      map.put("files", pulled);
      map.put("output", localFilesDir.toString());
      map.put("dry_run", dryRun);
      out.println(YamlUtil.dumpJson(map));
      return;
    }

    if (!pulled.isEmpty()) {
      out.println();
      out.println(
          ansi.string(
              "  @|bold,green \u2713|@ Pulled " + pulled.size() + " file(s) to " + localFilesDir));
      for (var p : pulled) {
        out.println(ansi.string("    files/" + p));
      }
      out.println();
      out.println(
          ansi.string(
              "  @|faint Next:|@ @|bold sail project push " + name + "|@ to share with team"));
    }
  }

  private List<String> discoverInteractively(
      PrintStream out, Ansi ansi, String sshUser, String workspaceDir) throws Exception {
    var searchPattern = pattern;

    while (true) {
      if (searchPattern != null) {
        out.println(
            ansi.string(
                "  @|bold Searching|@ ~/workspace for @|faint " + searchPattern + "|@ files..."));
      } else {
        out.println(ansi.string("  @|bold Searching|@ ~/workspace for config files..."));
      }

      var discovered = searchContainer(workspaceDir, searchPattern);

      if (discovered.isEmpty()) {
        out.println();
        out.println(ansi.string("  @|faint No files found.|@"));
        out.println();
        out.println(ansi.string("  Enter a search pattern or workspace file path:"));
        out.println(ansi.string("    @|faint Pattern examples: *.json, *.xml, setup.*|@"));
        out.println(ansi.string("    @|faint Path example:    myapp/config.txt|@"));
        out.println();
        out.print(ansi.string("  @|bold Search or path|@: "));
        out.flush();
        var input = ConsoleHelper.readLine();
        if (Strings.isBlank(input)) {
          return List.of();
        }
        input = input.strip();
        if (input.contains("*") || input.contains("?")) {
          searchPattern = input;
          out.println();
          continue;
        }
        return collectManualPaths(out, ansi, input);
      }

      out.println();
      out.println(ansi.string("  Found @|bold " + discovered.size() + "|@ file(s):"));
      out.println();
      for (var i = 0; i < discovered.size(); i++) {
        out.println(ansi.string("    @|bold " + (i + 1) + ".|@ " + discovered.get(i)));
      }
      out.println();
      out.println(
          ansi.string(
              "  Enter file numbers (e.g. @|faint 1,3|@ or @|faint 1-3|@ or @|faint all|@),"
                  + " or:"));
      out.println(ansi.string("    @|bold p|@  Search with a different pattern (e.g. *.json)"));
      out.println(
          ansi.string("    @|bold f|@  Enter a workspace path directly (e.g. myapp/config.txt)"));
      out.println();
      out.print(ansi.string("  @|bold Selection|@: "));
      out.flush();
      var input = Objects.requireNonNullElse(ConsoleHelper.readLine(), "").strip();

      if (input.isEmpty()) {
        return List.of();
      }

      if (input.equalsIgnoreCase("p")) {
        out.print(ansi.string("  @|bold Pattern|@ @|faint (e.g. *.json, setup.*)|@: "));
        out.flush();
        var patternInput = ConsoleHelper.readLine();
        if (Strings.isBlank(patternInput)) {
          return List.of();
        }
        searchPattern = patternInput.strip();
        out.println();
        continue;
      }

      if (input.equalsIgnoreCase("f")) {
        out.print(ansi.string("  @|bold File path|@ @|faint (relative to ~/workspace)|@: "));
        out.flush();
        var pathInput = ConsoleHelper.readLine();
        if (Strings.isBlank(pathInput)) {
          return List.of();
        }
        return collectManualPaths(out, ansi, pathInput.strip());
      }

      var indices = parseSelection(input, discovered.size());
      if (indices.isEmpty()) {
        out.println(ansi.string("    @|yellow Invalid selection. Try again.|@"));
        out.println();
        continue;
      }

      return indices.stream().map(i -> discovered.get(i - 1)).toList();
    }
  }

  private List<String> collectManualPaths(PrintStream out, Ansi ansi, String firstPath) {
    var manualPaths = new ArrayList<String>();
    manualPaths.add(firstPath);
    while (ConsoleHelper.confirmNo("Add another file?")) {
      out.print(ansi.string("  @|bold File path|@ @|faint (relative to ~/workspace)|@: "));
      out.flush();
      var pathInput = ConsoleHelper.readLine();
      if (Strings.isNotBlank(pathInput)) {
        manualPaths.add(pathInput.strip());
      }
    }
    return manualPaths;
  }

  private List<String> searchContainer(String workspaceDir, String customPattern) throws Exception {
    var readShell = new ShellExecutor(false);
    var findArgs = buildFindArgs(workspaceDir, customPattern);
    var cmd = ContainerExec.asDevUser(name, findArgs);
    var result = readShell.exec(cmd);
    if (!result.ok()) {
      return List.of();
    }
    return parseDiscoveryOutput(result.stdout(), workspaceDir);
  }

  static List<String> buildFindArgs(String workspaceDir, String customPattern) {
    var args = new ArrayList<String>();
    args.add("find");
    args.add(workspaceDir);
    args.add("-maxdepth");
    args.add("5");
    args.add("-type");
    args.add("f");
    args.add("(");
    if (customPattern != null) {
      args.add("-name");
      args.add(customPattern);
    } else {
      var first = true;
      for (var p : DEFAULT_PATTERNS) {
        if (!first) args.add("-o");
        args.add("-name");
        args.add(p);
        first = false;
      }
    }
    args.add(")");
    for (var dir : EXCLUDED_DIRS) {
      args.add("!");
      args.add("-path");
      args.add("*/" + dir + "/*");
    }
    return List.copyOf(args);
  }

  static List<String> parseDiscoveryOutput(String output, String workspaceDir) {
    if (Strings.isBlank(output)) return List.of();
    var prefix = workspaceDir + "/";
    return output
        .lines()
        .map(String::strip)
        .filter(line -> !line.isEmpty())
        .map(line -> line.startsWith(prefix) ? line.substring(prefix.length()) : line)
        .sorted()
        .toList();
  }

  static List<Integer> parseSelection(String input, int max) {
    if (input.strip().equalsIgnoreCase("all")) {
      return IntStream.rangeClosed(1, max).boxed().toList();
    }
    try {
      var result = new TreeSet<Integer>();
      for (var part : input.split(",")) {
        part = part.strip();
        if (part.contains("-")) {
          var range = part.split("-", 2);
          var start = Integer.parseInt(range[0].strip());
          var end = Integer.parseInt(range[1].strip());
          for (var i = start; i <= end; i++) {
            if (i >= 1 && i <= max) result.add(i);
          }
        } else {
          var n = Integer.parseInt(part);
          if (n >= 1 && n <= max) result.add(n);
        }
      }
      return List.copyOf(result);
    } catch (NumberFormatException e) {
      return List.of();
    }
  }

  private List<String> pullFiles(
      ShellExec shell,
      PrintStream out,
      Ansi ansi,
      List<String> filePaths,
      String workspaceDir,
      Path localFilesDir)
      throws Exception {
    var pulled = new ArrayList<String>();
    for (var relativePath : filePaths) {
      var containerPath = name + workspaceDir + "/" + relativePath;
      var localPath = localFilesDir.resolve(relativePath);

      if (dryRun) {
        if (!json) {
          out.println(ansi.string("  @|faint [dry-run] Would pull:|@ " + relativePath));
        }
        pulled.add(relativePath);
        continue;
      }

      if (localPath.getParent() != null) {
        Files.createDirectories(localPath.getParent());
      }

      var cmd = List.of("incus", "file", "pull", containerPath, localPath.toString());
      var result = shell.exec(cmd);
      if (result.ok()) {
        pulled.add(relativePath);
        if (!json) {
          out.println(ansi.string("  @|green \u2713|@ " + relativePath));
        }
      } else {
        if (!json) {
          out.println(ansi.string("  @|red \u2717|@ " + relativePath + " @|faint (not found)|@"));
        }
      }
    }
    return pulled;
  }
}
