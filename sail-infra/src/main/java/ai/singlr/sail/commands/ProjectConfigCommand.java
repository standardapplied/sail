/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "config",
    description = "Show a project's definition, or read or write one of its sections.",
    mixinStandardHelpOptions = true,
    subcommands = {
      ProjectConfigShowCommand.class,
      ProjectConfigGetCommand.class,
      ProjectConfigSetCommand.class,
    })
public final class ProjectConfigCommand implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }
}
