/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import picocli.CommandLine.Command;

@Command(
    name = "service",
    description = "Manage infrastructure services on a running project.",
    mixinStandardHelpOptions = true,
    subcommands = {
      ProjectAddServiceCommand.class,
      ProjectRemoveServiceCommand.class,
    })
public final class ProjectServiceCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }
}
