/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.gen.SailYamlGenerator;
import ai.singlr.sail.harness.Harnesses;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine.Help.Ansi;

class ProjectInitCommandTest {

  private PrintStream originalOut;
  private final ByteArrayOutputStream asked = new ByteArrayOutputStream();

  @BeforeEach
  void captureTheConsole() {
    originalOut = System.out;
    System.setOut(new PrintStream(asked, true, StandardCharsets.UTF_8));
  }

  @AfterEach
  void restoreTheConsole() {
    ConsoleStub.restore();
    System.setOut(originalOut);
  }

  private ProjectDefinition.Agent answering(String answers) {
    ConsoleStub.answering(answers, true);
    return ProjectInitCommand.promptAgent(
        new PrintStream(asked, true, StandardCharsets.UTF_8), Ansi.OFF);
  }

  @Test
  void anOverwriteNobodyCanConfirmIsRefusedAndWritesNothing(@TempDir Path dir) throws Exception {
    var existing = Files.writeString(dir.resolve("sail.yaml"), "name: acme\n");
    ConsoleStub.answering("y\n", false);
    try {
      var refused =
          assertThrows(
              CliCommand.Refused.class, () -> ProjectInitCommand.confirmOverwrite(existing));

      assertEquals(
          "Not written: no terminal to confirm on. Re-run with --output <another path>.",
          refused.getMessage());
      assertEquals("", asked.toString(StandardCharsets.UTF_8));
    } finally {
      ConsoleStub.restore();
    }
  }

  @Test
  void aNewFileNeedsNoConfirming(@TempDir Path dir) {
    assertDoesNotThrow(() -> ProjectInitCommand.confirmOverwrite(dir.resolve("sail.yaml")));
  }

  @Test
  void thePromptsNameExactlyTheHarnessesSailKnows() {
    assertEquals("Agent type (claude-code/codex/none)", ProjectInitCommand.agentTypePrompt());
    assertEquals("Agent CLI name (claude-code/codex)", ProjectInitCommand.agentCliPrompt());
  }

  @Test
  void anEmptyAnswerTakesTheDefaultHarnessWhichThePromptOffers() {
    var agent = answering("\nn\n");

    assertEquals(Harnesses.DEFAULT.yamlName(), agent.type());
    assertNull(agent.install(), "no additional harness was asked for");
    assertTrue(
        asked
            .toString(StandardCharsets.UTF_8)
            .startsWith("  Agent type (claude-code/codex/none) [claude-code]: "),
        asked.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aProjectThatWantsNoAgentGetsNoAgentBlock() {
    assertNull(answering("none\n"));
    assertNull(answering("NONE\n"));
  }

  @Test
  void theHarnessesToInstallBesideTheFirstAreAskedForByName() {
    var agent = answering("codex\ny\n\nclaude-code\ny\ncodex\nn\n");

    assertEquals("codex", agent.type());
    assertEquals(
        List.of("codex", "claude-code"),
        agent.install(),
        "the project's own harness leads, and naming it again adds nothing");
    assertTrue(
        asked.toString(StandardCharsets.UTF_8).contains("  Agent CLI name (claude-code/codex): "));
  }

  @Test
  void sshAccessAsksOnlyForKeysAndWritesDev() {
    ConsoleStub.answering("y\nssh-ed25519 AAAA alice@laptop\nn\n", true);

    var ssh =
        ProjectInitCommand.promptSsh(
            new PrintStream(asked, true, StandardCharsets.UTF_8), Ansi.OFF);

    assertEquals(new ProjectDefinition.Ssh("dev", List.of("ssh-ed25519 AAAA alice@laptop")), ssh);
    assertFalse(asked.toString(StandardCharsets.UTF_8).contains("SSH user"));
    var config =
        new ProjectDefinition(
            "acme",
            null,
            new ProjectDefinition.Resources(2, "4GB", "50GB"),
            "ubuntu/24.04",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            ssh);
    assertTrue(
        SailYamlGenerator.generate(config)
            .contains("ssh:\n  user: dev\n  authorized_keys:\n    - "),
        SailYamlGenerator.generate(config));
  }

  private Map<String, ProjectDefinition.Process> app(
      String answers, ProjectDefinition.Runtimes runtimes, List<ProjectDefinition.Repo> repos) {
    ConsoleStub.answering(answers, true);
    return ProjectInitCommand.promptApp(
        new PrintStream(asked, true, StandardCharsets.UTF_8), Ansi.OFF, runtimes, repos);
  }

  @Test
  void anAppIsAskedForOnlyWhenARuntimeImpliesOneAndRunsFromTheFirstRepo() {
    var node = new ProjectDefinition.Runtimes(0, "22", null);
    var java = new ProjectDefinition.Runtimes(25, null, "3.9.14");
    var repo = List.of(new ProjectDefinition.Repo("https://github.com/acme/web.git", "web", null));

    assertNull(app("", null, repo));
    assertNull(app("", new ProjectDefinition.Runtimes(0, null, "3.9.14"), repo));
    assertEquals("", asked.toString(StandardCharsets.UTF_8), "nothing was asked");
    assertEquals(
        Map.of("app", new ProjectDefinition.Process("npm run dev", "web", 3000, Map.of())),
        app("npm run dev\n\n", node, repo));
    assertTrue(
        asked
            .toString(StandardCharsets.UTF_8)
            .contains("  Command that starts it (blank to skip): "),
        asked.toString(StandardCharsets.UTF_8));
    assertEquals(
        Map.of("app", new ProjectDefinition.Process("mvn spring-boot:run", null, 9090, Map.of())),
        app("mvn spring-boot:run\n9090\n", java, null));
    assertNull(app("\n", node, repo), "a blank command skips the app");
  }
}
