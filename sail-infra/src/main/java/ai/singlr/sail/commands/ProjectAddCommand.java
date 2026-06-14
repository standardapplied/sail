/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import picocli.CommandLine.Command;

@Command(
    name = "add",
    description = "Add services or repos to a running project.",
    mixinStandardHelpOptions = true,
    subcommands = {
      ProjectAddServiceCommand.class,
      ProjectAddRepoCommand.class,
    })
public final class ProjectAddCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }
}
