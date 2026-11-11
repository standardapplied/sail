/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import picocli.CommandLine.Command;

@Command(
    name = "resources",
    description = "Manage project resource allocations.",
    mixinStandardHelpOptions = true,
    subcommands = {
      ProjectResourcesSetCommand.class,
    })
public final class ProjectResourcesCommand implements Runnable {

  @Override
  public void run() {
    SailCommandLine.of(this).usage(System.out);
  }
}
