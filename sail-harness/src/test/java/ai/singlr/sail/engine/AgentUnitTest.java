/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
  void fromRoleResolvesTheLogSelectingRoleNames() {
    assertSame(AgentUnit.BUILD, AgentUnit.fromRole("build"));
    assertSame(AgentUnit.REVIEW, AgentUnit.fromRole("review"));
  }

  @Test
  void fromRoleRejectsUnknownRole() {
    assertThrows(IllegalArgumentException.class, () -> AgentUnit.fromRole("bogus"));
  }

  @Test
  void runScopedLogPathsNameExactlyOneExecutionUnderTheRunDir() {
    assertEquals("/home/dev/.sail/runs/run-1", AgentUnit.runDir("run-1"));
    assertEquals("/home/dev/.sail/runs/run-1/agent.log", AgentUnit.BUILD.runLogPath("run-1"));
    assertEquals("/home/dev/.sail/runs/run-1/review.log", AgentUnit.REVIEW.runLogPath("run-1"));
    assertNotEquals(
        AgentUnit.BUILD.runLogPath("run-1"),
        AgentUnit.BUILD.runLogPath("run-2"),
        "two runs get isolated logs");
  }
}
