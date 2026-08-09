/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.store.Finding;
import java.util.List;
import java.util.Map;

/**
 * Configurable multi-stage review pipeline. Parsed from the {@code agent.review_pipeline} block in
 * {@code sail.yaml}. Stages execute sequentially; each agent stage produces structured findings
 * evaluated against its gate before advancing.
 *
 * @param maxFindingAge how many fix iterations a gate-blocking finding may survive before the loop
 *     escalates it as stuck — a convergence measure, unlike {@code maxIterations}' blind budget: a
 *     loop resolving old findings while new ones surface keeps running; a loop replaying the same
 *     finding stops here
 */
public record ReviewPipelineConfig(int maxIterations, int maxFindingAge, List<StageConfig> stages) {

  public ReviewPipelineConfig {
    if (stages.stream().map(StageConfig::name).distinct().count() != stages.size()) {
      throw new IllegalArgumentException(
          "review_pipeline stage names must be unique — carry-forward is keyed by stage name;"
              + " rename the duplicate stage in sail.yaml");
    }
  }

  public record StageConfig(
      String name, StageType type, String agent, List<String> categories, Gate gate) {

    @SuppressWarnings("unchecked")
    public static StageConfig fromMap(Map<String, Object> map) {
      var name = (String) map.get("name");
      if (Strings.isBlank(name)) {
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

    public boolean passes(List<Finding> findings) {
      return findings.stream().noneMatch(this::blocks);
    }

    /**
     * Whether this single finding trips the gate. Only {@code OPEN} findings block — a disputed
     * finding is excluded from the gate and surfaces in the room verdict for the human instead.
     */
    public boolean blocks(Finding finding) {
      if (finding.resolution() != Finding.Resolution.OPEN) {
        return false;
      }
      return switch (this) {
        case NO_CRITICAL -> finding.severity() == Finding.Severity.CRITICAL;
        case NO_CRITICAL_OR_HIGH -> finding.severity().isAtLeast(Finding.Severity.HIGH);
        case ALL_CLEAR -> true;
      };
    }
  }

  /**
   * The review every dispatched spec gets when {@code sail.yaml} configures no {@code
   * review_pipeline}: one agent stage whose reviewer is resolved from the project's installed-agent
   * roster (cross-agent when a second agent is installed, self-review otherwise), gated on no
   * critical findings. Review is on by default.
   */
  public static ReviewPipelineConfig mandatoryDefault() {
    return new ReviewPipelineConfig(
        3,
        2,
        List.of(
            new StageConfig(
                "review",
                StageType.AGENT,
                null,
                List.of("security", "correctness"),
                Gate.NO_CRITICAL)));
  }

  @SuppressWarnings("unchecked")
  public static ReviewPipelineConfig fromMap(Map<String, Object> map) {
    var maxIterations =
        map.containsKey("max_iterations") ? ((Number) map.get("max_iterations")).intValue() : 3;
    var maxFindingAge =
        map.containsKey("max_finding_age") ? ((Number) map.get("max_finding_age")).intValue() : 2;
    var stagesList = (List<Map<String, Object>>) map.getOrDefault("stages", List.of());
    var stages = stagesList.stream().map(StageConfig::fromMap).toList();
    return new ReviewPipelineConfig(maxIterations, maxFindingAge, stages);
  }

  public List<StageConfig> agentStages() {
    return stages.stream().filter(s -> s.type() == StageType.AGENT).toList();
  }

  public List<StageConfig> humanStages() {
    return stages.stream().filter(s -> s.type() == StageType.HUMAN).toList();
  }
}
