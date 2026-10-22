/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The operator at the keyboard, for a test of what {@link ConsoleHelper} asks: {@code stdin} holds
 * the answers, with a terminal attached or not. A test that answered calls {@link #restore} after.
 */
final class ConsoleStub {

  private static InputStream replaced;

  private ConsoleStub() {}

  static void answering(String stdin, boolean terminal) {
    if (replaced == null) {
      replaced = System.in;
    }
    System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
    ConsoleHelper.resetStdin();
    ConsoleHelper.terminal = () -> terminal;
  }

  static void restore() {
    if (replaced != null) {
      System.setIn(replaced);
      replaced = null;
    }
    ConsoleHelper.terminal = () -> ConsoleHelper.consoleSupplier.get() != null;
    ConsoleHelper.resetStdin();
  }
}
