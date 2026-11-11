/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "config",
    description = "View or update host configuration.",
    mixinStandardHelpOptions = true,
    subcommands = {HostConfigSetCommand.class})
public final class HostConfigCommand implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }
}
