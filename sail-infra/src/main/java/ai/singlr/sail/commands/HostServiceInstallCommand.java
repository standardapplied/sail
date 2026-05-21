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
    name = "install",
    description =
        "Install the sail-api systemd user service so the API runs as a persistent daemon.",
    mixinStandardHelpOptions = true)
public final class HostServiceInstallCommand implements Runnable {

  @Option(names = "--host", description = "Bind address.", defaultValue = "127.0.0.1")
  private String host;

  @Option(names = "--port", description = "Bind port.", defaultValue = "7070")
  private int port;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    if (ConsoleHelper.isRoot()) {
      throw new IllegalStateException(
          "Do not run 'sail host service install' as root. Run as your dev user — systemd user"
              + " units belong to the invoking user.");
    }
    var shell = new ShellExecutor(dryRun);
    var username = HostServiceInstallers.currentUsername();
    var installer = HostServiceInstallers.create(shell, host, port, username);

    installer.install();

    System.out.println(
        Ansi.AUTO.string("  @|bold,green ✓|@ Installed " + installer.serviceFilePath()));
    System.out.println(
        Ansi.AUTO.string("    @|faint Linked at " + installer.systemdLinkPath() + "|@"));
    System.out.println(
        Ansi.AUTO.string("    @|bold ExecStart:|@ sail api --host " + host + " --port " + port));

    if (!dryRun && !installer.isLingerEnabled()) {
      System.out.println();
      System.out.println(
          Ansi.AUTO.string(
              "  @|yellow Warning:|@ systemd linger is not enabled for '"
                  + username
                  + "'. The service will stop when you log out. Enable with:"));
      System.out.println(Ansi.AUTO.string("    @|bold " + installer.enableLingerCommand() + "|@"));
    }
  }
}
