/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.Sail;
import ai.singlr.sail.engine.DiskDetector.Candidate;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class HostInitCommandTest {

  private static final List<Candidate> DISKS =
      List.of(
          new Candidate("/dev/sdb", "100G", null, "unused"),
          new Candidate("/dev/sdc", "200G", null, "unused"));

  private final PrintStream originalOut = System.out;
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();

  @AfterEach
  void restoreConsole() {
    ConsoleStub.restore();
    System.setOut(originalOut);
  }

  private void answering(String stdin, boolean terminal) {
    ConsoleStub.answering(stdin, terminal);
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
  }

  @Test
  void anInvalidWorkstationKeyAbortsBeforeAnyProvisioning() {
    var cmd = new CommandLine(new Sail());
    cmd.setOut(new PrintWriter(new StringWriter()));
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exit = cmd.execute("host", "init", "--ssh-public-key", "not-a-real-key", "--dry-run");

    assertNotEquals(0, exit, "a malformed workstation key must fail host init fast");
  }

  @Test
  void provisioningNobodyCanConfirmIsRefusedNamingYes() {
    answering("", false);

    var refused =
        assertThrows(
            CliCommand.Refused.class, () -> HostInitCommand.ask("Proceed with provisioning?"));

    assertEquals(
        "Not provisioned: no terminal to confirm on. Re-run with --yes.", refused.getMessage());
    assertEquals("", out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void provisioningDeclinedOnATerminalIsAborted() {
    answering("n\n", true);

    var refused =
        assertThrows(
            CliCommand.Refused.class, () -> HostInitCommand.ask("Proceed with provisioning?"));

    assertEquals("Aborted.", refused.getMessage());
    assertEquals("  Proceed with provisioning? [Y/n] ", out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aDiskIsChosenByItsNumber() {
    assertEquals("/dev/sdc", HostInitCommand.chosenDisk(" 2 ", DISKS));
  }

  @Test
  void noDiskChosenIsRefusedNamingTheDisksToPassWithDisk() {
    for (var typed : Arrays.asList(null, "", "3", "0", "sdb")) {
      var refused =
          assertThrows(CliCommand.Refused.class, () -> HostInitCommand.chosenDisk(typed, DISKS));

      assertEquals(
          "Not provisioned: no disk chosen. Re-run with --disk and one of: /dev/sdb, /dev/sdc.",
          refused.getMessage(),
          String.valueOf(typed));
    }
  }

  @Test
  void aFailureHostInitDidNotDecideEndsWithTheDryRunHintOnlyWhenRunOnItsOwn() {
    assertEquals(
        "  ✗ Command timed out after 300s\n" + CliCommand.DRY_RUN_HINT + "\n",
        failing(new HostInitCommand()),
        "run on its own, host init has --dry-run to offer");
    assertEquals(
        "  ✗ Command timed out after 300s\n",
        failing(HostInitCommand.initStep()),
        "as a step of sail init, which has no --dry-run, it offers nothing");
  }

  private static String failing(HostInitCommand hostInit) {
    var err = new ByteArrayOutputStream();
    var originalErr = System.err;
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      assertThrows(
          CliCommand.Printed.class,
          () ->
              hostInit.run(
                  () -> {
                    throw new IOException("Command timed out after 300s");
                  }));
    } finally {
      System.setErr(originalErr);
    }
    return err.toString(StandardCharsets.UTF_8);
  }

  @Test
  void anInvalidStorageBackendIsRefusedWithNoDryRunHint() {
    var err = new ByteArrayOutputStream();
    var originalErr = System.err;
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    var cmd = new CommandLine(new Sail());
    cmd.setExecutionExceptionHandler((failure, line, parsed) -> 1);
    int exit;
    try {
      exit = cmd.execute("host", "init", "--storage", "btrfs", "--dry-run");
    } finally {
      System.setErr(originalErr);
    }

    assertEquals(1, exit);
    assertEquals(
        "  ✗ Invalid storage backend: 'btrfs'. Must be 'dir' or 'zfs'.\n",
        err.toString(StandardCharsets.UTF_8));
  }
}
