/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Shared utilities for interactive CLI commands — stdin reading, confirmation prompts, and
 * privilege checks. Not thread-safe; intended for single-threaded CLI use.
 */
final class ConsoleHelper {

  private static BufferedReader stdinReader;

  static Supplier<Console> consoleSupplier = System::console;

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

  /** Returns {@code true} if the current process is running as root. */
  static boolean isRoot() {
    return "root".equals(ProcessHandle.current().info().user().orElse(""));
  }
}
