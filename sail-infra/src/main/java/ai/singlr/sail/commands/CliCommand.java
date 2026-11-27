/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.ApiError;
import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.ErrorCode;
import ai.singlr.sail.api.Refusals;
import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.OperatorCommand;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import picocli.CommandLine.Help.Ansi;

/**
 * Runs a command body and prints its failure once: the message, a refusal's fix indented as far as
 * the message's mark, then, for a failure sail did not decide (neither a {@link Refused} nor a
 * client error), any hint, each command in them rendered for the account reading this terminal. The
 * top-level handler ({@link ai.singlr.sail.Main#report}) prints only what was not printed here.
 */
public final class CliCommand {

  /** What a command that can rehearse itself says after a failure sail did not decide. */
  static final String DRY_RUN_HINT =
      "  If this is unexpected, re-run with --dry-run to see what would execute.";

  /** How far a failure's fix is indented: as far as the {@code ✗} of the line above it. */
  private static final String FIX_INDENT = "  ";

  private CliCommand() {}

  /**
   * Refuses {@code --json} with {@code --dry-run}: dry-run narrates the shell commands on stdout,
   * which would corrupt the JSON document.
   */
  static void requireJsonOrDryRun(boolean json, boolean dryRun) {
    if (json && dryRun) {
      throw new IllegalArgumentException(
          "--json and --dry-run cannot be combined: dry-run narrates the underlying shell commands"
              + " on stdout, which would corrupt the JSON document. Drop one of the two flags.");
    }
  }

  static void run(CheckedRunnable runnable) {
    run(System.err, null, runnable);
  }

  static void run(String failureHint, CheckedRunnable runnable) {
    run(System.err, failureHint, runnable);
  }

  static void run(PrintStream err, CheckedRunnable runnable) {
    run(err, null, runnable);
  }

  /**
   * As {@link #run(CheckedRunnable)}, but under {@code --json} ({@code json}) a failure is printed
   * as JSON on stdout in the API's error envelope, {@code {"error": {"code", "message", "action",
   * ...}}} ({@link #refusalOf}), so a caller that reads JSON reads a refusal, and its code, as it
   * reads one from the API; its {@code action} names each command as this box types it.
   */
  static void run(boolean json, CheckedRunnable runnable) {
    if (!json) {
      run(runnable);
      return;
    }
    try {
      runnable.run();
    } catch (Exception e) {
      var refusal = refusalOf(e);
      var error = new LinkedHashMap<>(refusal.toMap());
      if (!refusal.remedy().isEmpty()) {
        error.put("action", reader().render(refusal.remedy()));
      }
      System.out.println(CliJson.stringify(Map.of("error", error)));
      throw new Printed(refusal.message(), e);
    }
  }

  /**
   * {@code failure} as the API states a refusal: an API refusal as it is; one sail made on purpose
   * ({@link #decided}) an invalid request, anything else an internal failure, each as {@link
   * ApiException#of} states it, with the remedy and the fields it names; one with no words named by
   * its type.
   */
  static ApiError refusalOf(Exception failure) {
    if (Refusals.translated(failure) instanceof ApiException refusal) {
      return refusal.error();
    }
    if (failure instanceof SailApiClient.Refused refused && refused.code() != null) {
      return new ApiError(refused.code(), refused.reason(), refused.remedy(), List.of());
    }
    var code = decided(failure) ? ErrorCode.INVALID_REQUEST : ErrorCode.INTERNAL;
    return failure.getMessage() == null
        ? new ApiError(code.code(), failure.getClass().getSimpleName(), Remedy.NONE, List.of())
        : ApiException.of(code, failure).error();
  }

  private static void run(PrintStream err, String failureHint, CheckedRunnable runnable) {
    try {
      runnable.run();
    } catch (Exception e) {
      var lines = lines(e, reader());
      err.println(Banner.errorLine(lines.getFirst(), Ansi.AUTO));
      lines.stream().skip(1).forEach(line -> err.println(FIX_INDENT + line));
      if (Strings.isNotBlank(failureHint) && !decided(e)) {
        err.println(failureHint);
      }
      throw new Printed(lines.getFirst(), e);
    }
  }

  /**
   * Whether sail refused {@code failure} on purpose: a {@link Refused}, an argument it rejected, or
   * a refusal its rules or its API answered with a client error, a write the journal refused
   * included.
   */
  private static boolean decided(Exception failure) {
    return failure instanceof Refused
        || failure instanceof IllegalArgumentException
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
   * own; any other failure as {@link Remedied#told} says it, its causes after it and ending at a
   * fix, so the line says why.
   */
  public static List<String> lines(Exception failure, OperatorCommand reader) {
    var e = Refusals.translated(failure);
    return e instanceof ApiException refusal && !refusal.remedy().isEmpty()
        ? List.of(
            Objects.requireNonNullElse(refusal.getMessage(), refusal.getClass().getSimpleName()),
            reader.render(refusal.remedy()))
        : List.of(Remedied.told(e, reader::render));
  }

  private static OperatorCommand reader() {
    return OperatorCommand.here();
  }

  @FunctionalInterface
  interface CheckedRunnable {
    void run() throws Exception;
  }
}
