/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Attaches interactively to a running agent session inside a container. Detects the agent type and
 * delegates to the native resume mechanism: {@code claude --resume} for Claude Code, {@code codex
 * --last} for Codex.
 */
@Command(
    name = "attach",
    description = "Attach to a running agent session.",
    mixinStandardHelpOptions = true)
public final class AgentAttachCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Option(
      names = {"-f", "--file"},
      description = "Path to sail.yaml.",
      defaultValue = "sail.yaml")
  private String file;

  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    CliCommand.run(commandSpec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidProjectName(name);
    var shell = new ShellExecutor(false);
    var state = new ContainerManager(shell).queryState(name);

    switch (state) {
      case ContainerState.Running ignored -> {}
      case ContainerState.Stopped ignored ->
          throw new IllegalStateException(
              "Project '" + name + "' is stopped. Start it with: sail project up " + name);
      case ContainerState.NotCreated ignored ->
          throw new IllegalStateException("Project '" + name + "' does not exist.");
      case ContainerState.Error e ->
          throw new IllegalStateException("Container error: " + e.message());
    }

    var agentType = resolveAgentType();
    var resumeCommand = buildResumeCommand(agentType);

    System.out.println(
        Ansi.AUTO.string(
            "  @|faint Attaching to " + agentType.yamlName() + " session in " + name + "...|@"));

    var command = buildIncusExecWithTty(name, resumeCommand);
    var process = new ProcessBuilder(command).inheritIO().start();
    var exitCode = process.waitFor();
    if (exitCode != 0) {
      System.err.println(
          Ansi.AUTO.string("  @|yellow ⚠|@ Agent session exited with code " + exitCode));
    }
  }

  private AgentCli resolveAgentType() throws IOException {
    var sailYamlPath = SailPaths.resolveSailYaml(name, file);
    if (Files.exists(sailYamlPath)) {
      var config = SailYaml.fromMap(YamlUtil.parseFile(sailYamlPath));
      if (config.agent() != null && config.agent().type() != null) {
        return AgentCli.fromYamlName(config.agent().type());
      }
    }
    return AgentCli.CLAUDE_CODE;
  }

  static List<String> buildResumeCommand(AgentCli agentType) {
    return switch (agentType) {
      case CLAUDE_CODE -> List.of("bash", "-lc", "claude --resume");
      case CODEX -> List.of("bash", "-lc", "codex --last");
    };
  }

  static List<String> buildIncusExecWithTty(String container, List<String> args) {
    var cmd = new ArrayList<String>();
    cmd.add("incus");
    cmd.add("exec");
    cmd.add(container);
    cmd.add("--user");
    cmd.add("1000");
    cmd.add("--group");
    cmd.add("1000");
    cmd.add("--env");
    cmd.add("HOME=/home/dev");
    cmd.add("-t");
    cmd.add("--");
    cmd.addAll(args);
    return List.copyOf(cmd);
  }
}
