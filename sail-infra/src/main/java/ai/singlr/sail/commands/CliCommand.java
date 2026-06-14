/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.Banner;
import java.io.PrintStream;
import java.util.Objects;
import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;

final class CliCommand {

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
      if (Strings.isNotBlank(failureHint)) {
        err.println(failureHint);
      }
      throw new CommandLine.ExecutionException(commandSpec.commandLine(), message, e);
    }
  }

  @FunctionalInterface
  interface CheckedRunnable {
    void run() throws Exception;
  }
}
