/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;

@Command(
    name = "stop",
    description = "Stop the running Sail server.",
    mixinStandardHelpOptions = true)
public final class ServerStopCommand implements Runnable {

  @Override
  public void run() {
    CliCommand.run(
        () -> {
          System.out.println(
              Ansi.AUTO.string(
                  "  @|yellow ⚠|@ Server stop via CLI is not yet implemented. Use Ctrl+C or"
                      + " 'systemctl --user stop sail-server'."));
        });
  }
}
