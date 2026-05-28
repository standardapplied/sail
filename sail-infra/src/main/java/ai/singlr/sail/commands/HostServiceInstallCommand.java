/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "install",
    description =
        "Install the sail-api systemd service so the API runs as a persistent daemon. Mode is"
            + " picked automatically: system-level (/etc/systemd/system) when invoked as root,"
            + " user-level (~/.config/systemd/user) otherwise.",
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
    var shell = new ShellExecutor(dryRun);
    var username = HostServiceInstallers.currentUsername();
    var installer = HostServiceInstallers.create(shell, host, port, username);

    installer.install();

    var modeLabel =
        installer.mode() == SystemdServiceInstaller.Mode.SYSTEM ? "system-level" : "user-level";
    System.out.println(
        Ansi.AUTO.string(
            "  @|bold,green ✓|@ Installed "
                + installer.serviceFilePath()
                + " @|faint ("
                + modeLabel
                + ")|@"));
    if (installer.systemdLinkPath() != null) {
      System.out.println(
          Ansi.AUTO.string("    @|faint Linked at " + installer.systemdLinkPath() + "|@"));
    }
    System.out.println(
        Ansi.AUTO.string(
            "    @|bold ExecStart:|@ sail server start --host " + host + " --port " + port));

    if (!dryRun
        && installer.mode() == SystemdServiceInstaller.Mode.USER
        && !installer.isLingerEnabled()) {
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
