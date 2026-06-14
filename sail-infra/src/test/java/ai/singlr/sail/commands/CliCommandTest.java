/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
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
  void wrapsFailuresAsExecutionExceptions() {
    var command = new CommandLine(new TestCommand());
    var spec = command.getCommandSpec();

    var error =
        assertThrows(
            CommandLine.ExecutionException.class,
            () ->
                CliCommand.run(
                    spec,
                    () -> {
                      throw new IllegalStateException("boom");
                    }));

    assertEquals("boom", error.getMessage());
    assertTrue(capturedErr.toString(StandardCharsets.UTF_8).contains("boom"));
  }

  @Test
  void usesExceptionTypeWhenMessageIsMissing() {
    var command = new CommandLine(new TestCommand());
    var spec = command.getCommandSpec();

    var error =
        assertThrows(
            CommandLine.ExecutionException.class,
            () ->
                CliCommand.run(
                    spec,
                    () -> {
                      throw new IllegalStateException();
                    }));

    assertEquals("IllegalStateException", error.getMessage());
  }

  @Test
  void printsFailureHintsWhenProvided() {
    var command = new CommandLine(new TestCommand());
    var spec = command.getCommandSpec();

    assertThrows(
        CommandLine.ExecutionException.class,
        () ->
            CliCommand.run(
                spec,
                "try --dry-run",
                () -> {
                  throw new IllegalStateException("boom");
                }));

    assertTrue(capturedErr.toString(StandardCharsets.UTF_8).contains("try --dry-run"));
  }

  @Test
  void returnsWhenCommandSucceeds() {
    var command = new CommandLine(new TestCommand());
    var spec = command.getCommandSpec();

    assertDoesNotThrow(() -> CliCommand.run(spec, () -> {}));
    assertEquals("", capturedErr.toString(StandardCharsets.UTF_8));
  }

  @Test
  void writesToCustomErrorStream() {
    var command = new CommandLine(new TestCommand());
    var spec = command.getCommandSpec();
    var err = new ByteArrayOutputStream();

    assertThrows(
        CommandLine.ExecutionException.class,
        () ->
            CliCommand.run(
                spec,
                new PrintStream(err),
                () -> {
                  throw new IllegalStateException("custom");
                }));

    assertEquals("", capturedErr.toString(StandardCharsets.UTF_8));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("custom"));
  }

  @Test
  void ignoresBlankFailureHints() {
    var command = new CommandLine(new TestCommand());
    var spec = command.getCommandSpec();

    assertThrows(
        CommandLine.ExecutionException.class,
        () ->
            CliCommand.run(
                spec,
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
