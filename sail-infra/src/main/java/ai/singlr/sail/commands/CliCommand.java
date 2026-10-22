/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.Refusals;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.OperatorCommand;
import java.io.PrintStream;
import java.util.List;
import java.util.Objects;
import picocli.CommandLine.Help.Ansi;

/**
 * Runs a command body and prints its failure once: the message, a refusal's fix, then, for a
 * failure sail did not decide (neither a {@link Refused} nor a client error), any hint, each
 * command in them rendered for the account reading this terminal. The top-level handler ({@link
 * ai.singlr.sail.Main#report}) prints only what was not printed here.
 */
public final class CliCommand {

  /** What a command that can rehearse itself says after a failure sail did not decide. */
  static final String DRY_RUN_HINT =
      "  If this is unexpected, re-run with --dry-run to see what would execute.";

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
      var lines = lines(e, reader());
      err.println(Banner.errorLine(lines.getFirst(), Ansi.AUTO));
      lines.stream().skip(1).forEach(err::println);
      if (Strings.isNotBlank(failureHint) && !decided(e)) {
        err.println(failureHint);
      }
      throw new Printed(lines.getFirst(), e);
    }
  }

  /**
   * Whether sail refused {@code failure} on purpose: a {@link Refused}, or a refusal its rules or
   * its API answered with a client error, a write the journal refused included.
   */
  private static boolean decided(Exception failure) {
    return failure instanceof Refused
        || Refusals.translated(failure) instanceof ApiException refusal
            && refusal.status() / 100 == 4;
  }

  /**
   * A refusal sail raised on purpose, before acting: nothing failed, so {@link #run} follows it
   * with no hint about what would have executed.
   */
  static final class Refused extends IllegalStateException {
    Refused(String message) {
      super(message);
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
   * A failure as the terminal shows it on one line: its message, then a refusal's fix when it names
   * one, a write the journal refused included.
   */
  static String describe(Exception failure) {
    return String.join(" ", lines(failure, reader()));
  }

  /**
   * A failure as {@code reader} reads it: an API refusal's message, then its fix on a line of its
   * own; a failure below the door that names a remedy, its reason and remedy on one line; anything
   * else, its message.
   */
  public static List<String> lines(Exception failure, OperatorCommand reader) {
    var e = Refusals.translated(failure);
    var message = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
    return switch (e) {
      case ApiException refusal when !refusal.remedy().isEmpty() ->
          List.of(message, reader.render(refusal.remedy()));
      case ApiException refusal -> List.of(message);
      case Remedied remedied -> List.of(reader.render(remedied));
      default -> List.of(message);
    };
  }

  private static OperatorCommand reader() {
    return OperatorCommand.here();
  }

  @FunctionalInterface
  interface CheckedRunnable {
    void run() throws Exception;
  }
}
