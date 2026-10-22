/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail;

import ai.singlr.sail.api.Refusals;
import ai.singlr.sail.commands.CliCommand;
import ai.singlr.sail.commands.RootRequired;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.AutoUpgrader;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.RemoteCommandRunner;
import ai.singlr.sail.engine.RuntimeMode;
import java.util.Arrays;
import java.util.List;
import picocli.CommandLine;

public final class Main {

  private Main() {}

  public static void main(String[] args) {
    switch (RuntimeMode.detect()) {
      case RuntimeMode.Client client ->
          System.exit(new RemoteCommandRunner(client.config()).execute(args));
      case RuntimeMode.Refused refused when !exempt(args) -> {
        System.err.println(refusal(System.getProperty("user.name"), args));
        System.exit(1);
      }
      default -> {}
    }
    AutoUpgrader.upgradeIfAvailable(args);
    var cmd = new CommandLine(new Sail());
    cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> report(ex, commandLine));
    var exitCode = cmd.execute(args);
    System.exit(exitCode);
  }

  /**
   * Whether picocli reads {@code args} as a request for help or the version, which every account
   * may; a {@code --help} it would pass on as an argument, after {@code --} say, runs the command.
   */
  static boolean exempt(String[] args) {
    try {
      for (var parsed = new CommandLine(new Sail()).parseArgs(args);
          parsed != null;
          parsed = parsed.subcommand()) {
        if (parsed.isUsageHelpRequested() || parsed.isVersionHelpRequested()) {
          return true;
        }
      }
      return false;
    } catch (CommandLine.ParameterException invalid) {
      return false;
    }
  }

  /** What an account that does not own this box is told, with its command to paste back. */
  static String refusal(String user, String[] args) {
    return "sail-api on this box runs as root, so "
        + user
        + "'s sail would see none of its specs, projects or FDEs. Run it with sudo: "
        + RootRequired.rerun(Arrays.asList(args));
  }

  /**
   * Prints an escaped command failure instead of swallowing it (the old handler returned 1 with no
   * output), unless {@link CliCommand#run} already printed it. The message carries the "what
   * happened AND what to do", and a refusal's fix follows it, a write the journal refused included;
   * a message-less throwable falls back to its type so the user is never left with a silent
   * non-zero exit.
   */
  public static int report(Exception thrown, CommandLine commandLine) {
    if (thrown instanceof CliCommand.Printed) {
      return 1;
    }
    var ex = Refusals.translated(thrown);
    var lines =
        Strings.isBlank(ex.getMessage()) && !(ex instanceof Remedied)
            ? List.of(ex.toString())
            : CliCommand.lines(ex, OperatorCommand.here());
    var err = commandLine.getErr();
    err.println(commandLine.getColorScheme().errorText(lines.getFirst()));
    lines.stream().skip(1).forEach(err::println);
    return 1;
  }
}
