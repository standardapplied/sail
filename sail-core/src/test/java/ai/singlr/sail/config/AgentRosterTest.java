/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRosterTest {

  @Test
  void picksTheOtherInstalledAgentForCrossAgentReview() {
    assertEquals("codex", AgentRoster.reviewer("claude-code", List.of("claude-code", "codex")));
    assertEquals("claude-code", AgentRoster.reviewer("codex", List.of("claude-code", "codex")));
  }

  @Test
  void picksTheFirstAgentThatIsNotThePrimary() {
    assertEquals("codex", AgentRoster.reviewer("claude-code", List.of("codex", "claude-code")));
  }

  @Test
  void fallsBackToSelfReviewWhenThePrimaryIsTheOnlyAgent() {
    assertEquals("claude-code", AgentRoster.reviewer("claude-code", List.of("claude-code")));
  }

  @Test
  void returnsNullWhenNoAgentIsInstalled() {
    assertNull(AgentRoster.reviewer("claude-code", List.of()));
    assertNull(AgentRoster.reviewer("claude-code", null));
  }
}
