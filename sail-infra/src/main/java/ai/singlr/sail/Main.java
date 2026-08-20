/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.AutoUpgrader;
import ai.singlr.sail.engine.RemoteCommandRunner;
import ai.singlr.sail.engine.RuntimeMode;
import picocli.CommandLine;

public final class Main {

  private Main() {}

  public static void main(String[] args) {
    if (RuntimeMode.detect() instanceof RuntimeMode.Client client) {
      System.exit(new RemoteCommandRunner(client.config()).execute(args));
    }
    AutoUpgrader.upgradeIfAvailable(args);
    var cmd = new CommandLine(new Sail());
    cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> report(ex, commandLine));
    var exitCode = cmd.execute(args);
    System.exit(exitCode);
  }

  /**
   * Prints an escaped command failure instead of swallowing it (the old handler returned 1 with no
   * output). The message carries the "what happened AND what to do"; a message-less throwable falls
   * back to its type so the user is never left with a silent non-zero exit.
   */
  static int report(Exception ex, CommandLine commandLine) {
    var message = ex.getMessage();
    commandLine
        .getErr()
        .println(
            commandLine
                .getColorScheme()
                .errorText(Strings.isBlank(message) ? ex.toString() : message));
    return 1;
  }
}
