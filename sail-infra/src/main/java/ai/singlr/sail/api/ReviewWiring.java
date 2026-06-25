/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.AgentRoster;
import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.SpecStore;
import java.util.function.Function;

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
   * projectLoader}, and a {@link ContainerReviewAgentRunner} that launches reviewers in the
   * container over {@code shell}.
   */
  public static ReviewPipelineController controller(
      SpecStore specStore,
      ReviewStore reviewStore,
      EventBus eventBus,
      Function<String, SailYaml> projectLoader,
      ShellExec shell) {
    return new ReviewPipelineController(
        specStore,
        reviewStore,
        configResolver(projectLoader),
        reviewerResolver(projectLoader),
        new ContainerReviewAgentRunner(shell),
        eventBus);
  }

  /** Resolves a project's review pipeline: its configured one, or the mandatory default. */
  static Function<String, ReviewPipelineConfig> configResolver(Function<String, SailYaml> loader) {
    return project -> {
      var config = loader.apply(project);
      var configured =
          config != null && config.agent() != null ? config.agent().reviewPipeline() : null;
      return configured != null && !configured.stages().isEmpty()
          ? configured
          : ReviewPipelineConfig.mandatoryDefault();
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
