/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
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

  @Test
  void hasConsoleReflectsWhetherATerminalIsAttached() {
    ConsoleHelper.consoleSupplier = () -> null;
    assertFalse(ConsoleHelper.hasConsole(), "no console attached");
  }

  @Test
  void noPromptInAnyModuleAnswersYesWhenNobodyCanAnswerIt() {
    var modules =
        Stream.of("sail-core", "sail-harness", "sail-infra")
            .map(module -> Path.of("..", module, "src", "main"));
    var answeringYes =
        modules
            .flatMap(ConsoleHelperTest::sources)
            .filter(file -> read(file).contains(": \"y\""))
            .map(Path::toString)
            .toList();

    assertEquals(List.of(), answeringYes, "a prompt with no terminal answers \"y\" for nobody");
  }

  private static Stream<Path> sources(Path root) {
    try (var files = Files.walk(root)) {
      return files.filter(file -> file.toString().endsWith(".java")).toList().stream();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
