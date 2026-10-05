/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.AgentRoster;
import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.nio.file.Files;
import java.nio.file.Path;
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
   * A project's descriptor as read from {@code path}: null when the project has none. One that is
   * there and cannot be read is an error — it is never taken for a project that configured nothing,
   * which would put the mandatory default pipeline in place of the one the project's reviews are
   * running under.
   */
  public static SailYaml descriptor(String project, Path path) {
    if (!Files.exists(path)) {
      return null;
    }
    try {
      return SailYaml.fromMap(YamlUtil.parseFile(path));
    } catch (Exception e) {
      throw new IllegalStateException(
          "sail.yaml of project '" + project + "' could not be read: " + e.getMessage(), e);
    }
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
