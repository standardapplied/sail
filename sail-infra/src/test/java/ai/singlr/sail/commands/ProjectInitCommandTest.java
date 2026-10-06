/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.harness.Harnesses;
import org.junit.jupiter.api.Test;

class ProjectInitCommandTest {

  @Test
  void thePromptsNameExactlyTheHarnessesSailKnows() {
    assertEquals("Agent type (claude-code/codex/none)", ProjectInitCommand.agentTypePrompt());
    assertEquals("Agent CLI name (claude-code/codex)", ProjectInitCommand.agentCliPrompt());
    assertEquals(
        "Agent type (" + String.join("/", Harnesses.names()) + "/none)",
        ProjectInitCommand.agentTypePrompt(),
        "a harness added to Harnesses.all() is offered here without another edit");
  }
}
