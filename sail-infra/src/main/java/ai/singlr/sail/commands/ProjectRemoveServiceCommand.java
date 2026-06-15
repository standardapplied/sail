/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.SailYamlUpdater;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ProjectApplier;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "remove",
    description = "Remove an infrastructure service from a running project.",
    mixinStandardHelpOptions = true)
public final class ProjectRemoveServiceCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Parameters(index = "1", description = "Service name to remove.")
  private String serviceName;

  @Option(
      names = {"-f", "--file"},
      description = "Path to sail.yaml project descriptor.",
      defaultValue = "sail.yaml")
  private String file;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

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
    if (config.services() == null || !config.services().containsKey(serviceName)) {
      throw new IllegalStateException(
          "Service '" + serviceName + "' not found in " + singYamlPath.toAbsolutePath());
    }

    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);
    var state = mgr.queryState(name);

    ContainerStateGuard.requireRunning(state, name);

    var applier = new ProjectApplier(shell, out);
    var result = applier.removeServices(name, List.of(serviceName));

    SailYamlUpdater.removeService(singYamlPath, serviceName);

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("action", "remove-service");
      map.put("service", serviceName);
      map.put("removed", result.removed());
      out.println(YamlUtil.dumpJson(map));
      return;
    }

    out.println();
    out.println(
        ansi.string(
            "  @|bold,green \u2713|@ Service '"
                + serviceName
                + "' removed from "
                + name
                + " and sail.yaml updated"));
  }
}
