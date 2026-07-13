/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import java.util.Map;
import java.util.Objects;

/**
 * One real state transition observed while committing a node's push on main: an entity's {@code
 * status} moved from {@code from} to {@code to}. Detected by {@link SyncTransitions} from the
 * before/after comparable snapshots around a {@link MainReplica#commit}, so a re-applied identical
 * revision produces none. {@code snapshot} is the committed after-state (for a {@code review_stage}
 * transition, the stage's map augmented with the review's {@code spec_id}), giving a consumer
 * everything it needs to narrate the transition without re-reading the store.
 *
 * @param entityType {@code spec}, {@code run}, {@code review}, or {@code review_stage}
 * @param entityId the entity's id ({@code review_stage} carries the stage's id)
 * @param from the prior status, or {@code null} when the entity is new to main
 * @param to the committed status
 * @param snapshot the committed after-state the transition was read from
 */
public record SyncTransition(
    String entityType, String entityId, String from, String to, Map<String, Object> snapshot) {

  public SyncTransition {
    Objects.requireNonNull(entityType, "entityType");
    Objects.requireNonNull(entityId, "entityId");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(snapshot, "snapshot");
  }
}
