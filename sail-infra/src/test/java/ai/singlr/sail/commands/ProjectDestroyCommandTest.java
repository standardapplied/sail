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
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProjectDestroyCommandTest {

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
  void aDestroyWithNoTerminalToConfirmOnIsRefused() {
    answering("y\n", false);

    var refused =
        assertThrows(
            CliCommand.Refused.class,
            () -> ProjectDestroyCommand.confirm(false, false, false, "acme", true));

    assertEquals(
        "Not destroyed: no terminal to confirm on. Re-run with --yes.", refused.getMessage());
  }

  @Test
  void aDestroyAnsweredWithAnEmptyLineOnATerminalIsAborted() {
    answering("\n", true);

    var refused =
        assertThrows(
            CliCommand.Refused.class,
            () -> ProjectDestroyCommand.confirm(false, false, false, "acme", false));

    assertEquals("Aborted.", refused.getMessage());

    assertEquals(
        "  Destroy project acme? This cannot be undone. [y/N] ",
        out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aJsonDestroyNeedsYesSinceNobodyAnswersAPromptThroughJson() {
    answering("y\n", true);

    var refused =
        assertThrows(
            CliCommand.Refused.class,
            () -> ProjectDestroyCommand.confirm(false, false, true, "acme", true));

    assertEquals(
        "--json requires --yes (nobody answers a prompt through JSON).", refused.getMessage());
    assertEquals("", out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void yesOrADryRunAsksNothing() {
    answering("", false);

    assertDoesNotThrow(() -> ProjectDestroyCommand.confirm(true, false, false, "acme", true));
    assertDoesNotThrow(() -> ProjectDestroyCommand.confirm(false, true, false, "acme", true));
    assertEquals("", out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void defaultConfirmDoesNotMentionTheOrgCatalog() {
    var prompt = ProjectDestroyCommand.confirmPrompt("acme", false);
    assertTrue(prompt.contains("Destroy project acme"));
    assertFalse(prompt.contains("org catalog"), "a plain destroy is local-only");
  }

  @Test
  void purgeConfirmWarnsItErasesEverythingOnEveryBox() {
    var prompt = ProjectDestroyCommand.confirmPrompt("acme", true);
    assertTrue(prompt.contains("erase it everywhere"));
    assertTrue(prompt.contains("specs, rooms, runs, files and history"));
    assertTrue(prompt.contains("every box"));
    assertTrue(prompt.contains("cannot be undone"));
  }
}
