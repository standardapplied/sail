/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.sync.SyncTransition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The state-to-event vocabulary: every mapped event matches what the node's own bus used to fire,
 * plus the {@code source=sync} stamp that keeps execution-side reactors away from it.
 */
class SyncTransitionEventsTest {

  private static final Function<String, String> PROJECT_OF_SPEC =
      specId -> "auth".equals(specId) ? "proj" : null;
  private static final Function<String, String> NO_REVIEW = specId -> null;

  private static List<Event> map(SyncTransition transition) {
    return map(transition, NO_REVIEW);
  }

  private static List<Event> map(
      SyncTransition transition, Function<String, String> latestReviewStatus) {
    return SyncTransitionEvents.eventsFor(transition, PROJECT_OF_SPEC, latestReviewStatus, "main");
  }

  private static Map<String, Object> spec(String status) {
    var map = new LinkedHashMap<String, Object>();
    map.put("project", "proj");
    map.put("status", status);
    map.put("branch", "feat/auth");
    return map;
  }

  @Test
  void aPendingToInProgressSpecBecomesTheDispatchRoot() {
    var events =
        map(new SyncTransition("spec", "auth", "pending", "in_progress", spec("in_progress")));

    assertEquals(1, events.size());
    var event = events.getFirst();
    assertEquals(Event.WellKnownTypes.SPEC_DISPATCHED, event.type());
    assertEquals("proj", event.project());
    assertEquals("auth", event.spec());
    assertEquals("main", event.host());
    assertEquals("feat/auth", event.data().get("branch"));
    assertEquals(Event.WellKnownData.SOURCE_SYNC, event.data().get(Event.WellKnownData.SOURCE));
  }

  @Test
  void aSpecNewToMainAlreadyInProgressStillDispatches() {
    var snapshot = spec("in_progress");
    snapshot.remove("branch");

    var events = map(new SyncTransition("spec", "auth", null, "in_progress", snapshot));

    assertEquals(Event.WellKnownTypes.SPEC_DISPATCHED, events.getFirst().type());
    assertNull(events.getFirst().data().get("branch"));
  }

  @Test
  void aFixIterationIsReadFromReviewToInProgressOverAFailedReview() {
    var events =
        map(
            new SyncTransition("spec", "auth", "review", "in_progress", spec("in_progress")),
            specId -> "failed");

    assertEquals(1, events.size());
    assertEquals("review_iteration_started", events.getFirst().type());
  }

  @Test
  void aRestartFromAnEscalatedReviewPostsTheReDispatchPair() {
    var events =
        map(
            new SyncTransition("spec", "auth", "review", "in_progress", spec("in_progress")),
            specId -> "escalated");

    assertEquals(2, events.size());
    assertEquals(Event.WellKnownTypes.SPEC_RESTARTED, events.get(0).type());
    assertEquals("restarted from review", events.get(0).data().get("note"));
    assertEquals(Event.WellKnownTypes.SPEC_DISPATCHED, events.get(1).type());
  }

  @Test
  void aRestartFromAwaitingMergePostsTheReDispatchPair() {
    var events =
        map(
            new SyncTransition(
                "spec", "auth", "awaiting_merge", "in_progress", spec("in_progress")));

    assertEquals(2, events.size());
    assertEquals("restarted from awaiting_merge", events.get(0).data().get("note"));
  }

  @Test
  void aSpecMoveOutOfInProgressIsSilent() {
    assertTrue(
        map(new SyncTransition("spec", "auth", "in_progress", "review", spec("review"))).isEmpty());
  }

  @Test
  void aProjectlessSpecSnapshotIsSilent() {
    var snapshot = new LinkedHashMap<String, Object>(Map.of("status", "in_progress"));

    assertTrue(
        map(new SyncTransition("spec", "auth", "pending", "in_progress", snapshot)).isEmpty());
  }

  private static Map<String, Object> run(String status, Integer exitCode) {
    var map = new LinkedHashMap<String, Object>();
    map.put("project", "proj");
    map.put("spec_id", "auth");
    map.put("agent", "claude-code");
    map.put("status", status);
    map.put("exit_code", exitCode);
    return map;
  }

  @Test
  void aCleanRunCompletionBecomesTheAuthoritativeStop() {
    var events = map(new SyncTransition("run", "r1", "running", "completed", run("completed", 0)));

    assertEquals(1, events.size());
    var stop = events.getFirst();
    assertEquals(Event.WellKnownTypes.AGENT_SESSION_STOPPED, stop.type());
    assertEquals("claude-code", stop.agent());
    assertEquals("r1", stop.data().get(Event.WellKnownData.RUN_ID));
    assertEquals(0, stop.data().get(Event.WellKnownData.EXIT_CODE));
    assertEquals(Event.WellKnownData.SOURCE_SYNC, stop.data().get(Event.WellKnownData.SOURCE));
  }

