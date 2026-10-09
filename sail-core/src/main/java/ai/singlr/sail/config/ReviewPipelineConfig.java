/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.PromptConversation;
import ai.singlr.sail.store.Finding;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Configurable multi-stage review pipeline. Parsed from the {@code agent.review_pipeline} block in
 * {@code sail.yaml}. Stages execute sequentially; each agent stage produces structured findings
 * evaluated against its gate before advancing.
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
    return "agent.review_pipeline.stages[" + stage + "].brief";
  }

  /** The keys of a stage sail no longer reads, each with where its text now goes. */
  private static final Map<String, String> DELETED_STAGE_KEYS =
      Map.of(
          "skill",
          "is no longer read: a stage judges under its brief. Put what the skill said in the"
              + " stage's brief (%s), or keep it as a project skill (sail project skills add) the"
              + " brief tells the stage to run.",
          "categories",
          "is no longer read: what a stage focuses on is its brief. Say it in %s.");

  /**
   * The one sentence refusing {@code key}, a key sail no longer reads, on the stage called {@code
   * stage}: which key, and where its text now goes.
   */
  public static String deletedStageKey(String stage, String key) {
    return "agent.review_pipeline.stages["
        + stage
        + "]."
        + key
        + " "
        + DELETED_STAGE_KEYS.get(key).formatted(briefKey(stage));
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
          "review_pipeline stage names must be unique — carry-forward is keyed by stage name;"
              + " rename the duplicate stage in sail.yaml");
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

    public static StageConfig fromMap(Map<String, Object> map) {
      var name = (String) map.get("name");
      if (Strings.isBlank(name)) {
        throw new IllegalArgumentException("review_pipeline stage requires a name");
      }
      for (var key : DELETED_STAGE_KEYS.keySet()) {
        if (map.containsKey(key)) {
          throw new IllegalArgumentException(deletedStageKey(name, key));
        }
      }
      var type = StageType.parse((String) map.getOrDefault("type", "agent"));
      var agent = (String) map.get("agent");
      var gate = Gate.parse((String) map.getOrDefault("gate", "no_critical"));
      var brief = map.get("brief");
      if (brief != null && !(brief instanceof String)) {
        throw new IllegalArgumentException(briefKey(name) + " must be text.");
      }
      return new StageConfig(name, type, agent, gate, (String) brief);
    }

    /** This stage as one entry of {@code review_pipeline.stages}, as {@link #fromMap} reads it. */
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
   * review_pipeline}: one agent stage named {@code review}, judged under sail's default brief by
   * the reviewer resolved from the project's installed-agent roster (cross-agent when a second
   * agent is installed, self-review otherwise), gated on no critical findings. Review is on by
   * default.
   */
  public static ReviewPipelineConfig mandatoryDefault() {
    return new ReviewPipelineConfig(
        DEFAULT_MAX_ITERATIONS,
        DEFAULT_MAX_FINDING_AGE,
        List.of(new StageConfig("review", StageType.AGENT, null, Gate.NO_CRITICAL, null)));
  }

  /**
   * The pipeline the loop runs for a project whose {@code review_pipeline} block is {@code
   * configured}: that block, or {@link #mandatoryDefault()} for none. A block that names no stages
   * runs the default's stages under the default's limits.
   */
  public static ReviewPipelineConfig resolved(ReviewPipelineConfig configured) {
    if (configured == null || configured.stages().isEmpty()) {
      return mandatoryDefault();
    }
    return configured;
  }

  /** Parses an {@code agent.review_pipeline} block of {@code sail.yaml}. */
  public static ReviewPipelineConfig fromMap(Map<String, Object> map) {
    return fromMap(map, "sail.yaml");
  }

  @SuppressWarnings("unchecked")
  static ReviewPipelineConfig fromMap(Map<String, Object> map, String descriptor) {
    SailYaml.refuseDeleted(map, "agent.review_pipeline.fix_skill", descriptor);
    var maxIterations =
        map.containsKey("max_iterations")
            ? ((Number) map.get("max_iterations")).intValue()
            : DEFAULT_MAX_ITERATIONS;
    var maxFindingAge =
        map.containsKey("max_finding_age")
            ? ((Number) map.get("max_finding_age")).intValue()
            : DEFAULT_MAX_FINDING_AGE;
    var stagesList = (List<Map<String, Object>>) map.getOrDefault("stages", List.of());
    var stages = stagesList.stream().map(StageConfig::fromMap).toList();
    var guardrails =
        Guardrails.fromBlock(map.get("guardrails"), Guardrails.REVIEW_BLOCK, descriptor)
            .orElseGet(Guardrails::reviewDefaults);
    return new ReviewPipelineConfig(maxIterations, maxFindingAge, stages, guardrails);
  }

  /**
   * This pipeline as its {@code review_pipeline} block, as {@link #fromMap} reads it. A limit that
   * is the default is left out, as a project that never set it left it out: written down, it would
   * pin that project to today's default the next time anything rewrote its {@code sail.yaml}.
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
