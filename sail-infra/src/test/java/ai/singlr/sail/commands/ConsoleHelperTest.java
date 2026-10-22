/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConsoleHelperTest {

  private final InputStream originalIn = System.in;
  private final PrintStream originalOut = System.out;

  @AfterEach
  void restoreConsoleSupplier() {
    ConsoleHelper.consoleSupplier = System::console;
    ConsoleHelper.terminal = () -> ConsoleHelper.consoleSupplier.get() != null;
    ConsoleHelper.resetStdin();
    System.setIn(originalIn);
    System.setOut(originalOut);
  }

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();

  private void answering(String stdin, boolean terminal) {
    System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    ConsoleHelper.resetStdin();
    ConsoleHelper.terminal = () -> terminal;
  }

  @Test
  void aDestructiveQuestionNobodyCanAnswerIsRefusedNamingTheFlagThatSkipsIt() {
    answering("y\n", false);

    var refused =
        assertThrows(
            CliCommand.Refused.class,
            () -> ConsoleHelper.confirmDestructive("Destroy acme?", "Not destroyed", "--yes"));

    assertEquals(
        "Not destroyed: no terminal to confirm on. Re-run with --yes.", refused.getMessage());
    assertEquals("", out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aDestructiveQuestionOnATerminalDefaultsToNoAndRefuses() {
    answering("\n", true);

    var refused =
        assertThrows(
            CliCommand.Refused.class,
            () -> ConsoleHelper.confirmDestructive("Destroy acme?", "Not destroyed", "--yes"));

    assertEquals("Aborted.", refused.getMessage());
    assertEquals("  Destroy acme? [y/N] ", out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aDestructiveQuestionOnATerminalTakesYes() {
    answering("y\n", true);

    assertDoesNotThrow(
        () -> ConsoleHelper.confirmDestructive("Destroy acme?", "Not destroyed", "--yes"));
  }

  @Test
  void aGoAheadNobodyCanAnswerIsRefusedBeforeAnythingIsAsked() {
    answering("y\n", false);

    var refused =
        assertThrows(
            CliCommand.Refused.class,
            () -> ConsoleHelper.proceed("Proceed with provisioning?", "Not provisioned", "--yes"));

    assertEquals(
        "Not provisioned: no terminal to confirm on. Re-run with --yes.", refused.getMessage());
    assertEquals("", out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aGoAheadDeclinedOnATerminalIsAborted() {
    answering("n\n", true);

    var refused =
        assertThrows(
            CliCommand.Refused.class,
            () -> ConsoleHelper.proceed("Proceed with provisioning?", "Not provisioned", "--yes"));

    assertEquals("Aborted.", refused.getMessage());
    assertEquals("  Proceed with provisioning? [Y/n] ", out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aGoAheadOnATerminalDefaultsToYes() {
    answering("\n", true);

    assertDoesNotThrow(
        () -> ConsoleHelper.proceed("Proceed with provisioning?", "Not provisioned", "--yes"));
  }

  private static final Pattern DECLINED = Pattern.compile("\"[^\"\n]*(Aborted|Cancelled)\\.");

  /**
   * A prompt nobody answered yes refuses through {@link ConsoleHelper}, so the command exits 1: no
   * door prints its own "Aborted." or "Cancelled." and returns as if it had done what was asked.
   */
  @Test
  void noDeclinedPromptEndsACommandAsIfItSucceeded() {
    var declinedHere =
        Stream.of("sail-core", "sail-harness", "sail-infra")
            .map(module -> Path.of("..", module, "src", "main"))
            .flatMap(ConsoleHelperTest::sources)
            .filter(file -> !file.endsWith(Path.of("commands", "ConsoleHelper.java")))
            .filter(file -> DECLINED.matcher(read(file)).find())
            .map(Path::toString)
            .toList();

    assertEquals(List.of(), declinedHere, "a declined prompt that returns normally exits 0");
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