  @Test
  void aSyncedRoomStopKeepsItsRoleSoLifecycleIgnoresIt() {
    var snapshot = run("completed", 0);
    snapshot.put("role", "room");

    var stop = map(new SyncTransition("run", "r1", "running", "completed", snapshot)).getFirst();

    assertEquals(
        Event.WellKnownData.RUN_ROLE_ROOM,
        stop.data().get(Event.WellKnownData.RUN_ROLE),
        "a room run's terminal stop must carry its role across sync, or SpecLifecycleReactor"
            + " treats the chat as a build stop and advances the spec to review");
  }

  @Test
  void aSyncedBuildStopCarriesNoRoomRole() {
    var stop =
        map(new SyncTransition("run", "r1", "running", "completed", run("completed", 0)))
            .getFirst();
    assertEquals(
        null,
        stop.data().get(Event.WellKnownData.RUN_ROLE),
        "a build stop advances lifecycle exactly as before — only room stops are excluded");
  }

  @Test
  void aNonZeroExitFollowsTheStopWithAgentFailed() {
    var events = map(new SyncTransition("run", "r1", "running", "stopped", run("stopped", 2)));

    assertEquals(2, events.size());
    assertEquals(Event.WellKnownTypes.AGENT_SESSION_STOPPED, events.get(0).type());
    assertEquals(Event.WellKnownTypes.AGENT_FAILED, events.get(1).type());
    assertEquals("exit 2", events.get(1).data().get("detail"));
  }

  @Test
  void aStopWithoutAnExitCodeOmitsIt() {
    var snapshot = run("stopped", null);
    snapshot.remove("agent");

    var events = map(new SyncTransition("run", "r1", null, "stopped", snapshot));

    assertEquals(1, events.size());
    assertEquals(Event.SAIL_AGENT, events.getFirst().agent());
    assertNull(events.getFirst().data().get(Event.WellKnownData.EXIT_CODE));
  }

  @Test
  void aRunLaunchIsSilent() {
    assertTrue(
        map(new SyncTransition("run", "r1", null, "running", run("running", null))).isEmpty());
  }

  @Test
  void aTerminalToTerminalRunMoveIsSilent() {
    assertTrue(
        map(new SyncTransition("run", "r1", "stopped", "completed", run("completed", 0)))
            .isEmpty());
  }

  @Test
  void aProjectlessRunSnapshotIsSilent() {
    var snapshot = new LinkedHashMap<String, Object>(Map.of("status", "completed"));

    assertTrue(map(new SyncTransition("run", "r1", "running", "completed", snapshot)).isEmpty());
  }

  @Test
  void aPostedMessageBecomesASpecMessageEvent() {
    var snapshot =
        Map.<String, Object>of(
            "spec_id", "auth", "author", "ada", "body", "  Progress\n\nupdate  ");

    var events = map(new SyncTransition("message", "m1", null, "posted", snapshot));

    assertEquals(1, events.size());
    var event = events.getFirst();
    assertEquals(Event.WellKnownTypes.SPEC_MESSAGE_POSTED, event.type());
    assertEquals("proj", event.project());
    assertEquals("auth", event.spec());
    assertEquals("ada", event.agent());
    assertEquals("main", event.host());
    assertEquals("m1", event.data().get("message_id"));
    assertEquals("Progress update", event.data().get("preview"));
    assertEquals(Event.WellKnownData.SOURCE_SYNC, event.data().get(Event.WellKnownData.SOURCE));
  }

  @Test
  void aMessageForAnUnknownSpecIsSilent() {
    var snapshot =
        Map.<String, Object>of("spec_id", "ghost", "author", "ada", "body", "Progress update");

    assertTrue(map(new SyncTransition("message", "m1", null, "posted", snapshot)).isEmpty());
  }

  @Test
  void aMessagePreviewIsLimitedByCodePoints() {
    var body = "🚢".repeat(161);
    var snapshot = Map.<String, Object>of("spec_id", "auth", "author", "ada", "body", body);

    var event = map(new SyncTransition("message", "m1", null, "posted", snapshot)).getFirst();

    assertEquals("🚢".repeat(160), event.data().get("preview"));
  }

  private static Map<String, Object> review(String status, String error) {
    var map = new LinkedHashMap<String, Object>();
    map.put("spec_id", "auth");
    map.put("status", status);
    map.put("error", error);
    return map;
  }

