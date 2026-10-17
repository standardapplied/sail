/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.Refusals;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.Banner;
import java.io.PrintStream;
import java.util.Objects;
import picocli.CommandLine.Help.Ansi;

/**
 * Runs a command body and prints its failure once: the message, a refusal's fix, then any hint. The
 * top-level handler ({@link ai.singlr.sail.Main#report}) prints only what was not printed here.
 */
public final class CliCommand {

  private CliCommand() {}

  static void run(CheckedRunnable runnable) {
    run(System.err, null, runnable);
  }

  static void run(String failureHint, CheckedRunnable runnable) {
    run(System.err, failureHint, runnable);
  }

  static void run(PrintStream err, CheckedRunnable runnable) {
    run(err, null, runnable);
  }

  private static void run(PrintStream err, String failureHint, CheckedRunnable runnable) {
    try {
      runnable.run();
    } catch (Exception e) {
      var message = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
      err.println(Banner.errorLine(message, Ansi.AUTO));
      if (Refusals.translated(e) instanceof ApiException refusal
          && Strings.isNotBlank(refusal.failure().action())) {
        err.println(refusal.failure().action());
      }
      if (Strings.isNotBlank(failureHint)) {
        err.println(failureHint);
      }
      throw new Printed(message, e);
    }
  }

  /**
   * A failure {@link #run} printed, carried to the top-level handler as its cause so that handler
   * prints nothing more.
   */
  public static final class Printed extends RuntimeException {
    Printed(String message, Exception cause) {
      super(message, cause);
    }
  }

  /**
   * A failure as the terminal shows it: its message, then a refusal's fix when it names one, a
   * write the journal refused included.
   */
  static String describe(Exception failure) {
    var e = Refusals.translated(failure);
    var message = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
    return e instanceof ApiException refusal && Strings.isNotBlank(refusal.failure().action())
        ? message + " " + refusal.failure().action()
        : message;
  }

  @FunctionalInterface
  interface CheckedRunnable {
    void run() throws Exception;
  }
}
