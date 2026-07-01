/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentUnitTest {

  @Test
  void buildRoleMatchesTheLegacyDispatchPathsExactly() {
    assertEquals("sail-agent", AgentUnit.BUILD.unitName());
    assertEquals("sail-agent.service", AgentUnit.BUILD.service());
    assertEquals("/home/dev/.sail/agent.log", AgentUnit.BUILD.logPath());
    assertEquals("/home/dev/.sail/agent.pid", AgentUnit.BUILD.pidPath());
    assertEquals("/home/dev/.sail/agent-session.json", AgentUnit.BUILD.sessionPath());
    assertEquals("/home/dev/.sail/agent-task.txt", AgentUnit.BUILD.taskPath());
  }

  @Test
  void reviewRoleIsFullyIsolatedFromBuildSoNeitherClobbersTheOther() {
    assertNotEquals(AgentUnit.BUILD.unitName(), AgentUnit.REVIEW.unitName());
    assertNotEquals(AgentUnit.BUILD.logPath(), AgentUnit.REVIEW.logPath());
    assertNotEquals(AgentUnit.BUILD.pidPath(), AgentUnit.REVIEW.pidPath());
    assertNotEquals(AgentUnit.BUILD.sessionPath(), AgentUnit.REVIEW.sessionPath());
    assertNotEquals(AgentUnit.BUILD.taskPath(), AgentUnit.REVIEW.taskPath());
    assertEquals("sail-review", AgentUnit.REVIEW.unitName());
    assertEquals("/home/dev/.sail/review.log", AgentUnit.REVIEW.logPath());
  }

  @Test
  void buildTruncatesItsLogWhileReviewAppendsTheNegotiation() {
    assertFalse(AgentUnit.BUILD.appendsLog(), "each dispatch build starts a fresh agent.log");
    assertTrue(
        AgentUnit.REVIEW.appendsLog(),
        "review.log accumulates the reviewer↔fix negotiation within an attempt");
  }
}
