/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.PtyHostUnit;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeoutException;
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
    var removed =
        uninstall(
            new ShellExecutor(dryRun),
            HostServiceInstallers.mode(),
            HostServiceInstallers.userHome());
    System.out.println(Ansi.AUTO.string("  @|bold,green ✓|@ Uninstalled " + removed));
  }

  /**
   * Removes sail-api and the pty host, returning the sail-api unit's path. Removing a unit runs no
   * sail, so a box whose installed binary is gone or broken can still be cleaned up.
   */
  static Path uninstall(ShellExec shell, SystemdServiceInstaller.Mode mode, Path userHome)
      throws IOException, InterruptedException, TimeoutException {
    var api = HostServiceInstallers.existing(shell, mode, userHome);
    api.uninstall();
    new PtyHostUnit(shell, mode, userHome, SailPaths.INSTALLED_BINARY).uninstall();
    return api.serviceFilePath();
  }
}
