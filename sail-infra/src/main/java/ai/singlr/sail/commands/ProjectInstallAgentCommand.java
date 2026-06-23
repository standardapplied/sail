/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ShellExecutor;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "install-agent",
    description = "Install an AI coding agent CLI into a running project container.",
    mixinStandardHelpOptions = true)
public final class ProjectInstallAgentCommand implements Runnable {

  private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(10);

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Parameters(index = "1", description = "Agent CLI to install (claude-code, codex).")
  private String agentName;

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
    var tool = AgentCli.fromYamlName(agentName);

    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);
    var state = mgr.queryState(name);

    switch (state) {
      case ContainerState.Running ignored -> {}
      case ContainerState.Stopped ignored ->
          throw new IllegalStateException(
              "Project '" + name + "' is stopped. Start it with: sail project start " + name);
      case ContainerState.NotCreated ignored ->
          throw new IllegalStateException(
              "Project '"
                  + name
                  + "' does not exist."
                  + "\n  Create it first with: sail project create "
                  + name);
      case ContainerState.Error e ->
          throw new IllegalStateException("Container error: " + e.message());
    }

    var check = shell.exec(ContainerExec.asDevUser(name, List.of("which", tool.binaryName())));
    if (check.ok()) {
      if (json) {
        printJson(tool, "already_installed");
      } else {
        Banner.printBranding(System.out, Ansi.AUTO);
        System.out.println();
        System.out.println(
            Ansi.AUTO.string(
                "  @|bold,green \u2713|@ " + tool.yamlName() + " is already installed in " + name));
      }
      return;
    }

    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
      System.out.println();
      System.out.println(
          Ansi.AUTO.string("  Installing @|bold " + tool.yamlName() + "|@ in " + name + "..."));
    }

    var result =
        shell.exec(
            ContainerExec.asDevUser(name, List.of("bash", "-c", tool.installCommand())),
            null,
            INSTALL_TIMEOUT);
    if (!result.ok()) {
      throw new IOException("Failed to install " + tool.yamlName() + ": " + result.stderr());
    }

    var verify = shell.exec(ContainerExec.asDevUser(name, List.of("which", tool.binaryName())));
    if (!verify.ok()) {
      throw new IOException(
          tool.yamlName()
              + " install command succeeded but '"
              + tool.binaryName()
              + "' not found on PATH.");
    }

    if (json) {
      printJson(tool, "installed");
    } else {
      System.out.println(
          Ansi.AUTO.string(
              "  @|bold,green \u2713 " + tool.yamlName() + " installed successfully.|@"));
    }
  }

  private void printJson(AgentCli tool, String status) {
    var map = new LinkedHashMap<String, Object>();
    map.put("project", name);
    map.put("agent", tool.yamlName());
    map.put("binary", tool.binaryName());
    map.put("status", status);
    System.out.println(YamlUtil.dumpJson(map));
  }
}
