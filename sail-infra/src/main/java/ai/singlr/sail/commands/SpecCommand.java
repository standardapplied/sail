/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import picocli.CommandLine.Command;

@Command(
    name = "spec",
    description = "Manage specs via the Sail control plane.",
    mixinStandardHelpOptions = true,
    subcommands = {
      ApiSpecListCommand.class,
      ApiSpecShowCommand.class,
      ApiSpecCreateCommand.class,
      ApiSpecEditCommand.class,
      ApiSpecContentCommand.class,
      ApiSpecDeleteCommand.class,
      ApiSpecBoardCommand.class,
      DispatchCommand.class,
    })
public final class SpecCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }
}
