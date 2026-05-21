/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import picocli.CommandLine.Command;

@Command(
    name = "service",
    description = "Manage the sail API systemd user service.",
    mixinStandardHelpOptions = true,
    subcommands = {
      HostServiceInstallCommand.class,
      HostServiceUninstallCommand.class,
      HostServiceStartCommand.class,
      HostServiceStopCommand.class,
      HostServiceRestartCommand.class,
      HostServiceStatusCommand.class,
      HostServiceLogsCommand.class
    })
public final class HostServiceCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }
}
