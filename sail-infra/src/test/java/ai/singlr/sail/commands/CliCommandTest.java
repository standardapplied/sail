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
import ai.singlr.sail.authority.Refusal;
import ai.singlr.sail.authority.WriteRefused;
import ai.singlr.sail.engine.CliOperator;
import ai.singlr.sail.engine.OperatorCommand;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
  void aDestroyNobodyCanConfirmIsRefusedWithNoDryRunHint() {
    ConsoleStub.answering("", false);
    try {
      assertThrows(
          CliCommand.Printed.class,
          () ->
              CliCommand.run(
                  CliCommand.DRY_RUN_HINT,
                  () -> ProjectDestroyCommand.confirm(false, false, false, "gamma", false)));
      assertThrows(
          CliCommand.Printed.class,
          () ->
              CliCommand.run(
                  CliCommand.DRY_RUN_HINT,
                  () -> ProjectDestroyCommand.confirm(false, false, true, "gamma", false)));
    } finally {
      ConsoleStub.restore();
    }

    assertEquals(
        "  ✗ Not destroyed: no terminal to confirm on. Re-run with --yes.\n"
            + "  ✗ --json requires --yes (nobody answers a prompt through JSON).\n",
        capturedErr.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aFailureSailDidNotDecideIsFollowedByTheDryRunHint() {
    assertThrows(
        CliCommand.Printed.class,
        () ->
            CliCommand.run(
                CliCommand.DRY_RUN_HINT,
                () -> {
                  throw new IOException("incus delete gamma failed: device busy");
                }));

    assertEquals(
        "  ✗ incus delete gamma failed: device busy\n"
            + "  If this is unexpected, re-run with --dry-run to see what would execute.\n",
        capturedErr.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aRefusalIsPrintedOnceWithItsFixWithNoDryRunHintAndThrownAsPrinted() {
    var refusal = CliOperator.unplaced(null);

    var error =
        assertThrows(
            CliCommand.Printed.class,
            () ->
                CliCommand.run(
                    CliCommand.DRY_RUN_HINT,
                    () -> {
                      throw refusal;
                    }));

    assertEquals(
        "  ✗ This box syncs with main but names no FDE of its own, so it cannot tell what you may"
            + " do.\n"
            + OperatorCommand.here().render(refusal.remedy())
            + "\n",
        capturedErr.toString(StandardCharsets.UTF_8));
    assertEquals(refusal, error.getCause());
  }

  @Test
  void aWriteTheJournalRefusedGetsNoDryRunHint() {
    var refused =
        new WriteRefused(
            new Refusal(
                Refusal.Kind.ADMIN_ONLY, "Purging project 'acme' is admin-only.", "Ask an admin."));

    assertThrows(
        CliCommand.Printed.class,
        () ->
            CliCommand.run(
                CliCommand.DRY_RUN_HINT,
                () -> {
                  throw refused;
                }));

    assertEquals(
        "  ✗ Purging project 'acme' is admin-only.\nAsk an admin.\n",
        capturedErr.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aServerErrorIsFollowedByTheDryRunHint() {
    assertThrows(
        CliCommand.Printed.class,
        () ->
            CliCommand.run(
                CliCommand.DRY_RUN_HINT,
                () -> {
                  throw new ApiException(ErrorCode.CONTAINER_ERROR, "incus delete acme failed.");
                }));

    assertEquals(
        "  ✗ incus delete acme failed.\n" + CliCommand.DRY_RUN_HINT + "\n",
        capturedErr.toString(StandardCharsets.UTF_8));
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

  @Test
  void anArgumentSailRejectedGetsNoDryRunHint() {
    assertThrows(
        CliCommand.Printed.class,
        () ->
            CliCommand.run(
                CliCommand.DRY_RUN_HINT,
                () -> {
                  throw new IllegalArgumentException(
                      "Invalid storage backend: 'btrfs'. Must be 'dir' or 'zfs'.");
                }));

    assertEquals(
        "  ✗ Invalid storage backend: 'btrfs'. Must be 'dir' or 'zfs'.\n",
        capturedErr.toString(StandardCharsets.UTF_8));
  }
}
