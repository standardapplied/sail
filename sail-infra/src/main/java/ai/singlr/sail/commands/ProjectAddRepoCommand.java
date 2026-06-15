/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.SailYamlUpdater;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.GitCredentials;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ProjectApplier;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "add",
    description = "Add a git repository to a running project.",
    mixinStandardHelpOptions = true)
public final class ProjectAddRepoCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Option(
      names = {"-f", "--file"},
      description = "Path to sail.yaml project descriptor.",
      defaultValue = "sail.yaml")
  private String file;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Option(
      names = "--git-token",
      description = "Access token for cloning private repos over HTTPS.",
      defaultValue = "${GITHUB_TOKEN}")
  private String gitToken;

  @Option(names = "--url", description = "Repository URL (non-interactive mode).")
  private String url;

  @Option(names = "--path", description = "Local clone path (non-interactive mode).")
  private String repoPath;

  @Option(names = "--branch", description = "Branch to clone.")
  private String branch;

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

    SailYaml.Repo repo;

    if (url != null) {
      var path = repoPath != null ? repoPath : guessRepoPath(url);
      repo = new SailYaml.Repo(url, path, branch);
    } else {
      if (!json) {
        Banner.printBranding(out, ansi);
        out.println();
        out.println(ansi.string("  @|bold Add repository to|@ " + name));
        out.println();
      }
      repo = collectRepoInteractively(out, ansi);
    }

    var sshUser = config.sshUser();
    var token = Strings.isNotBlank(gitToken) ? gitToken : null;
    var applier = new ProjectApplier(shell, out);
    var result =
        applier.applyRepos(
            name, List.of(repo), sshUser, GitCredentials.singleTokenMap(token), config.git());

    SailYamlUpdater.addRepo(singYamlPath, repo);

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("action", "add-repo");
      map.put("url", repo.url());
      map.put("path", repo.path());
      map.put("added", result.added());
      out.println(YamlUtil.dumpJson(map));
      return;
    }

    out.println();
    out.println(
        ansi.string(
            "  @|bold,green \u2713|@ Repository '"
                + repo.path()
                + "' added to "
                + name
                + " and sail.yaml updated"));
  }

  private SailYaml.Repo collectRepoInteractively(PrintStream out, Ansi ansi) {
    var repoUrl = promptRequired(out, ansi, "Repo URL");
    var pathDefault = guessRepoPath(repoUrl);
    var path = promptWithDefault(out, ansi, "Local path", pathDefault);
    var branchInput = promptWithDefault(out, ansi, "Branch (blank for default)", "");
    return new SailYaml.Repo(repoUrl, path, branchInput.isEmpty() ? null : branchInput);
  }

  private static String guessRepoPath(String url) {
    var lastSlash = url.lastIndexOf('/');
    if (lastSlash < 0) return url;
    var repoName = url.substring(lastSlash + 1);
    if (repoName.endsWith(".git")) repoName = repoName.substring(0, repoName.length() - 4);
    return repoName;
  }

  private static String promptWithDefault(PrintStream out, Ansi ansi, String label, String def) {
    if (!Strings.isEmpty(def)) {
      out.print(ansi.string("  @|bold " + label + "|@ @|faint [" + def + "]|@: "));
    } else {
      out.print(ansi.string("  @|bold " + label + "|@: "));
    }
    out.flush();
    var line = ConsoleHelper.readLine();
    if (Strings.isBlank(line)) {
      return Objects.requireNonNullElse(def, "");
    }
    return line.strip();
  }

  private static String promptRequired(PrintStream out, Ansi ansi, String label) {
    while (true) {
      out.print(ansi.string("  @|bold " + label + "|@: "));
      out.flush();
      var line = ConsoleHelper.readLine();
      if (Strings.isNotBlank(line)) {
        return line.strip();
      }
      out.println(ansi.string("    @|yellow Required field. Please enter a value.|@"));
    }
  }
}
