/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import picocli.CommandLine;

/** One run of a command: its exit code and what it wrote to stdout and stderr. */
record CommandRun(int exit, String out, String err) {

  /** Runs {@code command} with {@code args}, stdout and stderr captured for the run alone. */
  static CommandRun of(Object command, String... args) {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    var originalOut = System.out;
    var originalErr = System.err;
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      var cli = new CommandLine(command);
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
