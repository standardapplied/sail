/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.ShellExecutor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;

@Command(name = "stop", description = "Stop the sail API service.", mixinStandardHelpOptions = true)
public final class HostServiceStopCommand implements Runnable {

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() throws Exception {
    var installer = HostServiceInstallers.create(new ShellExecutor(false));
    installer.stop();
    System.out.println(Ansi.AUTO.string("  @|bold,green ✓|@ sail-api stopped."));
  }
}
