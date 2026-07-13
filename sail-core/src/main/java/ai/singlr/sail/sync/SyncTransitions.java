/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure detection of real state transitions between two comparable snapshots of a synced entity.
 * Compares only what narration cares about — the {@code status} of a spec, run, or review, and for
 * the review aggregate each stage's status — so a re-applied identical revision, a timestamp churn,
 * or a metadata-only edit yields nothing. A deletion (null after-state) also yields nothing: a
 * tombstone has no lifecycle to narrate. No I/O and no store access; the {@link SyncRpcServer}
 * feeds it the snapshots it already holds around a commit.
 */
public final class SyncTransitions {

  private SyncTransitions() {}

  public static List<SyncTransition> detect(
      String entityType, String entityId, Map<String, Object> before, Map<String, Object> after) {
    if (after == null) {
      return List.of();
    }
    return switch (entityType) {
      case "spec", "run" -> statusChange(entityType, entityId, before, after);
      case "review" -> reviewChanges(entityId, before, after);
      default -> List.of();
    };
  }

  private static List<SyncTransition> statusChange(
      String entityType, String entityId, Map<String, Object> before, Map<String, Object> after) {
    var from = status(before);
    var to = status(after);
    if (to == null || Objects.equals(from, to)) {
      return List.of();
    }
    return List.of(new SyncTransition(entityType, entityId, from, to, after));
  }

  /**
   * The review is an aggregate: its own status transition plus one per stage whose status moved.
   * Stages are matched by id between the two snapshots; a stage new to main transitions from {@code
   * null}, so a debounce-coalesced push (created + started in one commit) still narrates. Each
   * stage transition's snapshot is the stage map plus the review's {@code spec_id}, the key a
   * consumer needs to address the spec the stage belongs to.
   */
  private static List<SyncTransition> reviewChanges(
      String reviewId, Map<String, Object> before, Map<String, Object> after) {
    var transitions = new ArrayList<>(statusChange("review", reviewId, before, after));
    var previous = new LinkedHashMap<String, Map<String, Object>>();
    for (var stage : stagesOf(before)) {
      previous.put(stageKey(stage), stage);
    }
    for (var stage : stagesOf(after)) {
      var from = status(previous.get(stageKey(stage)));
      var to = status(stage);
      if (to == null || Objects.equals(from, to)) {
        continue;
      }
      var context = new LinkedHashMap<>(stage);
      context.put("spec_id", after.get("spec_id"));
      transitions.add(new SyncTransition("review_stage", stageKey(stage), from, to, context));
    }
    return List.copyOf(transitions);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> stagesOf(Map<String, Object> aggregate) {
    if (aggregate == null || !(aggregate.get("stages") instanceof List<?> raw)) {
      return List.of();
    }
    return raw.stream()
        .filter(entry -> entry instanceof Map)
        .map(entry -> (Map<String, Object>) entry)
        .toList();
  }

  private static String stageKey(Map<String, Object> stage) {
    var id = stage.get("id");
    return Objects.toString(id, Objects.toString(stage.get("name"), ""));
  }

  private static String status(Map<String, Object> snapshot) {
    return snapshot == null ? null : Objects.toString(snapshot.get("status"), null);
  }
}
