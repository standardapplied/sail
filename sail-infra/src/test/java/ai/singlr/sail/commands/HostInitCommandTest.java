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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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

  private final InputStream originalIn = System.in;
  private final PrintStream originalOut = System.out;
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();

  @AfterEach
  void restoreConsole() {
    ConsoleHelper.terminal = () -> ConsoleHelper.consoleSupplier.get() != null;
    ConsoleHelper.resetStdin();
    System.setIn(originalIn);
    System.setOut(originalOut);
  }

  private void answering(String stdin, boolean terminal) {
    System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    ConsoleHelper.resetStdin();
    ConsoleHelper.terminal = () -> terminal;
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
}
