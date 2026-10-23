/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.HostProvisioner;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsoleProvisionListenerTest {

  private static final String CLEARED = "\r\033[2K";

  @TempDir Path tempDir;

  @Test
  void aStepThatThrowsClearsItsSpinnerAndItsReasonStartsALineOfItsOwn() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("needrestart")
            .onOk("apt-get")
            .onThrow("which incus", new IOException("Command timed out after 300s"));
    var terminal = new ByteArrayOutputStream();
    var printed = new PrintStream(terminal, true, StandardCharsets.UTF_8);
    var originalOut = System.out;
    System.setOut(printed);
    try {
      assertThrows(
          CliCommand.Printed.class,
          () ->
              CliCommand.run(
                  printed,
                  () ->
                      new HostProvisioner(shell, ConsoleProvisionListener.INSTANCE)
                          .provision(
                              "dir",
                              null,
                              "devpool",
                              "incusbr0",
                              null,
                              tempDir.resolve("host.yaml"),
                              tempDir.resolve("sail.db"))));
    } finally {
      System.setOut(originalOut);
    }

    var text = terminal.toString(StandardCharsets.UTF_8);
    assertTrue(text.contains("  [1/8] ✓ a library update never restarts Incus\n"), text);
    assertEquals(
        "  [2/8] ✗ Installing Incus (Zabbly stable)\n  ✗ Command timed out after 300s\n",
        text.substring(text.lastIndexOf(CLEARED) + CLEARED.length()));
  }

  @Test
  void aFailureWithNoStepRunningPrintsNothing() {
    var terminal = new ByteArrayOutputStream();
    var originalOut = System.out;
    System.setOut(new PrintStream(terminal, true, StandardCharsets.UTF_8));
    try {
      ConsoleProvisionListener.INSTANCE.onStepFailed();
    } finally {
      System.setOut(originalOut);
    }

    assertEquals("", terminal.toString(StandardCharsets.UTF_8));
  }
}
