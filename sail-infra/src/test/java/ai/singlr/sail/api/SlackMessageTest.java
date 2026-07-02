/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlackMessageTest {

  private static Event event(String type) {
    return Event.of("light", "auth", type, "sail", "host");
  }

  private static Event event(String type, Map<String, Object> data) {
    return Event.of("light", "auth", type, "sail", "host", data);
  }

  private static SpecStore.SpecRow spec(String title, String agent) {
    return new SpecStore.SpecRow(
        "auth",
        "light",
        title,
        SpecStatus.PENDING,
        null,
        agent,
        null,
        null,
        null,
        0,
        null,
        "",
        "",
        null,
        List.of(),
        List.of());
  }

  @Test
  void dispatchedRootCarriesSpecTitleProjectBranchAgent() {
    var text =
        SlackMessage.forEvent(
            event("spec_dispatched", Map.of("branch", "feat/auth", "mode", "background")),
            spec("OAuth flow", "claude-code"));

    assertTrue(text.contains("*auth*"));
    assertTrue(text.contains("OAuth flow"));
    assertTrue(text.contains("dispatched"));
    assertTrue(text.contains("project `light`"));
    assertTrue(text.contains("branch `feat/auth`"));
    assertTrue(text.contains("agent `claude-code`"));
  }

  @Test
  void restartedRootSaysRedispatched() {
    var text = SlackMessage.forEvent(event("spec_restarted"), spec("OAuth flow", "codex"));

    assertTrue(text.contains("re-dispatched"));
    assertTrue(text.contains("agent `codex`"));
    assertFalse(text.contains("branch"));
  }

  @Test
  void rootWithoutSpecRowFallsBackToEventAgent() {
    var text = SlackMessage.forEvent(event("spec_dispatched"), null);

    assertTrue(text.contains("*auth*"));
    assertTrue(text.contains("agent `sail`"));
    assertFalse(text.contains("—"));
  }

  @Test
  void rootWithSpecRowWithoutTitleOmitsTitle() {
    var text = SlackMessage.forEvent(event("spec_dispatched"), spec(null, null));

    assertFalse(text.contains("—"));
    assertTrue(text.contains("agent `sail`"));
  }

  @Test
  void rootWithoutSpecIdSaysUnknown() {
    var text =
        SlackMessage.forEvent(Event.of("light", null, "spec_dispatched", "sail", "host"), null);

    assertTrue(text.contains("*unknown*"));
  }

  @Test
  void stoppedCarriesExitCodeAndNote() {
    var text =
        SlackMessage.forEvent(
            event("agent_session_stopped", Map.of("exit_code", 0, "note", "clean run")), null);

    assertEquals("Agent sail stopped (exit 0) — clean run.", text);
  }

  @Test
  void stoppedWithoutExitCodeOrNoteIsBare() {
    var text = SlackMessage.forEvent(event("agent_session_stopped"), null);

    assertEquals("Agent sail stopped.", text);
  }

  @Test
  void agentFailedUsesDetail() {
    var text = SlackMessage.forEvent(event("agent_failed", Map.of("detail", "exit 1")), null);

    assertEquals(":x: Agent failed (exit 1).", text);
  }

  @Test
  void agentFailedWithoutDetailFallsBack() {
    var text = SlackMessage.forEvent(event("agent_failed"), null);

    assertEquals(":x: Agent failed (no exit code).", text);
  }

  @Test
  void guardrailCarriesReasonAndAction() {
    var text =
        SlackMessage.forEvent(
            event("guardrail_triggered", Map.of("reason", "budget", "action", "paused")), null);

    assertEquals(":no_entry: Guardrail triggered. Reason: budget. Action: paused.", text);
  }

  @Test
  void guardrailWithoutDataIsBare() {
    var text = SlackMessage.forEvent(event("guardrail_triggered"), null);

    assertEquals(":no_entry: Guardrail triggered.", text);
  }

  @Test
  void reviewStageStartedNamesStage() {
    var text =
        SlackMessage.forEvent(event("review_stage_started", Map.of("detail", "security")), null);

    assertEquals(":mag: Review started: security.", text);
  }

  @Test
  void reviewStageStartedWithoutDetailFallsBack() {
    var text = SlackMessage.forEvent(event("review_stage_started"), null);

    assertEquals(":mag: Review started: review.", text);
  }

  @Test
  void reviewStagePassedCarriesSeverityCounts() {
    var text =
        SlackMessage.forEvent(
            event(
                "review_stage_passed",
                Map.of("detail", "security", "findings", Map.of("high", 2, "low", 1))),
            null);

    assertEquals(":white_check_mark: Review stage passed: security — 2 high, 1 low.", text);
  }

  @Test
  void reviewStagePassedWithoutFindingsSaysNoFindings() {
    var text =
        SlackMessage.forEvent(event("review_stage_passed", Map.of("detail", "security")), null);

    assertEquals(":white_check_mark: Review stage passed: security (no findings).", text);
  }

  @Test
  void reviewStageFailedOrdersCountsBySeverity() {
    var text =
        SlackMessage.forEvent(
            event(
                "review_stage_failed",
                Map.of(
                    "detail",
                    "security",
                    "findings",
                    Map.of("low", 4, "critical", 1, "medium", 2, "high", 3))),
            null);

    assertEquals(":x: Review stage failed: security — 1 critical, 3 high, 2 medium, 4 low.", text);
  }

  @Test
  void findingsMapWithOnlyUnknownSeveritiesSaysNoFindings() {
    var text =
        SlackMessage.forEvent(
            event("review_stage_failed", Map.of("detail", "s", "findings", Map.of("weird", 9))),
            null);

    assertEquals(":x: Review stage failed: s (no findings).", text);
  }

  @Test
  void reviewCompletedIsSpecDone() {
    var text = SlackMessage.forEvent(event("review_completed"), null);

    assertEquals(":tada: Review passed — spec done.", text);
  }

  @Test
  void reviewErroredCarriesError() {
    var text =
        SlackMessage.forEvent(event("review_errored", Map.of("detail", "agent timed out")), null);

    assertEquals(":warning: Review errored: agent timed out", text);
  }

  @Test
  void reviewErroredWithoutDetailFallsBack() {
    var text = SlackMessage.forEvent(event("review_errored"), null);

    assertEquals(":warning: Review errored: unknown error", text);
  }

  @Test
  void fixIterationStarted() {
    var text = SlackMessage.forEvent(event("review_iteration_started"), null);

    assertEquals(":wrench: Fix iteration started.", text);
  }

  @Test
  void escalatedIsLoud() {
    var text = SlackMessage.forEvent(event("review_escalated"), null);

    assertTrue(text.contains(":rotating_light:"));
    assertTrue(text.contains("human"));
  }

  @Test
  void unknownTypeFallsBackToGeneric() {
    var text = SlackMessage.forEvent(event("custom_thing"), null);

    assertEquals("Event custom_thing from sail.", text);
  }
}
