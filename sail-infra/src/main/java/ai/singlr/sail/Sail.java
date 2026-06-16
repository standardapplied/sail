/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail;

import ai.singlr.sail.commands.AgentCommand;
import ai.singlr.sail.commands.ClientInitCommand;
import ai.singlr.sail.commands.ConflictsCommand;
import ai.singlr.sail.commands.EventsCommand;
import ai.singlr.sail.commands.FdeCommand;
import ai.singlr.sail.commands.GatewayCommand;
import ai.singlr.sail.commands.HostCommand;
import ai.singlr.sail.commands.JoinCommand;
import ai.singlr.sail.commands.LoginCommand;
import ai.singlr.sail.commands.MigrateCommand;
import ai.singlr.sail.commands.ProjectCommand;
import ai.singlr.sail.commands.ServerCommand;
import ai.singlr.sail.commands.SpecCommand;
import ai.singlr.sail.commands.SyncCommand;
import ai.singlr.sail.commands.SyncServerCommand;
import ai.singlr.sail.commands.UpgradeCommand;
import ai.singlr.sail.engine.Banner;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;

@Command(
    name = "sail",
    description = "Isolated dev environments for AI agents.",
    versionProvider = SailVersion.class,
    mixinStandardHelpOptions = true,
    subcommands = {
      ClientInitCommand.class,
      HostCommand.class,
      ProjectCommand.class,
      ServerCommand.class,
      FdeCommand.class,
      LoginCommand.class,
      SpecCommand.class,
      AgentCommand.class,
      EventsCommand.class,
      MigrateCommand.class,
      UpgradeCommand.class,
      GatewayCommand.class,
      SyncCommand.class,
      SyncServerCommand.class,
      ConflictsCommand.class,
      JoinCommand.class,
    })
public final class Sail implements Runnable {

  @Override
  public void run() {
    Banner.printBranding(System.out, Ansi.AUTO);
    System.out.println();
    new CommandLine(this).usage(System.out);
  }
}
