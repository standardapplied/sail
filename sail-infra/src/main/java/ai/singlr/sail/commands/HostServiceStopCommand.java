/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.ShellExecutor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "stop", description = "Stop the sail API service.", mixinStandardHelpOptions = true)
public final class HostServiceStopCommand implements Runnable {

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    var installer = HostServiceInstallers.existing(new ShellExecutor(false));
    installer.stop();
    System.out.println(Ansi.AUTO.string("  @|bold,green ✓|@ sail-api stopped."));
  }
}
