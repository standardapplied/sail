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
import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.ErrorCode;
import ai.singlr.sail.api.FieldError;
import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.authority.WriteRefused;
import ai.singlr.sail.common.FieldsRefused;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.engine.CliOperator;
import ai.singlr.sail.engine.OperatorCommand;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

  /**
   * Low 10: under {@code --json} a refusal is JSON on stdout in the API's error envelope, its code
   * and its fix in it.
   */
  @Test
  void aRefusalUnderJsonIsJsonOnStdout() {
    var out = System.out;
    var captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      assertThrows(
          CliCommand.Printed.class,
          () ->
              CliCommand.run(
                  true,
                  () -> {
                    throw new ApiException(
                        ErrorCode.CONFLICT, "This box has no ZFS pool.", Remedy.of("Add one."));
                  }));
    } finally {
      System.setOut(out);
    }

    assertEquals(
        "{\"error\": {\"code\": \"conflict\", \"message\": \"This box has no ZFS pool.\","
            + " \"action\": \"Add one.\", \"field_errors\": []}}",
        captured.toString(StandardCharsets.UTF_8).strip());
    assertEquals("", capturedErr.toString(StandardCharsets.UTF_8));
  }

  /** Low 2 of the proof: a JSON refusal's action names each command whole, as this box types it. */
  @Test
  void aJsonRefusalsActionNamesItsCommandAsTyped() {
    var out = System.out;
    var captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      assertThrows(
          CliCommand.Printed.class,
          () ->
              CliCommand.run(
                  true,
                  () -> {
                    throw new ApiException(
                        ErrorCode.CONFLICT,
                        "No token.",
                        Remedy.of("Store one: ", Remedy.sail("git login", "github.com"), "."));
                  }));
    } finally {
      System.setOut(out);
    }

    assertTrue(
        captured
            .toString(StandardCharsets.UTF_8)
            .contains(
                "\"action\": \"Store one: "
                    + OperatorCommand.here()
                        .render(Remedy.of(Remedy.sail("git login", "github.com")))
                    + ".\""),
        captured.toString(StandardCharsets.UTF_8));
    assertTrue(captured.toString(StandardCharsets.UTF_8).contains("sail git login github.com"));
  }

  /**
   * Under {@code --json} a failure sail did not decide is internal, and one with no fix names none;
   * one it refused on purpose is an invalid request, its remedy kept.
   */
  @Test
  void aRefusalWithNoFixUnderJsonIsItsErrorAlone() {
    var out = System.out;
    var captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      assertThrows(
          CliCommand.Printed.class,
          () ->
              CliCommand.run(
                  true,
                  () -> {
                    throw new IllegalStateException("boom");
                  }));
    } finally {
      System.setOut(out);
    }

    assertEquals(
        "{\"error\": {\"code\": \"internal\", \"message\": \"boom\", \"field_errors\": []}}",
        captured.toString(StandardCharsets.UTF_8).strip());
    assertEquals("invalid_request", CliCommand.refusalOf(new CliCommand.Refused("no")).code());
    assertEquals(
        "forbidden_admin_only",
        CliCommand.refusalOf(
                SailApiClient.Refused.of(Map.of("code", "forbidden_admin_only", "message", "No.")))
            .code(),
        "an API refusal keeps the API's code");
    assertEquals(
        "internal",
        CliCommand.refusalOf(SailApiClient.Refused.of(Map.of("message", "No."))).code(),
        "an envelope naming no code is an internal failure");
    var remedied = CliCommand.refusalOf(Remedied.state("Gone.", Remedy.of("Make it.")));
    assertEquals("internal", remedied.code());
    assertEquals("Gone.", remedied.message());
    assertEquals("Make it.", remedied.action());
  }

  @Test
  void aJsonRefusalNamesTheFieldsTheApiWouldName() {
    var refused =
        CliCommand.refusalOf(
            new FieldsRefused("Invalid project name.", Map.of("name", "Invalid project name.")));

    assertEquals("invalid_request", refused.code());
    assertEquals("Invalid project name.", refused.message());
    assertEquals(List.of(FieldError.of("name", "Invalid project name.")), refused.fieldErrors());
    assertEquals(
        "IllegalStateException", CliCommand.refusalOf(new IllegalStateException()).message());
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
                  () ->
                      ProjectDestroyCommand.confirm(
                          false, false, false, "gamma", false, List.of())));
      assertThrows(
          CliCommand.Printed.class,
          () ->
              CliCommand.run(
                  CliCommand.DRY_RUN_HINT,
                  () ->
                      ProjectDestroyCommand.confirm(
                          false, false, true, "gamma", false, List.of())));
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
            + " do.\n  "
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
        "  ✗ Purging project 'acme' is admin-only.\n  Ask an admin.\n",
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
                      ErrorCode.PROJECT_NOT_IN_CATALOG, "Project 'acme' is not in the catalog.");
                }));

    assertEquals(
        "  ✗ Project 'acme' is not in the catalog.\n",
        capturedErr.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aFailureThatWrapsItsCauseAndNamesNoFixSaysTheCause() {
    assertThrows(
        CliCommand.Printed.class,
        () ->
            CliCommand.run(
                () -> {
                  throw new ApiException(
                      ErrorCode.AGENT_LAUNCH_FAILED,
                      "Failed to prepare the agent session.",
                      new IOException("Failed to create branch 'x': fatal: not a git repository"));
                }));

    assertEquals(
        "  ✗ Failed to prepare the agent session: Failed to create branch 'x': fatal: not a git"
            + " repository\n",
        capturedErr.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aCauseLineWalksEveryWrapperThatNamesNoFixAndStopsAtOneThatDoes() {
    var root = new IOException("Cannot run program incus: error=2");
    assertEquals(
        "Failed to prepare the agent session: A sail system command failed: Cannot run program"
            + " incus: error=2",
        CliCommand.describe(
            new ApiException(
                ErrorCode.AGENT_LAUNCH_FAILED,
                "Failed to prepare the agent session.",
                new ApiException(
                    ErrorCode.COMMAND_FAILED, "A sail system command failed.", root))));
    assertEquals(
        "Failed to prepare the agent session: The roster is unsynced. Run "
            + OperatorCommand.here().sail("sync")
            + ".",
        CliCommand.describe(
            new ApiException(
                ErrorCode.AGENT_LAUNCH_FAILED,
                "Failed to prepare the agent session.",
                new ApiException(
                    ErrorCode.CONFLICT,
                    "The roster is unsynced.",
                    Remedy.of("Run ", Remedy.sail("sync"), "."),
                    root))),
        "a wrapper that names a fix ends the line with it, rendered for the reader");
    assertEquals(
        "Failed to prepare the agent session: A sail system command failed.",
        CliCommand.describe(
            new ApiException(
                ErrorCode.AGENT_LAUNCH_FAILED,
                "Failed to prepare the agent session.",
                new ApiException(ErrorCode.COMMAND_FAILED, "A sail system command failed."))),
        "a wrapper that wraps nothing ends the line");
  }

  @Test
  void aCauseThatOnlyRepeatsItsWrappersMessageIsSaidOnce() {
    var unknown = new IllegalArgumentException("Unknown agent CLI: 'x'.");

    assertEquals(
        "Unknown agent CLI: 'x'.",
        CliCommand.describe(ApiException.of(ErrorCode.INVALID_REQUEST, unknown)));
    assertEquals(
        "Failed to prepare the agent session: Unknown agent CLI: 'x'.",
        CliCommand.describe(
            new ApiException(
                ErrorCode.AGENT_LAUNCH_FAILED,
                "Failed to prepare the agent session.",
                ApiException.of(ErrorCode.INVALID_REQUEST, unknown))),
        "a wrapper that repeats its cause is said once, wherever it sits");
    assertEquals(
        "A sail system command failed: Cannot run program incus",
        CliCommand.describe(
            ApiException.of(
                ErrorCode.COMMAND_FAILED,
                new ApiException(
                    ErrorCode.COMMAND_FAILED,
                    "A sail system command failed.",
                    new IOException("Cannot run program incus")))),
        "a wrapper that repeats a wrapper says the inner line once");
    assertEquals(
        "Failed to prepare the agent session: ApiException: IOException",
        CliCommand.describe(
            new ApiException(
                ErrorCode.AGENT_LAUNCH_FAILED,
                "Failed to prepare the agent session.",
                ApiException.of(ErrorCode.COMMAND_FAILED, new IOException()))),
        "a wrapper with no message is named by its type");
  }

  @Test
  @Timeout(value = 1, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void anyFailureThatWrapsItsCauseSaysItsCauseHereAndAtTheTopLevelHandler() {
    var failure =
        new IllegalStateException(
            "Closing the sync health source failed",
            new IllegalStateException("Session is acquired by 1 clients"));
    var line = new CommandLine(new TestCommand());
    var err = new StringWriter();
    line.setErr(new PrintWriter(err));

    var printed =
        assertThrows(
            CliCommand.Printed.class,
            () ->
                CliCommand.run(
                    () -> {
                      throw failure;
                    }));
    assertEquals(1, Main.report(failure, line));

    assertEquals(
        "Closing the sync health source failed: Session is acquired by 1 clients",
        printed.getMessage());
    assertEquals(
        "  ✗ Closing the sync health source failed: Session is acquired by 1 clients\n",
        capturedErr.toString(StandardCharsets.UTF_8));
    assertEquals(
        "Closing the sync health source failed: Session is acquired by 1 clients%n".formatted(),
        err.toString());
  }

  @Test
  void aCauseWithNoMessageIsNamedByItsType() {
    assertThrows(
        CliCommand.Printed.class,
        () ->
            CliCommand.run(
                () -> {
                  throw new ApiException(
                      ErrorCode.COMMAND_FAILED, "A sail system command failed", new IOException());
                }));

    assertEquals(
        "  ✗ A sail system command failed: IOException\n",
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
                ErrorCode.PROJECT_NOT_IN_CATALOG, "Project 'acme' is not in the catalog.")),
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

  @Test
  void jsonAndADryRunAreRefusedTogether() {
    var failure =
        assertThrows(
            IllegalArgumentException.class, () -> CliCommand.requireJsonOrDryRun(true, true));

    assertEquals(
        "--json and --dry-run cannot be combined: dry-run narrates the underlying shell commands"
            + " on stdout, which would corrupt the JSON document. Drop one of the two flags.",
        failure.getMessage());
    CliCommand.requireJsonOrDryRun(true, false);
    CliCommand.requireJsonOrDryRun(false, true);
  }
}
