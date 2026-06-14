/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentSweepCommandTest {

  @Test
  void sweepPromptMentionsDeadImports() {
    assertTrue(AgentSweepCommand.SWEEP_PROMPT.contains("Dead imports"));
  }

  @Test
  void sweepPromptMentionsNamingInconsistencies() {
    assertTrue(AgentSweepCommand.SWEEP_PROMPT.contains("Naming inconsistencies"));
  }

  @Test
  void sweepPromptMentionsTestCoverage() {
    assertTrue(AgentSweepCommand.SWEEP_PROMPT.contains("Test coverage gaps"));
  }

  @Test
  void sweepPromptMentionsDocumentationDrift() {
    assertTrue(AgentSweepCommand.SWEEP_PROMPT.contains("Documentation drift"));
  }

  @Test
  void sweepPromptMentionsSweepReport() {
    assertTrue(AgentSweepCommand.SWEEP_PROMPT.contains("sweep-report.md"));
  }

  @Test
  void sweepPromptMentionsSeparateCommits() {
    assertTrue(AgentSweepCommand.SWEEP_PROMPT.contains("separate commit"));
  }

  @Test
  void sweepPromptDoesNotAddFeatures() {
    assertTrue(AgentSweepCommand.SWEEP_PROMPT.contains("Do NOT add new features"));
  }
}
