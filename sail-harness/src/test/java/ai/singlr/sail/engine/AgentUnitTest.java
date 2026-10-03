/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AgentUnitTest {

  private static final String RUN_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String RUN_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

  @Test
  void forRunDerivesTheWholeIdentityUnderTheRunDir() {
    var unit = AgentUnit.forRun(RUN_A);

    assertEquals("sail-agent-" + RUN_A, unit.unitName());
    assertEquals("sail-agent-" + RUN_A + ".service", unit.service());
    assertEquals("/home/dev/.sail/runs/" + RUN_A + "/agent.log", unit.logPath());
    assertEquals("/home/dev/.sail/runs/" + RUN_A + "/agent.pid", unit.pidPath());
    assertEquals("/home/dev/.sail/runs/" + RUN_A + "/agent-session.json", unit.sessionPath());
    assertEquals("/home/dev/.sail/runs/" + RUN_A + "/agent-task.txt", unit.taskPath());
  }

  @Test
  void twoRunsGetFullyDisjointIdentities() {
    var a = AgentUnit.forRun(RUN_A);
    var b = AgentUnit.forRun(RUN_B);

    assertNotEquals(a.unitName(), b.unitName());
    assertNotEquals(a.logPath(), b.logPath());
    assertNotEquals(a.pidPath(), b.pidPath());
    assertNotEquals(a.sessionPath(), b.sessionPath());
    assertNotEquals(a.taskPath(), b.taskPath());
  }

  @Test
  void recordedAddressesSystemdByTheRecordedNameAndFilesByTheCanonicalRunId() {
    var unit = AgentUnit.recorded(RUN_A, "sail-agent-legacy-shape");

    assertEquals("sail-agent-legacy-shape", unit.unitName());
    assertEquals("/home/dev/.sail/runs/" + RUN_A + "/agent-session.json", unit.sessionPath());
  }

  @Test
  void runScopedIdentitiesRejectANonCanonicalRunId() {
    assertThrows(IllegalArgumentException.class, () -> AgentUnit.forRun("../escape"));
    assertThrows(IllegalArgumentException.class, () -> AgentUnit.recorded("$(rm -rf)", "unit"));
  }

  @Test
  void theGuardrailTriggerIsRecordedBesideTheRunsOwnFiles() {
    var unit = AgentUnit.forRun(RUN_A);

    assertEquals(
        AgentUnit.runDir(RUN_A) + "/guardrail-triggered.yaml", unit.guardrailTriggerPath());
    assertNotEquals(
        unit.guardrailTriggerPath(),
        AgentUnit.forRun(RUN_B).guardrailTriggerPath(),
        "two runs in one container never share a trigger file");
  }

  @Test
  void aReviewThatLoggedBeforeEveryLaneWasARunIsStillReadFromItsReviewLog() {
    var legacy = AgentUnit.runDir(RUN_A) + "/review.log";

    assertEquals(legacy, AgentUnit.readableLogPath(RUN_A, legacy));
  }

  @Test
  void aRecordedLogPathOnlyEverChoosesBetweenTheRunsOwnTwoLogs() {
    var own = AgentUnit.forRun(RUN_A).logPath();

    for (var recorded :
        Arrays.asList(
            null,
            "",
            own,
            "/home/dev/.ssh/id_ed25519",
            AgentUnit.runDir(RUN_B) + "/review.log",
            AgentUnit.runDir(RUN_A) + "/../" + RUN_B + "/review.log")) {
      assertEquals(own, AgentUnit.readableLogPath(RUN_A, recorded), String.valueOf(recorded));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentUnit.readableLogPath("../escape", AgentUnit.runDir("../escape") + "/review.log"));
  }
}