  @Test
  void aPassedReviewBecomesReviewCompleted() {
    var events =
        map(new SyncTransition("review", "rev1", "running", "passed", review("passed", null)));

    assertEquals(1, events.size());
    assertEquals("review_completed", events.getFirst().type());
    assertEquals("proj", events.getFirst().project());
    assertEquals("auth", events.getFirst().spec());
  }

  @Test
  void anEscalatedReviewBecomesReviewEscalated() {
    var events =
        map(new SyncTransition("review", "rev1", "failed", "escalated", review("escalated", null)));

    assertEquals("review_escalated", events.getFirst().type());
  }

  @Test
  void anErroredReviewCarriesItsErrorDetail() {
    var events =
        map(
            new SyncTransition(
                "review",
                "rev1",
                "running",
                "failed",
                review("failed", "reviewer quota exceeded")));

    assertEquals("review_errored", events.getFirst().type());
    assertEquals("reviewer quota exceeded", events.getFirst().data().get("detail"));
  }

  @Test
  void aGateFailedReviewIsSilentItsStageAlreadyTold() {
    assertTrue(
        map(new SyncTransition("review", "rev1", "running", "failed", review("failed", null)))
            .isEmpty());
  }

  @Test
  void aReviewCreationIsSilent() {
    assertTrue(
        map(new SyncTransition("review", "rev1", null, "running", review("running", null)))
            .isEmpty());
  }

  @Test
  void aReviewOfAnUnknownSpecIsSilent() {
    var snapshot = review("passed", null);
    snapshot.put("spec_id", "ghost");

    assertTrue(map(new SyncTransition("review", "rev1", "running", "passed", snapshot)).isEmpty());
  }

  @Test
  void aSpecIdLessReviewIsSilent() {
    var snapshot = new LinkedHashMap<String, Object>(Map.of("status", "passed"));

    assertTrue(map(new SyncTransition("review", "rev1", "running", "passed", snapshot)).isEmpty());
  }

  private static Map<String, Object> stage(String status, Map<String, Object> counts) {
    var map = new LinkedHashMap<String, Object>();
    map.put("spec_id", "auth");
    map.put("name", "quality-gate");
    map.put("status", status);
    map.put("finding_counts", counts);
    return map;
  }

  @Test
  void aStartedStageCarriesItsNameAsDetail() {
    var events =
        map(
            new SyncTransition(
                "review_stage", "s1", "pending", "running", stage("running", Map.of())));

    assertEquals("review_stage_started", events.getFirst().type());
    assertEquals("quality-gate", events.getFirst().data().get("detail"));
  }

  @Test
  void aFailedStageCarriesLowercaseCountsInSeverityOrder() {
    var counts = Map.<String, Object>of("MEDIUM", 2, "HIGH", 2, "LOW", 0);

    var events =
        map(new SyncTransition("review_stage", "s1", "running", "failed", stage("failed", counts)));

    assertEquals("review_stage_failed", events.getFirst().type());
    var findings = (Map<?, ?>) events.getFirst().data().get("findings");
    assertEquals(List.of("high", "medium"), List.copyOf(findings.keySet()));
    assertEquals(2, findings.get("high"));
  }

  @Test
  void aPassedStageWithoutFindingsOmitsTheFindingsKey() {
    var events =
        map(
            new SyncTransition(
                "review_stage", "s1", "running", "passed", stage("passed", Map.of())));

    assertEquals("review_stage_passed", events.getFirst().type());
    assertNull(events.getFirst().data().get("findings"));
  }

  @Test
  void aNamelessCountlessFailedStageOmitsDetailAndFindings() {
    var snapshot = stage("failed", Map.of());
    snapshot.remove("name");
    snapshot.remove("finding_counts");

    var events = map(new SyncTransition("review_stage", "s1", "running", "failed", snapshot));

    assertEquals("review_stage_failed", events.getFirst().type());
    assertNull(events.getFirst().data().get("detail"));
    assertNull(events.getFirst().data().get("findings"));
  }

  @Test
  void aStageResetToPendingIsSilent() {
    assertTrue(
        map(new SyncTransition(
                "review_stage", "s1", "running", "pending", stage("pending", Map.of())))
            .isEmpty());
  }

  @Test
  void aStageOfAnUnknownSpecIsSilent() {
    var snapshot = stage("running", Map.of());
    snapshot.put("spec_id", "ghost");

    assertTrue(
        map(new SyncTransition("review_stage", "s1", "pending", "running", snapshot)).isEmpty());
  }

  @Test
  void anUnknownEntityTypeIsSilent() {
    assertTrue(
        map(new SyncTransition("file", "f1", null, "changed", Map.of("status", "changed")))
            .isEmpty());
  }
}
