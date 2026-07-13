/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure transition detection: only a real status move between two snapshots yields anything. */
class SyncTransitionsTest {

  private static Map<String, Object> spec(String status) {
    var map = new LinkedHashMap<String, Object>();
    map.put("project", "proj");
    map.put("status", status);
    map.put("branch", "feat/auth");
    return map;
  }

  @Test
  void aSpecMovingFromPendingToInProgressEmitsOneDispatchTransition() {
    var emitted = SyncTransitions.detect("spec", "auth", spec("pending"), spec("in_progress"));

    assertEquals(1, emitted.size());
    var transition = emitted.getFirst();
    assertEquals("spec", transition.entityType());
    assertEquals("auth", transition.entityId());
    assertEquals("pending", transition.from());
    assertEquals("in_progress", transition.to());
    assertEquals("feat/auth", transition.snapshot().get("branch"));
  }

  @Test
  void anIdenticalReApplyEmitsNothing() {
    assertTrue(SyncTransitions.detect("spec", "auth", spec("review"), spec("review")).isEmpty());
  }

  @Test
  void aBrandNewEntityTransitionsFromNull() {
    var emitted = SyncTransitions.detect("spec", "auth", null, spec("in_progress"));

    assertEquals(1, emitted.size());
    assertNull(emitted.getFirst().from());
    assertEquals("in_progress", emitted.getFirst().to());
  }

  @Test
  void aDeletionEmitsNothing() {
    assertTrue(SyncTransitions.detect("spec", "auth", spec("pending"), null).isEmpty());
  }

  @Test
  void aStatuslessSnapshotEmitsNothing() {
    assertTrue(SyncTransitions.detect("spec", "auth", spec("pending"), Map.of()).isEmpty());
  }

  @Test
  void anUnknownEntityTypeEmitsNothing() {
    assertTrue(SyncTransitions.detect("file", "f1", null, Map.of("status", "changed")).isEmpty());
  }

  @Test
  void aRunCompletingEmitsAnAgentStoppedTransition() {
    var running = Map.<String, Object>of("project", "proj", "status", "running");
    var completed =
        Map.<String, Object>of("project", "proj", "status", "completed", "exit_code", 0);

    var emitted = SyncTransitions.detect("run", "r1", running, completed);

    assertEquals(1, emitted.size());
    assertEquals("running", emitted.getFirst().from());
    assertEquals("completed", emitted.getFirst().to());
    assertEquals(0, emitted.getFirst().snapshot().get("exit_code"));
  }

  @SafeVarargs
  private static Map<String, Object> review(String status, Map<String, Object>... stages) {
    var map = new LinkedHashMap<String, Object>();
    map.put("spec_id", "auth");
    map.put("iteration", 1);
    map.put("status", status);
    map.put("stages", List.of(stages));
    return map;
  }

  private static Map<String, Object> stage(String id, String status, Map<String, Object> counts) {
    var map = new LinkedHashMap<String, Object>();
    map.put("id", id);
    map.put("name", "quality-gate");
    map.put("status", status);
    map.put("finding_counts", counts);
    return map;
  }

  @Test
  void aStageFlippingToFailedCarriesItsCountsAndSpecId() {
    var before = review("running", stage("s1", "running", Map.of()));
    var after = review("running", stage("s1", "failed", Map.of("HIGH", 2, "MEDIUM", 2)));

    var emitted = SyncTransitions.detect("review", "rev1", before, after);

    assertEquals(1, emitted.size());
    var transition = emitted.getFirst();
    assertEquals("review_stage", transition.entityType());
    assertEquals("s1", transition.entityId());
    assertEquals("running", transition.from());
    assertEquals("failed", transition.to());
    assertEquals("auth", transition.snapshot().get("spec_id"));
    assertEquals(Map.of("HIGH", 2, "MEDIUM", 2), transition.snapshot().get("finding_counts"));
  }

  @Test
  void aCoalescedNewReviewEmitsTheReviewAndEachStartedStage() {
    var after = review("running", stage("s1", "running", Map.of()));

    var emitted = SyncTransitions.detect("review", "rev1", null, after);

    assertEquals(2, emitted.size());
    assertEquals("review", emitted.get(0).entityType());
    assertNull(emitted.get(0).from());
    assertEquals("running", emitted.get(0).to());
    assertEquals("review_stage", emitted.get(1).entityType());
    assertNull(emitted.get(1).from());
    assertEquals("running", emitted.get(1).to());
  }

  @Test
  void aReviewPassingEmitsOnlyTheReviewTransitionWhenStagesAreUnchanged() {
    var passedStage = stage("s1", "passed", Map.of());
    var emitted =
        SyncTransitions.detect(
            "review", "rev1", review("running", passedStage), review("passed", passedStage));

    assertEquals(1, emitted.size());
    assertEquals("review", emitted.getFirst().entityType());
    assertEquals("passed", emitted.getFirst().to());
  }

  @Test
  void aSupersededFlagAloneEmitsNothing() {
    var before = review("escalated", stage("s1", "failed", Map.of("HIGH", 1)));
    var after = review("escalated", stage("s1", "failed", Map.of("HIGH", 1)));
    after.put("superseded_at", "2026-07-13T00:00:00Z");

    assertTrue(SyncTransitions.detect("review", "rev1", before, after).isEmpty());
  }

  @Test
  void anIdWithoutAStageIdFallsBackToTheStageName() {
    var nameless = new LinkedHashMap<String, Object>(Map.of("name", "gate", "status", "running"));
    var after = review("running", nameless);

    var emitted = SyncTransitions.detect("review", "rev1", review("running"), after);

    assertEquals(1, emitted.size());
    assertEquals("gate", emitted.getFirst().entityId());
  }

  @Test
  void malformedStagesAreIgnored() {
    var after = new LinkedHashMap<String, Object>();
    after.put("spec_id", "auth");
    after.put("status", "running");
    after.put("stages", List.of("not-a-map"));

    var emitted = SyncTransitions.detect("review", "rev1", null, after);

    assertEquals(1, emitted.size());
    assertEquals("review", emitted.getFirst().entityType());
  }
}
