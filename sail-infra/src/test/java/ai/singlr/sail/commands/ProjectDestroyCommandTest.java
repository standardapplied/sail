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

import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProjectDestroyCommandTest {

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
  void aDestroyWithNoTerminalToConfirmOnIsRefused() {
    answering("y\n", false);

    var refused =
        assertThrows(
            CliCommand.Refused.class,
            () -> ProjectDestroyCommand.confirm(false, false, false, "acme", true, List.of()));

    assertEquals(
        "Not destroyed: no terminal to confirm on. Re-run with --yes.", refused.getMessage());
  }

  @Test
  void aDestroyAnsweredWithAnEmptyLineOnATerminalIsAborted() {
    answering("\n", true);

    var refused =
        assertThrows(
            CliCommand.Refused.class,
            () -> ProjectDestroyCommand.confirm(false, false, false, "acme", false, List.of()));

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
            () -> ProjectDestroyCommand.confirm(false, false, true, "acme", true, List.of()));

    assertEquals(
        "--json requires --yes (nobody answers a prompt through JSON).", refused.getMessage());
    assertEquals("", out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void yesOrADryRunAsksNothing() {
    answering("", false);

    assertDoesNotThrow(
        () -> ProjectDestroyCommand.confirm(true, false, false, "acme", true, List.of()));
    assertDoesNotThrow(
        () -> ProjectDestroyCommand.confirm(false, true, false, "acme", true, List.of()));
    assertEquals("", out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void defaultConfirmDoesNotMentionTheOrgCatalog() {
    var prompt = ProjectDestroyCommand.confirmPrompt("acme", false, List.of());
    assertTrue(prompt.contains("Destroy project acme"));
    assertFalse(prompt.contains("org catalog"), "a plain destroy is local-only");
  }

  @Test
  void purgeConfirmWarnsItErasesEverythingOnEveryBox() {
    var prompt = ProjectDestroyCommand.confirmPrompt("acme", true, List.of());
    assertTrue(prompt.contains("erase it everywhere"));
    assertTrue(prompt.contains("specs, rooms, runs, files and history"));
    assertTrue(prompt.contains("every box"));
    assertTrue(prompt.contains("cannot be undone"));
  }

  @Test
  void destroyAsksForTheProjectWithEachWorkspaceAndItsTemplate() {
    assertEquals(
        "Destroy project acme with workspace fix, template acme--tmpl and their uncommitted work?"
            + " This cannot be undone.",
        ProjectDestroyCommand.confirmPrompt(
            "acme", false, List.of("workspace fix", "template acme--tmpl")));
  }

  @Test
  void destroyTakesTheProjectsWorkspacesAndTemplateAndNothingElse() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "incus list --format json",
                """
                [{"name": "acme", "status": "Running"},
                 {"name": "acme--tmpl", "status": "Stopped", "config": {"user.sail.template.of": "acme"}},
                 {"name": "acme-fix", "status": "Running",
                  "config": {"user.sail.project": "acme", "user.sail.workspace": "fix"}},
                 {"name": "acme-copy", "status": "Running",
                  "config": {"user.sail.project": "acme", "user.sail.workspace": "fix"}},
                 {"name": "bravo-fix", "status": "Running",
                  "config": {"user.sail.project": "bravo", "user.sail.workspace": "fix"}}]
                """);

    assertEquals(
        List.of(
            new ProjectDestroyCommand.Other("acme-fix", "workspace fix"),
            new ProjectDestroyCommand.Other("acme--tmpl", "template acme--tmpl")),
        ProjectDestroyCommand.others("acme", new BoxInstances(shell).all()));
  }
}
