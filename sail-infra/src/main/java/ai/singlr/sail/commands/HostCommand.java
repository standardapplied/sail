/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import picocli.CommandLine.Command;

@Command(
    name = "host",
    description = "Manage the bare-metal host server.",
    mixinStandardHelpOptions = true,
    subcommands = {
      HostInitCommand.class,
      HostStatusCommand.class,
      HostUpdateCommand.class,
      HostConfigCommand.class,
      HostServiceCommand.class,
      HostSshIdentityCommand.class
    })
public final class HostCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }
}
