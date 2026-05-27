/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import java.util.List;
import java.util.Map;

/**
 * Configurable multi-stage review pipeline. Parsed from the {@code agent.review_pipeline} block in
 * {@code sail.yaml}. Stages execute sequentially; each agent stage produces structured findings
 * evaluated against its gate before advancing.
 */
public record ReviewPipelineConfig(int maxIterations, List<StageConfig> stages) {

  public record StageConfig(
      String name, StageType type, String agent, List<String> categories, Gate gate) {

    @SuppressWarnings("unchecked")
    public static StageConfig fromMap(Map<String, Object> map) {
      var name = (String) map.get("name");
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("review_pipeline stage requires a name");
      }
      var type = StageType.parse((String) map.getOrDefault("type", "agent"));
      var agent = (String) map.get("agent");
      var categories =
          map.containsKey("categories")
              ? ((List<String>) map.get("categories")).stream().map(String::strip).toList()
              : List.<String>of();
      var gate = Gate.parse((String) map.getOrDefault("gate", "no_critical"));
      return new StageConfig(name, type, agent, categories, gate);
    }
  }

  public enum StageType {
    AGENT,
    HUMAN;

    public static StageType parse(String value) {
      return valueOf(value.strip().toUpperCase());
    }
  }

  public enum Gate {
    NO_CRITICAL,
    NO_CRITICAL_OR_HIGH,
    ALL_CLEAR;

    public static Gate parse(String value) {
      return valueOf(value.strip().toUpperCase());
    }

    public boolean passes(List<ai.singlr.sail.store.Finding> findings) {
      return switch (this) {
        case NO_CRITICAL ->
            findings.stream()
                .filter(f -> f.resolution() == ai.singlr.sail.store.Finding.Resolution.OPEN)
                .noneMatch(f -> f.severity() == ai.singlr.sail.store.Finding.Severity.CRITICAL);
        case NO_CRITICAL_OR_HIGH ->
            findings.stream()
                .filter(f -> f.resolution() == ai.singlr.sail.store.Finding.Resolution.OPEN)
                .noneMatch(f -> f.severity().isAtLeast(ai.singlr.sail.store.Finding.Severity.HIGH));
        case ALL_CLEAR ->
            findings.stream()
                .noneMatch(f -> f.resolution() == ai.singlr.sail.store.Finding.Resolution.OPEN);
      };
    }
  }

  @SuppressWarnings("unchecked")
  public static ReviewPipelineConfig fromMap(Map<String, Object> map) {
    var maxIterations =
        map.containsKey("max_iterations") ? ((Number) map.get("max_iterations")).intValue() : 3;
    var stagesList = (List<Map<String, Object>>) map.getOrDefault("stages", List.of());
    var stages = stagesList.stream().map(StageConfig::fromMap).toList();
    return new ReviewPipelineConfig(maxIterations, stages);
  }

  public List<StageConfig> agentStages() {
    return stages.stream().filter(s -> s.type() == StageType.AGENT).toList();
  }

  public List<StageConfig> humanStages() {
    return stages.stream().filter(s -> s.type() == StageType.HUMAN).toList();
  }
}
