/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import picocli.CommandLine.Command;

@Command(
    name = "repo",
    description = "Manage git repositories on a running project.",
    mixinStandardHelpOptions = true,
    subcommands = {
      ProjectAddRepoCommand.class,
    })
public final class ProjectRepoCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }
}
