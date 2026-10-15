/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent state of a multi-step provisioning operation. Written to YAML after each phase
 * completes. Uses {@code String} for phase names (not generics) so serialization works without type
 * erasure issues — the {@link ProvisionTracker} handles {@code String <-> Enum} conversion.
 *
 * @param inputs a digest of what the run provisions from; progress recorded for other inputs, or
 *     with none, is no progress for these
 */
public record ProvisionState(
    String completedPhase,
    String startedAt,
    String updatedAt,
    ProvisionError error,
    String inputs) {
  /** Error details captured when a provisioning phase fails. */
  public record ProvisionError(String failedPhase, String message, String failedAt) {
    @SuppressWarnings("unchecked")
    public static ProvisionError fromMap(Map<String, Object> map) {
      return new ProvisionError(
          (String) map.get("failed_phase"),
          (String) map.get("message"),
          (String) map.get("failed_at"));
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("failed_phase", failedPhase);
      map.put("message", message);
      map.put("failed_at", failedAt);
      return map;
    }
  }

  /** Returns a fresh state for {@code inputs} with no completed phases. */
  public static ProvisionState empty(String inputs) {
    return new ProvisionState(null, null, null, null, inputs);
  }

  @SuppressWarnings("unchecked")
  public static ProvisionState fromMap(Map<String, Object> map) {
    var errorRaw = (Map<String, Object>) map.get("error");
    return new ProvisionState(
        (String) map.get("completed_phase"),
        (String) map.get("started_at"),
        (String) map.get("updated_at"),
        errorRaw != null ? ProvisionError.fromMap(errorRaw) : null,
        (String) map.get("inputs"));
  }

  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("completed_phase", completedPhase);
    map.put("started_at", startedAt);
    map.put("updated_at", updatedAt);
    map.put("error", error != null ? error.toMap() : null);
    map.put("inputs", inputs);
    return map;
  }
}
