/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.PromptConversation;
import ai.singlr.sail.store.Finding;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Configurable multi-stage review pipeline: a project definition's {@code pipeline} section. Stages
 * execute sequentially; each agent stage produces structured findings evaluated against its gate
 * before advancing.
 *
 * @param maxFindingAge how many fix iterations a gate-blocking finding may survive before the loop
 *     escalates it as stuck — a convergence measure, unlike {@code maxIterations}' blind budget: a
 *     loop resolving old findings while new ones surface keeps running; a loop replaying the same
 *     finding stops here
 * @param guardrails the limits every reviewer of this pipeline runs under, {@link
 *     Guardrails#reviewDefaults()} when the block names none
 */
public record ReviewPipelineConfig(
    int maxIterations, int maxFindingAge, List<StageConfig> stages, Guardrails guardrails) {

  /** The key that holds the brief of the stage called {@code stage}. */
  static String briefKey(String stage) {
    return "pipeline.stages[" + stage + "].brief";
  }

  /** How many review iterations a dispatch attempt gets when the project sets none. */
  static final int DEFAULT_MAX_ITERATIONS = 3;

  /** How many fix iterations a blocking finding may survive when the project sets none. */
  static final int DEFAULT_MAX_FINDING_AGE = 2;

  /** A pipeline under the review lane's default limits. */
  public ReviewPipelineConfig(int maxIterations, int maxFindingAge, List<StageConfig> stages) {
    this(maxIterations, maxFindingAge, stages, Guardrails.reviewDefaults());
  }

  public ReviewPipelineConfig {
    Objects.requireNonNull(guardrails, "guardrails");
    if (stages.stream().map(StageConfig::name).distinct().count() != stages.size()) {
      throw new IllegalArgumentException(
          "pipeline.stages names must be unique, since a finding carries forward by its stage's"
              + " name; rename the duplicate stage.");
    }
  }

  /**
   * One stage of the pipeline.
   *
   * @param brief what an agent stage judges, as the project wrote it, or null for sail's default
   *     brief; always null on a human stage, which follows no brief
   */
  public record StageConfig(String name, StageType type, String agent, Gate gate, String brief) {

    public StageConfig {
      if (brief != null && type != StageType.AGENT) {
        throw new IllegalArgumentException(
            briefKey(name) + " is set on a human stage; a person follows no brief, so remove it.");
      }
      if (brief != null) {
        if (Strings.isBlank(brief)) {
          throw new IllegalArgumentException(briefKey(name) + " is blank; write it or remove it.");
        }
        var codePoints = brief.codePointCount(0, brief.length());
        if (codePoints > PromptConversation.MAX_CODE_POINTS) {
          throw new IllegalArgumentException(
              "%s is %d code points; the limit is %d."
                  .formatted(briefKey(name), codePoints, PromptConversation.MAX_CODE_POINTS));
        }
      }
    }

    static StageConfig fromMap(Map<String, Object> map) {
      Fields.only(map, "pipeline.stages[]", List.of("name", "type", "agent", "gate", "brief"));
      var name = Fields.text(map, "name", "pipeline.stages[].name");
      if (Strings.isBlank(name)) {
        throw new IllegalArgumentException("pipeline.stages[].name is required.");
      }
      var at = "pipeline.stages[" + name + "]";
      return new StageConfig(
          name,
          choice(StageType.values(), map, "type", at, StageType.AGENT),
          Fields.text(map, "agent", at + ".agent"),
          choice(Gate.values(), map, "gate", at, Gate.NO_CRITICAL),
          Fields.text(map, "brief", briefKey(name)));
    }

    /** The constant {@code key} names, {@code fallback} when unset, or a refusal naming them. */
    private static <E extends Enum<E>> E choice(
        E[] values, Map<String, Object> map, String key, String at, E fallback) {
      var named = Fields.text(map, key, at + "." + key);
      if (named == null) {
        return fallback;
      }
      for (var value : values) {
        if (value.name().equalsIgnoreCase(named.strip())) {
          return value;
        }
      }
      throw new IllegalArgumentException(
          at
              + "."
              + key
              + " is '"
              + named
              + "'; it is one of "
              + String.join(
                  ", ", Arrays.stream(values).map(v -> v.name().toLowerCase(Locale.ROOT)).toList())
              + ".");
    }

    /** This stage as one entry of {@code pipeline.stages}, as {@link #fromMap} reads it. */
    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("type", type.name().toLowerCase(Locale.ROOT));
      if (agent != null) {
        map.put("agent", agent);
      }
      map.put("gate", gate.name().toLowerCase(Locale.ROOT));
      if (brief != null) {
        map.put("brief", brief);
      }
      return map;
    }
  }

  public enum StageType {
    AGENT,
    HUMAN;
  }

  public enum Gate {
    NO_CRITICAL,
    NO_CRITICAL_OR_HIGH,
    ALL_CLEAR;

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
   * The review every dispatched spec gets when its definition sets no {@code pipeline}: one agent
   * stage named {@code review}, judged under sail's default brief by the reviewer resolved from the
   * project's installed-agent roster (cross-agent when a second agent is installed, self-review
   * otherwise), gated on no critical findings. Review is on by default.
   */
  public static ReviewPipelineConfig mandatoryDefault() {
    return new ReviewPipelineConfig(
        DEFAULT_MAX_ITERATIONS,
        DEFAULT_MAX_FINDING_AGE,
        List.of(new StageConfig("review", StageType.AGENT, null, Gate.NO_CRITICAL, null)));
  }

  /**
   * The pipeline the loop runs for a project whose {@code pipeline} section is {@code configured}:
   * that block, or {@link #mandatoryDefault()} for none. A block that names no stages runs the
   * default's stages under the default's limits.
   */
  public static ReviewPipelineConfig resolved(ReviewPipelineConfig configured) {
    if (configured == null || configured.stages().isEmpty()) {
      return mandatoryDefault();
    }
    return configured;
  }

  /** Parses a definition's {@code pipeline} section. */
  static ReviewPipelineConfig fromMap(Map<String, Object> map) {
    Fields.only(
        map, "pipeline", List.of("max_iterations", "max_finding_age", "stages", "guardrails"));
    var maxIterations = positive(map, "max_iterations", DEFAULT_MAX_ITERATIONS);
    var maxFindingAge = positive(map, "max_finding_age", DEFAULT_MAX_FINDING_AGE);
    var stagesRaw = Fields.list(map, "stages", "pipeline.stages");
    var stages = new ArrayList<StageConfig>();
    if (stagesRaw != null) {
      for (var i = 0; i < stagesRaw.size(); i++) {
        stages.add(StageConfig.fromMap(Fields.element(stagesRaw, i, "pipeline.stages")));
      }
    }
    var guardrails =
        Guardrails.fromBlock(map.get("guardrails"), Guardrails.REVIEW_BLOCK)
            .orElseGet(Guardrails::reviewDefaults);
    return new ReviewPipelineConfig(maxIterations, maxFindingAge, List.copyOf(stages), guardrails);
  }

  private static int positive(Map<String, Object> map, String key, int fallback) {
    var value = Fields.integer(map, key, "pipeline." + key);
    if (value == null) {
      return fallback;
    }
    if (value < 1) {
      throw new IllegalArgumentException("pipeline." + key + " must be 1 or more.");
    }
    return value;
  }

  /**
   * This pipeline as its {@code pipeline} section, as {@link #fromMap} reads it. A limit that is
   * the default is left out, as a project that never set it left it out: written down, it would pin
   * that project to today's default the next time anything rewrote its definition.
   */
  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    if (maxIterations != DEFAULT_MAX_ITERATIONS) {
      map.put("max_iterations", maxIterations);
    }
    if (maxFindingAge != DEFAULT_MAX_FINDING_AGE) {
      map.put("max_finding_age", maxFindingAge);
    }
    if (!guardrails.equals(Guardrails.reviewDefaults())) {
      map.put("guardrails", guardrails.toMap());
    }
    map.put("stages", stages.stream().map(StageConfig::toMap).toList());
    return map;
  }

  public List<StageConfig> agentStages() {
    return stages.stream().filter(s -> s.type() == StageType.AGENT).toList();
  }

  public List<StageConfig> humanStages() {
    return stages.stream().filter(s -> s.type() == StageType.HUMAN).toList();
  }
}
