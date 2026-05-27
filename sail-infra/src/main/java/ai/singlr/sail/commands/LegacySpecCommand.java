/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import picocli.CommandLine.Command;

@Command(
    name = "legacy",
    description = "Container-based spec commands (pre-control-plane).",
    mixinStandardHelpOptions = true,
    subcommands = {
      SpecListCommand.class,
      SpecShowCommand.class,
      SpecCreateCommand.class,
      SpecStatusCommand.class,
      SpecSyncCommand.class,
    })
public final class LegacySpecCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }
}
