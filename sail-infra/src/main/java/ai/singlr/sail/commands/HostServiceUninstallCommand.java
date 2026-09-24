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
import java.util.function.Supplier;
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
            HostServiceInstallers.userHome(),
            SailPaths::installedBinary);
    System.out.println(Ansi.AUTO.string("  @|bold,green ✓|@ Uninstalled " + removed));
  }

  /**
   * Removes sail-api and the pty host, returning the sail-api unit's path. Removing a unit never
   * asks for {@code installedBinary}, so a box whose installed sail is gone or broken can still be
   * cleaned up.
   */
  static Path uninstall(
      ShellExec shell,
      SystemdServiceInstaller.Mode mode,
      Path userHome,
      Supplier<Path> installedBinary)
      throws IOException, InterruptedException, TimeoutException {
    var api = HostServiceInstallers.create(shell, mode, userHome, installedBinary);
    api.uninstall();
    new PtyHostUnit(shell, mode, userHome, installedBinary).uninstall();
    return api.serviceFilePath();
  }
}
