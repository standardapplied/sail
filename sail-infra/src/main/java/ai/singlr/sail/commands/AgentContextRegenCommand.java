/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentContextInstaller;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SpecCliHelper;
import ai.singlr.sail.gen.AgentContextGenerator;
import ai.singlr.sail.gen.GeneratedFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "regen",
    description = "Regenerate the agent context file from sail.yaml.",
    mixinStandardHelpOptions = true)
public final class AgentContextRegenCommand implements Runnable {

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

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidProjectName(name);

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

    var contextFiles = AgentContextGenerator.generateFiles(config);

    if (contextFiles.isEmpty()) {
      throw new IllegalStateException(
          "No agent configured in sail.yaml."
              + "\n  Add an 'agent:' section to generate context files.");
    }

    var pushed = new ArrayList<String>();

    if (dryRun) {
      for (var file : contextFiles) {
        System.out.println(
            "[dry-run] Would push "
                + file.remotePath()
                + " ("
                + file.content().length()
                + " bytes)");
      }
    } else {
      var result = AgentContextInstaller.install(shell, name, config);
      pushed.addAll(result.pushed());
      installSpecCli(shell);
    }

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("action", "regen_context");
      map.put("files", contextFiles.stream().map(GeneratedFile::remotePath).toList());
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    if (dryRun) {
      return;
    }
    Banner.printBranding(System.out, Ansi.AUTO);
    System.out.println();
    if (pushed.isEmpty()) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|faint Nothing to regenerate \u2014 kept every engineer-owned file.|@"));
    }
    for (var path : pushed) {
      System.out.println(
          Ansi.AUTO.string("  @|bold,green \u2713 Agent context regenerated:|@ " + path));
    }
  }

  /**
   * Installs (or refreshes) the in-container {@code spec} CLI so regen doubles as the retrofit for
   * projects created before specs moved to the database. Best-effort: the context files are the
   * primary deliverable, so a failure here only warns.
   */
  private void installSpecCli(ShellExecutor shell) {
    try {
      new SpecCliHelper(shell).install(name);
      if (!json) {
        System.out.println(
            Ansi.AUTO.string("  @|faint Installed the spec CLI (~/.sail/bin/spec)|@"));
      }
    } catch (Exception e) {
      System.err.println(
          Banner.errorLine("Could not install the spec CLI: " + e.getMessage(), Ansi.AUTO));
    }
  }
}
