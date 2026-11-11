/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import picocli.CommandLine.Command;

@Command(
    name = "server",
    description = "Manage the Sail control plane server.",
    mixinStandardHelpOptions = true,
    subcommands = {
      ServerInitCommand.class,
      ServerStartCommand.class,
      ServerStopCommand.class,
      ServerStatusCommand.class,
      ServerTokenCommand.class,
    })
public final class ServerCommand implements Runnable {

  @Override
  public void run() {
    SailCommandLine.of(this).usage(System.out);
  }
}
