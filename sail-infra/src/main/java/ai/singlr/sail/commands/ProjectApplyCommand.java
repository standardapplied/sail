/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.GitCredentials;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ProjectApplier;
import ai.singlr.sail.engine.ProjectDefinitions;
import ai.singlr.sail.engine.ShellExecutor;
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
    name = "apply",
    description = "Apply incremental changes from sail.yaml to a running project.",
    mixinStandardHelpOptions = true)
public final class ProjectApplyCommand implements Runnable {

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

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidProjectName(name);

    var explicit = ProjectDefinitions.explicitFile(file);
    var definitionText =
        ProjectDefinitions.definition(name, explicit)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No descriptor found for project '"
                            + name
                            + "'. Sync it from main with 'sail sync', or author one with 'sail"
                            + " project init'."));
    SailYaml config = ProjectDefinitions.resolveForProvisioning(definitionText);
    var singYamlPath =
        explicit != null ? explicit : ProjectDefinitions.materialize(name, definitionText);

    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);
    var state = mgr.queryState(name);
    var info = mgr.queryInfo(name);

    switch (state) {
      case ContainerState.Running ignored -> {}
      case ContainerState.Stopped ignored ->
          throw new IllegalStateException(
              "Project '" + name + "' is stopped. Start it with: sail project start " + name);
      case ContainerState.NotCreated ignored ->
          throw new IllegalStateException(
              "Project '"
                  + name
                  + "' does not exist. Run 'sail project create "
                  + name
                  + "' first.");
      case ContainerState.Error e ->
          throw new IllegalStateException("Container error: " + e.message());
    }

    var ansi = Ansi.AUTO;
    if (!json) {
      Banner.printBranding(System.out, ansi);
      System.out.println();
      System.out.println(ansi.string("  @|bold Applying changes to|@ " + name + "..."));
      System.out.println();
    }

    var applier = new ProjectApplier(shell, System.out);
    var sshUser = config.sshUser();
    var token = Strings.isNotBlank(gitToken) ? gitToken : null;

    var totalAdded = 0;
    var totalRemoved = 0;
    var totalSkipped = 0;

    var warnings = applier.checkUnsupportedChanges(config, info.limits());

    var svcResult = applier.applyServices(name, config.services());
    totalAdded += svcResult.added();
    totalSkipped += svcResult.skipped();

    var reconcileResult = applier.reconcileServices(name, config.services());
    totalRemoved += reconcileResult.removed();

    var repoResult =
        applier.applyRepos(
            name, config.repos(), sshUser, GitCredentials.singleTokenMap(token), config.git());
    totalAdded += repoResult.added();
    totalSkipped += repoResult.skipped();

    var filesResult = applier.applyWorkspaceFiles(name, singYamlPath, sshUser);
    totalAdded += filesResult.added();
    totalSkipped += filesResult.skipped();

    var agentInstall =
        config.agent() != null
            ? Objects.requireNonNullElse(config.agent().install(), List.of(config.agent().type()))
            : null;
    var agentResult = applier.applyAgentTools(name, agentInstall);
    totalAdded += agentResult.added();
    totalSkipped += agentResult.skipped();

    var gitResult = applier.applyGitConfig(name, config.git(), sshUser);
    totalAdded += gitResult.added();
    totalSkipped += gitResult.skipped();

    var ctxResult = applier.applyAgentContext(name, config);
    totalAdded += ctxResult.added();
    totalSkipped += ctxResult.skipped();

    var cleanupResult = applier.applyCleanupCron(name, sshUser);
    totalAdded += cleanupResult.added();
    totalSkipped += cleanupResult.skipped();

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("action", "apply");
      map.put("added", totalAdded);
      map.put("removed", totalRemoved);
      map.put("skipped", totalSkipped);
      if (!warnings.isEmpty()) {
        map.put("warnings", warnings);
      }
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    System.out.println();
    for (var warning : warnings) {
      System.out.println(ansi.string("  @|yellow \u26a0|@ " + warning));
    }
    System.out.println(
        ansi.string(
            "  @|bold,green \u2713 Apply complete:|@ "
                + totalAdded
                + " added, "
                + totalRemoved
                + " removed, "
                + totalSkipped
                + " skipped"));
  }
}
