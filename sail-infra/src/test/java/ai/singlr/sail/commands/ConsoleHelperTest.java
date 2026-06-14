/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConsoleHelperTest {

  @AfterEach
  void restoreConsoleSupplier() {
    ConsoleHelper.consoleSupplier = System::console;
  }

  @Test
  void readPasswordThrowsWhenConsoleIsNull() {
    ConsoleHelper.consoleSupplier = () -> null;

    var ex =
        assertThrows(
            EchoDisabledUnavailableException.class, () -> ConsoleHelper.readPassword("Token: "));

    assertTrue(ex.getMessage().contains("System.console() is unavailable"));
  }

  @Test
  void readPasswordNeverFallsBackToEchoedInput() {
    ConsoleHelper.consoleSupplier = () -> null;

    assertThrows(
        EchoDisabledUnavailableException.class, () -> ConsoleHelper.readPassword("Secret: "));
  }
}
