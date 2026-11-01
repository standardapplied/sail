/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.DefinitionEdits;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.ProjectDefinition.Section;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.GitCredentials;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ProjectApplier;
import ai.singlr.sail.engine.ShellExecutor;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
    name = "add",
    description = "Add a git repository to a running project.",
    mixinStandardHelpOptions = true)
public final class ProjectAddRepoCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

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

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() throws Exception {
    var operator = OperatorCommand.here();
    NameValidator.requireValidProjectName(name);
    var ansi = Ansi.AUTO;
    var out = System.out;

    var mutations = ProjectMutations.here();
    var actor = mutations.writer(name, dryRun);
    var config = mutations.current(name, operator);
    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);
    var state = mgr.queryState(name);

    ContainerStateGuard.requireRunning(state, name);

    ProjectDefinition.Repo repo;

    if (url != null) {
      var path = repoPath != null ? repoPath : ProjectDefinition.Repo.pathOf(url);
      repo = new ProjectDefinition.Repo(url, path, branch);
    } else {
      if (!json) {
        Banner.printBranding(out, ansi);
        out.println();
        out.println(ansi.string("  @|bold Add repository to|@ " + name));
        out.println();
      }
      repo = collectRepoInteractively(out, ansi);
    }

    var edited = ProjectMutations.checked(DefinitionEdits.addRepo(config, repo));
    var token = Strings.isNotBlank(gitToken) ? gitToken : null;
    var applier = new ProjectApplier(shell, out);
    var result =
        applier.applyRepos(
            name, List.of(repo), ProjectDefinition.USER, GitCredentials.singleTokenMap(token));
    mutations.write(
        Section.REPOS,
        config,
        edited,
        dryRun,
        out,
        "record repo '" + repo.path() + "' in the catalog",
        actor);

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
                + " and its definition"));
  }

  private ProjectDefinition.Repo collectRepoInteractively(PrintStream out, Ansi ansi) {
    var repoUrl = ConsoleHelper.promptRequired(out, ansi, "Repo URL");
    var pathDefault = ProjectDefinition.Repo.pathOf(repoUrl);
    var path = ConsoleHelper.promptWithDefault(out, ansi, "Local path", pathDefault);
    var branchInput = ConsoleHelper.promptWithDefault(out, ansi, "Branch (blank for default)", "");
    return new ProjectDefinition.Repo(repoUrl, path, branchInput.isEmpty() ? null : branchInput);
  }
}
