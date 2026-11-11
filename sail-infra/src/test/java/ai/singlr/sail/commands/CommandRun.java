/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.identity.Actor;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import picocli.CommandLine;

/** One run of a command: its exit code and what it wrote to stdout and stderr. */
record CommandRun(int exit, String out, String err) {

  /** Runs {@code command} with {@code args}, stdout and stderr captured for the run alone. */
  static CommandRun of(Object command, String... args) {
    return run(new CommandLine(command), args);
  }

  /**
   * Runs {@code command} with {@code args} as the host CLI runs it, reading as the box's person
   * {@code person}, stdout and stderr captured for the run alone.
   */
  static CommandRun as(Actor person, Object command, String... args) {
    return run(SailCommandLine.of(command, () -> person, Set.of()), args);
  }

  private static CommandRun run(CommandLine cli, String... args) {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    var originalOut = System.out;
    var originalErr = System.err;
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      cli.setExecutionExceptionHandler((e, line, parsed) -> 1);
      var exit = cli.execute(args);
      return new CommandRun(
          exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
  }
}
