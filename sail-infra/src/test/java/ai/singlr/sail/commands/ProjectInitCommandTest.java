/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.harness.Harnesses;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.Help.Ansi;

class ProjectInitCommandTest {

  private InputStream originalIn;
  private PrintStream originalOut;
  private final ByteArrayOutputStream asked = new ByteArrayOutputStream();

  @BeforeEach
  void captureTheConsole() {
    originalIn = System.in;
    originalOut = System.out;
    System.setOut(new PrintStream(asked, true, StandardCharsets.UTF_8));
  }

  @AfterEach
  void restoreTheConsole() {
    System.setIn(originalIn);
    System.setOut(originalOut);
    ConsoleHelper.resetStdin();
  }

  private SailYaml.Agent answering(String answers) {
    System.setIn(new ByteArrayInputStream(answers.getBytes(StandardCharsets.UTF_8)));
    ConsoleHelper.resetStdin();
    return ProjectInitCommand.promptAgent(
        new PrintStream(asked, true, StandardCharsets.UTF_8), Ansi.OFF);
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
}
