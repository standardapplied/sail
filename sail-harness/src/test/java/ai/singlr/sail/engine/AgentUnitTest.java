/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    assertThrows(IllegalArgumentException.class, () -> AgentUnit.forReview("../escape"));
  }

  @Test
  void reviewTemplateKeepsItsFixedFallbackPaths() {
    assertEquals("sail-review", AgentUnit.REVIEW.unitName());
    assertEquals("sail-review.service", AgentUnit.REVIEW.service());
    assertEquals("/home/dev/.sail/review.log", AgentUnit.REVIEW.logPath());
  }

  @Test
  void forReviewDerivesTheWholeIdentityUnderTheReviewsOwnDir() {
    var reviewId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

    var unit = AgentUnit.forReview(reviewId);

    assertEquals("sail-review-" + reviewId, unit.unitName());
    assertEquals("/home/dev/.sail/runs/" + reviewId + "/review.log", unit.logPath());
    assertEquals("/home/dev/.sail/runs/" + reviewId + "/review-prompt.txt", unit.taskPath());
    assertEquals(
        unit.logPath(),
        AgentUnit.REVIEW.runLogPath(reviewId),
        "the review-role log endpoints resolve exactly the file the review writes");
  }

  @Test
  void twoReviewsGetFullyDisjointIdentities() {
    var a = AgentUnit.forReview("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    var b = AgentUnit.forReview("dddddddd-dddd-4ddd-8ddd-dddddddddddd");

    assertNotEquals(a.logPath(), b.logPath());
    assertNotEquals(a.taskPath(), b.taskPath());
  }

  @Test
  void aRunAndAReviewOfTheSameIdNeverShareFiles() {
    var run = AgentUnit.forRun(RUN_A);
    var review = AgentUnit.forReview(RUN_A);

    assertNotEquals(run.unitName(), review.unitName());
    assertNotEquals(run.logPath(), review.logPath());
    assertNotEquals(run.pidPath(), review.pidPath());
    assertNotEquals(run.sessionPath(), review.sessionPath());
    assertNotEquals(run.taskPath(), review.taskPath());
  }

  @Test
  void logPathForRoleResolvesBuildAndAdhocToTheRunsAgentLog() {
    assertEquals(
        "/home/dev/.sail/runs/" + RUN_A + "/agent.log", AgentUnit.logPathForRole("build", RUN_A));
    assertEquals(
        "/home/dev/.sail/runs/" + RUN_A + "/agent.log", AgentUnit.logPathForRole("adhoc", RUN_A));
  }

  @Test
  void logPathForRoleResolvesReviewToTheRunsReviewLog() {
    assertEquals(
        "/home/dev/.sail/runs/" + RUN_A + "/review.log", AgentUnit.logPathForRole("review", RUN_A));
  }

  @Test
  void logPathForRoleRejectsUnknownRole() {
    assertThrows(IllegalArgumentException.class, () -> AgentUnit.logPathForRole("bogus", RUN_A));
  }

  @Test
  void logPathForRoleRejectsANonCanonicalRunId() {
    assertThrows(
        IllegalArgumentException.class, () -> AgentUnit.logPathForRole("build", "../escape"));
  }

  @Test
  void runScopedLogPathsNameExactlyOneExecutionUnderTheRunDir() {
    assertEquals("/home/dev/.sail/runs/" + RUN_A, AgentUnit.runDir(RUN_A));
    assertNotEquals(
        AgentUnit.logPathForRole("build", RUN_A),
        AgentUnit.logPathForRole("build", RUN_B),
        "two runs get isolated logs");
  }
}
