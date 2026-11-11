/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.Main;
import ai.singlr.sail.Sail;
import ai.singlr.sail.identity.Actor;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import picocli.CommandLine;

/**
 * Runs {@code sail <args>}, or one of its commands, as {@link Main} runs it, what it printed
 * captured.
 */
final class SailCli {

  /** What {@code sail <args>} exited with and printed to stdout and stderr. */
  record Ran(int exit, String out, String err) {}

  private SailCli() {}

  static Ran run(String... args) {
    return run(new Sail(), args);
  }

  /** What {@code command} run with {@code args} as {@link Main} runs it exited with and printed. */
  static Ran run(Object command, String... args) {
    return run(() -> new CommandLine(command), args);
  }

  /**
   * What {@code command} run with {@code args} exited with and printed, as the host CLI runs it for
   * the box's person {@code operator} resolves.
   */
  static Ran run(Supplier<Actor> operator, Object command, String... args) {
    return run(() -> SailCommandLine.of(command, operator), args);
  }

  /**
   * Runs the command line {@code sail} builds once stdout is captured: picocli writes its help to
   * the stdout it found when the command line was built.
   */
  private static Ran run(Supplier<CommandLine> sail, String... args) {
    var originalOut = System.out;
    var originalErr = System.err;
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      var line = sail.get();
      line.setErr(new PrintWriter(err, true, StandardCharsets.UTF_8));
      line.setExecutionExceptionHandler((failure, cli, parsed) -> Main.report(failure, cli));
      var exit = line.execute(args);
      return new Ran(
          exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
  }
}
