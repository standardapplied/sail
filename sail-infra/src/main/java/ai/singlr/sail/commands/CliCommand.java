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
import java.util.Arrays;
import java.util.Objects;
import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Runs a command body and prints its failure once: the message, a refusal's fix, then any hint. The
 * top-level handler ({@link ai.singlr.sail.Main#report}) prints only what was not printed here.
 */
public final class CliCommand {

  private CliCommand() {}

  static void run(CommandSpec commandSpec, CheckedRunnable runnable) {
    run(commandSpec, System.err, null, runnable);
  }

  static void run(CommandSpec commandSpec, String failureHint, CheckedRunnable runnable) {
    run(commandSpec, System.err, failureHint, runnable);
  }

  static void run(CommandSpec commandSpec, PrintStream err, CheckedRunnable runnable) {
    run(commandSpec, err, null, runnable);
  }

  private static void run(
      CommandSpec commandSpec, PrintStream err, String failureHint, CheckedRunnable runnable) {
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
      e.addSuppressed(new Printed());
      throw new CommandLine.ExecutionException(commandSpec.commandLine(), message, e);
    }
  }

  /** Whether {@link #run} already printed {@code failure}. */
  public static boolean printed(Throwable failure) {
    return Arrays.stream(failure.getSuppressed()).anyMatch(Printed.class::isInstance);
  }

  /** Marks a failure {@link #run} printed, riding along with it to the top-level handler. */
  private static final class Printed extends Exception {
    Printed() {
      super("printed by CliCommand.run", null, false, false);
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
