/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Main;
import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;

class CliCommandTest {

  private PrintStream originalErr;
  private ByteArrayOutputStream capturedErr;

  @BeforeEach
  void captureErr() {
    originalErr = System.err;
    capturedErr = new ByteArrayOutputStream();
    System.setErr(new PrintStream(capturedErr));
  }

  @AfterEach
  void restoreErr() {
    System.setErr(originalErr);
  }

  @Test
  void wrapsFailuresAsPrinted() {
    var error =
        assertThrows(
            CliCommand.Printed.class,
            () ->
                CliCommand.run(
                    () -> {
                      throw new IllegalStateException("boom");
                    }));

    assertEquals("boom", error.getMessage());
    assertTrue(capturedErr.toString(StandardCharsets.UTF_8).contains("boom"));
  }

  @Test
  void aRefusalIsPrintedOnceWithItsFixThenTheHintAndThrownAsPrinted() {
    var refusal =
        new ApiException(ErrorCode.CONFLICT, "The roster is unsynced.", "Run 'sail sync'.");

    var error =
        assertThrows(
            CliCommand.Printed.class,
            () ->
                CliCommand.run(
                    "  try --dry-run",
                    () -> {
                      throw refusal;
                    }));

    assertEquals(
        "  ✗ The roster is unsynced.\nRun 'sail sync'.\n  try --dry-run\n",
        capturedErr.toString(StandardCharsets.UTF_8));
    assertEquals(refusal, error.getCause());
  }

  @Test
  void aRefusalThatNamesNoFixIsPrintedAsItsMessageAlone() {
    assertThrows(
        CliCommand.Printed.class,
        () ->
            CliCommand.run(
                () -> {
                  throw new ApiException(
                      ErrorCode.PROJECT_DESCRIPTOR_NOT_FOUND,
                      "Project 'acme' is not in the catalog.");
                }));

    assertEquals(
        "  ✗ Project 'acme' is not in the catalog.\n",
        capturedErr.toString(StandardCharsets.UTF_8));
  }

  @Test
  void theTopLevelHandlerPrintsNothingThisRunAlreadyPrinted() {
    var line = new CommandLine(new TestCommand());
    var err = new StringWriter();
    line.setErr(new PrintWriter(err));
    var failure = new IllegalStateException("main's SSH host key changed.");
    var printed =
        assertThrows(
            CliCommand.Printed.class,
            () ->
                CliCommand.run(
                    () -> {
                      throw failure;
                    }));

    assertEquals(failure, printed.getCause());
    assertEquals(1, Main.report(printed, line));
    assertEquals("", err.toString());
    assertEquals(
        "  ✗ main's SSH host key changed.\n", capturedErr.toString(StandardCharsets.UTF_8));
  }

  @Test
  void describesARefusalWithItsFixAndAnythingElseByItsMessage() {
    assertEquals(
        "The roster is unsynced. Run 'sail sync'.",
        CliCommand.describe(
            new ApiException(ErrorCode.CONFLICT, "The roster is unsynced.", "Run 'sail sync'.")));
    assertEquals(
        "Project 'acme' is not in the catalog.",
        CliCommand.describe(
            new ApiException(
                ErrorCode.PROJECT_DESCRIPTOR_NOT_FOUND, "Project 'acme' is not in the catalog.")),
        "a refusal that names no fix is its message alone");
    assertEquals("boom", CliCommand.describe(new IllegalStateException("boom")));
    assertEquals("IllegalStateException", CliCommand.describe(new IllegalStateException()));
  }

  @Test
  void usesExceptionTypeWhenMessageIsMissing() {
    var error =
        assertThrows(
            CliCommand.Printed.class,
            () ->
                CliCommand.run(
                    () -> {
                      throw new IllegalStateException();
                    }));

    assertEquals("IllegalStateException", error.getMessage());
  }

  @Test
  void printsFailureHintsWhenProvided() {
    assertThrows(
        CliCommand.Printed.class,
        () ->
            CliCommand.run(
                "try --dry-run",
                () -> {
                  throw new IllegalStateException("boom");
                }));

    assertTrue(capturedErr.toString(StandardCharsets.UTF_8).contains("try --dry-run"));
  }

  @Test
  void returnsWhenCommandSucceeds() {
    assertDoesNotThrow(() -> CliCommand.run(() -> {}));
    assertEquals("", capturedErr.toString(StandardCharsets.UTF_8));
  }

  @Test
  void writesToCustomErrorStream() {
    var err = new ByteArrayOutputStream();

    assertThrows(
        CliCommand.Printed.class,
        () ->
            CliCommand.run(
                new PrintStream(err),
                () -> {
                  throw new IllegalStateException("custom");
                }));

    assertEquals("", capturedErr.toString(StandardCharsets.UTF_8));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("custom"));
  }

  @Test
  void ignoresBlankFailureHints() {
    assertThrows(
        CliCommand.Printed.class,
        () ->
            CliCommand.run(
                " ",
                () -> {
                  throw new IllegalStateException("boom");
                }));

    assertEquals(1, capturedErr.toString(StandardCharsets.UTF_8).lines().count());
  }

  @Command(name = "test")
  private static final class TestCommand implements Runnable {
    @Override
    public void run() {}
  }
}
