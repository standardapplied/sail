/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ShellExecutor;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "shell",
    description = "Open an interactive shell inside a project container.",
    mixinStandardHelpOptions = true)
public final class ShellCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidProjectName(name);
    var shell = new ShellExecutor(false);
    var mgr = new ContainerManager(shell);

    var state = mgr.queryState(name);
    switch (state) {
      case ContainerState.Running ignored -> {}
      case ContainerState.Stopped ignored ->
          throw new IllegalStateException(
              "Project '" + name + "' is stopped. Start it with: sudo sail project start " + name);
      case ContainerState.NotCreated ignored ->
          throw new IllegalStateException(
              "Project '" + name + "' does not exist. Run 'sail project apply <name>' first.");
      case ContainerState.Error e ->
          throw new IllegalStateException("Container error: " + e.message());
    }

    var cmd =
        List.of(
            "incus",
            "exec",
            name,
            "-t",
            "--user",
            "1000",
            "--group",
            "1000",
            "--cwd",
            "/home/dev/workspace",
            "--env",
            "HOME=/home/dev",
            "--",
            "bash",
            "-il");
    var pb = new ProcessBuilder(cmd);
    pb.inheritIO();
    var process = pb.start();
    System.exit(process.waitFor());
  }
}
