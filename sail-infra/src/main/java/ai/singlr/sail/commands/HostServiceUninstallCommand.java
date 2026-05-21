/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.ShellExecutor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "uninstall",
    description = "Stop, disable, and remove the sail-api systemd user service.",
    mixinStandardHelpOptions = true)
public final class HostServiceUninstallCommand implements Runnable {

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    var shell = new ShellExecutor(dryRun);
    var installer =
        HostServiceInstallers.create(
            shell, "127.0.0.1", 7070, HostServiceInstallers.currentUsername());

    installer.uninstall();

    System.out.println(
        Ansi.AUTO.string("  @|bold,green ✓|@ Uninstalled " + installer.serviceFilePath()));
  }
}
