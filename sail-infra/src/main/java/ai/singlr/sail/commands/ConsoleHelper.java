/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.SailPaths;
import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import picocli.CommandLine.Help.Ansi;

/**
 * Shared utilities for interactive CLI commands — stdin reading, confirmation prompts, and
 * privilege checks. Not thread-safe; intended for single-threaded CLI use.
 */
final class ConsoleHelper {

  private static BufferedReader stdinReader;

  static Supplier<Console> consoleSupplier = System::console;

  /**
   * Whether a terminal is attached. A {@link Console} cannot be made outside the JDK, so a test
   * that answers a prompt on stdin stands a terminal in here.
   */
  static BooleanSupplier terminal = () -> consoleSupplier.get() != null;

  private ConsoleHelper() {}

  /**
   * Prompts the user for confirmation. Returns {@code true} on empty input, 'y', or 'Y'. Returns
   * {@code false} on EOF (null) to prevent destructive operations from proceeding on piped empty
   * stdin.
   */
  static boolean confirm(String prompt) {
    System.out.print("  " + prompt + " [Y/n] ");
    System.out.flush();
    var line = readLine();
    if (line == null) {
      return false;
    }
    return line.isBlank() || line.strip().equalsIgnoreCase("y");
  }

  /** Test-only: clears the cached stdin reader so a swapped {@code System.in} is picked up. */
  static void resetStdin() {
    stdinReader = null;
  }

  /** Reads a single line from stdin, or returns {@code null} on error/EOF. */
  static String readLine() {
    try {
      if (stdinReader == null) {
        stdinReader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      }
      return stdinReader.readLine();
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Reads a password from the console with echo disabled. Throws {@link
   * EchoDisabledUnavailableException} if {@code System.console()} is null — never falls back to
   * echoed input.
   */
  static String readPassword(String prompt) {
    var console = consoleSupplier.get();
    if (console == null) {
      throw new EchoDisabledUnavailableException();
    }
    System.out.print(prompt);
    System.out.flush();
    var chars = console.readPassword();
    return chars != null ? new String(chars) : null;
  }

  /**
   * Prompts the user for confirmation with default NO. Returns {@code true} only on explicit 'y' or
   * 'Y'. Returns {@code false} on empty input or EOF. Use for optional/additive prompts where the
   * safe default is to skip.
   */
  static boolean confirmNo(String prompt) {
    System.out.print("  " + prompt + " [y/N] ");
    System.out.flush();
    var line = readLine();
    if (line == null) {
      return false;
    }
    return line.strip().equalsIgnoreCase("y");
  }

  /**
   * Asks before an action that cannot be undone, defaulting to no. Nobody can answer without a
   * terminal, so it refuses there rather than act on an answer nobody gave, naming {@code notDone}
   * (e.g. "Not deleted") and {@code flag}, the one that skips the question.
   */
  static boolean confirmDestructive(String prompt, String notDone, String flag) {
    if (!hasConsole()) {
      throw new IllegalStateException(
          notDone + ": no terminal to confirm on. Re-run with " + flag + ".");
    }
    return confirmNo(prompt);
  }

  /** Returns {@code true} if the current process is running as root. */
  static boolean isRoot() {
    return SailPaths.isRoot();
  }

  /** Returns {@code true} when a real console is attached (a TTY able to prompt interactively). */
  static boolean hasConsole() {
    return terminal.getAsBoolean();
  }

  /**
   * Prompts with {@code label}, showing {@code def} as a faint default. Returns the stripped input,
   * or {@code def} (never null) when the input is blank.
   */
  static String promptWithDefault(PrintStream out, Ansi ansi, String label, String def) {
    if (!Strings.isEmpty(def)) {
      out.print(ansi.string("  @|bold " + label + "|@ @|faint [" + def + "]|@: "));
    } else {
      out.print(ansi.string("  @|bold " + label + "|@: "));
    }
    out.flush();
    var line = readLine();
    if (Strings.isBlank(line)) {
      return Objects.requireNonNullElse(def, "");
    }
    return line.strip();
  }

  /** Prompts with {@code label} and re-asks until a non-blank value is entered. */
  static String promptRequired(PrintStream out, Ansi ansi, String label) {
    while (true) {
      out.print(ansi.string("  @|bold " + label + "|@: "));
      out.flush();
      var line = readLine();
      if (Strings.isNotBlank(line)) {
        return line.strip();
      }
      out.println(ansi.string("    @|yellow Required field. Please enter a value.|@"));
    }
  }
}
