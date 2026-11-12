/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ShellExecutor;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(
    name = "shell",
    description = "Open an interactive shell inside a project container.",
    mixinStandardHelpOptions = true)
public final class ShellCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() throws Exception {
    var operator = OperatorCommand.here();
    NameValidator.requireValidProjectName(name);
    var shell = new ShellExecutor(false);
    var mgr = new ContainerManager(shell);

    var state = mgr.queryState(name);
    ContainerStateGuard.requireRunning(state, name);
    open(name);
  }

  /** Opens a login shell as the dev user in {@code instance}, and exits with its status. */
  static void open(String instance) throws Exception {
    var pb = new ProcessBuilder(command(instance));
    pb.inheritIO();
    var process = pb.start();
    System.exit(process.waitFor());
  }

  /** The {@code incus exec} that opens a login shell as the dev user in {@code instance}. */
  static List<String> command(String instance) {
    return List.of(
        "incus",
        "exec",
        instance,
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
  }
}
