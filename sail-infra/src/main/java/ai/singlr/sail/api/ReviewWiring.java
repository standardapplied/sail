/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.AgentRoster;
import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Builds the per-project resolvers the {@link ReviewPipelineController} needs from a loader that
 * maps a project to its {@link SailYaml}. Encodes two product decisions so the controller stays
 * mechanism-only: review is on by default — a project that configures no {@code review_pipeline}
 * still gets {@link ReviewPipelineConfig#mandatoryDefault()} — and a stage that names no agent is
 * reviewed by the project's roster reviewer ({@link AgentRoster#reviewer}, i.e. the other installed
 * agent when there is one, else the coder itself in a fresh session).
 */
public final class ReviewWiring {

  private ReviewWiring() {}

  /**
   * Assembles the production review pipeline controller: the two roster-aware resolvers over {@code
   * projectLoader}, launching its reviewers and fix agents through {@code lanes} — the same
   * launcher, watcher and stop every other lane runs on.
   */
  public static ReviewPipelineController controller(
      SpecStore specStore,
      ReviewStore reviewStore,
      RunStore runStore,
      EventBus eventBus,
      Function<String, SailYaml> projectLoader,
      ReviewLanes lanes,
      Runnable syncTrigger,
      Supplier<String> localHandle) {
    return new ReviewPipelineController(
        specStore,
        reviewStore,
        runStore,
        configResolver(projectLoader),
        reviewerResolver(projectLoader),
        lanes,
        eventBus,
        syncTrigger,
        localHandle);
  }

  /**
   * What the loop reads a project as: its catalog definition, null for a project with no row, and
   * {@link ProjectReader.Unreadable} for one whose row cannot be read, which is never taken for a
   * project with no pipeline.
   */
  public static Function<String, SailYaml> definitions(ProjectReader reader) {
    return project -> reader.read(project).orElse(null);
  }

  /**
   * Resolves a project's review pipeline: its configured one, or the mandatory default. A block
   * that names no stages runs the default's stages under the default's limits, and keeps only the
   * skill it names for the fix agent.
   */
  public static Function<String, ReviewPipelineConfig> configResolver(
      Function<String, SailYaml> loader) {
    return project -> {
      var config = loader.apply(project);
      var configured =
          config != null && config.agent() != null ? config.agent().reviewPipeline() : null;
      var fallback = ReviewPipelineConfig.mandatoryDefault();
      if (configured == null) {
        return fallback;
      }
      return configured.stages().isEmpty()
          ? new ReviewPipelineConfig(
              fallback.maxIterations(),
              fallback.maxFindingAge(),
              fallback.stages(),
              fallback.guardrails(),
              configured.fixSkill())
          : configured;
    };
  }

  /** Resolves a project's default reviewer agent from its installed-agent roster. */
  static Function<String, String> reviewerResolver(Function<String, SailYaml> loader) {
    return project -> {
      var config = loader.apply(project);
      var agent = config != null ? config.agent() : null;
      return agent == null ? null : AgentRoster.reviewer(agent.type(), agent.install());
    };
  }
}
