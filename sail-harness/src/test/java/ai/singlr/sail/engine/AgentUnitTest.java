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
  void forRunDerivesTheWholeIdentityUnderTheRunDir() {
    var runId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    var unit = AgentUnit.forRun(runId);

    assertEquals("sail-agent-" + runId, unit.unitName());
    assertEquals("sail-agent-" + runId + ".service", unit.service());
    assertEquals("/home/dev/.sail/runs/" + runId + "/agent.log", unit.logPath());
    assertEquals("/home/dev/.sail/runs/" + runId + "/agent.pid", unit.pidPath());
    assertEquals("/home/dev/.sail/runs/" + runId + "/agent-session.json", unit.sessionPath());
    assertEquals("/home/dev/.sail/runs/" + runId + "/agent-task.txt", unit.taskPath());
  }

  @Test
  void twoRunsGetFullyDisjointIdentities() {
    var a = AgentUnit.forRun("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    var b = AgentUnit.forRun("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    assertNotEquals(a.unitName(), b.unitName());
    assertNotEquals(a.logPath(), b.logPath());
    assertNotEquals(a.pidPath(), b.pidPath());
    assertNotEquals(a.sessionPath(), b.sessionPath());
    assertNotEquals(a.taskPath(), b.taskPath());
  }

  @Test
  void recordedAddressesSystemdByTheRecordedNameAndFilesByTheCanonicalRunId() {
    var runId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    var unit = AgentUnit.recorded(runId, "sail-agent-legacy-shape");

    assertEquals("sail-agent-legacy-shape", unit.unitName());
    assertEquals("/home/dev/.sail/runs/" + runId + "/agent-session.json", unit.sessionPath());
  }

  @Test
  void runScopedIdentitiesRejectANonCanonicalRunId() {
    assertThrows(IllegalArgumentException.class, () -> AgentUnit.forRun("../escape"));
    assertThrows(IllegalArgumentException.class, () -> AgentUnit.recorded("$(rm -rf)", "unit"));
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
