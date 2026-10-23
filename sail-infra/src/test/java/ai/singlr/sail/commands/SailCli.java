/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.Main;
import ai.singlr.sail.Sail;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import picocli.CommandLine;

/** Runs {@code sail <args>} as {@link Main} runs it, what it printed captured. */
final class SailCli {

  /** What {@code sail <args>} exited with and printed to stdout and stderr. */
  record Ran(int exit, String out, String err) {}

  private SailCli() {}

  static Ran run(String... args) {
    var originalOut = System.out;
    var originalErr = System.err;
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      var sail = new CommandLine(new Sail());
      sail.setErr(new PrintWriter(err, true, StandardCharsets.UTF_8));
      sail.setExecutionExceptionHandler((failure, line, parsed) -> Main.report(failure, line));
      var exit = sail.execute(args);
      return new Ran(
          exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
  }
}
