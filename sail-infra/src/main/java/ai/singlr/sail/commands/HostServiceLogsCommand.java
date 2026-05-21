/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.ShellExecutor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "logs",
    description = "Print recent log lines from journalctl for the sail API service.",
    mixinStandardHelpOptions = true)
public final class HostServiceLogsCommand implements Runnable {

  @Option(names = "-n", description = "Number of lines to show.", defaultValue = "200")
  private int lines;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    if (lines <= 0) {
      throw new IllegalArgumentException("-n must be a positive integer.");
    }
    var installer =
        HostServiceInstallers.create(
            new ShellExecutor(false), "127.0.0.1", 7070, HostServiceInstallers.currentUsername());
    System.out.print(installer.journal(lines));
  }
}
