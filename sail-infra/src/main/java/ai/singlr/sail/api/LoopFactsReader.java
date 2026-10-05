/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.ReviewPipelineConfig.StageType;
import ai.singlr.sail.config.RunStatus;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.util.HashMap;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/** The one reader of the stores for a decision of the review loop ({@link LoopFacts}). */
final class LoopFactsReader {

  private final SpecStore specs;
  private final ReviewStore reviews;
  private final RunStore runs;
  private final Function<String, LoopFacts.Pipeline> pipelines;
  private final Supplier<String> localHandle;

  LoopFactsReader(
      SpecStore specs,
      ReviewStore reviews,
      RunStore runs,
      Function<String, LoopFacts.Pipeline> pipelines,
      Supplier<String> localHandle) {
    this.specs = specs;
    this.reviews = reviews;
    this.runs = runs;
    this.pipelines = pipelines;
    this.localHandle = localHandle;
  }

  /** The facts of {@code specId} under its project's pipeline as it reads now. */
  LoopFacts read(String project, String specId) {
    return read(project, specId, pipelines.apply(project));
  }

  /** A reader for one event: a project's pipeline is resolved at its first read, once. */
  BiFunction<String, String, LoopFacts> forOneEvent() {
    var resolved = new HashMap<String, LoopFacts.Pipeline>();
    return (project, specId) -> read(project, specId, resolved.computeIfAbsent(project, pipelines));
  }

  private LoopFacts read(String project, String specId, LoopFacts.Pipeline pipeline) {
    var review = reviews.latestReviewForSpec(specId);
    var stages = review.map(latest -> reviews.stagesForReview(latest.id())).orElse(List.of());
    return new LoopFacts(
        project,
        specId,
        localHandle.get(),
        specs.findById(specId),
        review,
        stages,
        review.map(latest -> serving(latest.id())).orElse(List.of()),
        runs.latestLoopRun(specId),
        pipeline,
        review.map(latest -> reviews.openFindingsForReview(latest.id())).orElse(List.of()),
        stages.stream()
            .filter(stage -> "failed".equals(stage.status()))
            .findFirst()
            .map(this::openFindingsWithAges)
            .orElse(List.of()),
        review.map(this::erroredAttempts).orElse(0L),
        review.map(ReviewStore.ReviewRow::waitingOn).filter(this::ended).isPresent());
  }

  /**
   * What a project's review pipeline is: {@code configResolver} answers null for none, and throws
   * for a descriptor that cannot be read, which is never taken for a project with no pipeline.
   */
  static Function<String, LoopFacts.Pipeline> pipelines(
      Function<String, ReviewPipelineConfig> configResolver,
      Function<String, String> reviewerResolver) {
    return project -> {
      try {
        var config = configResolver.apply(project);
        if (config == null || config.stages().isEmpty()) {
          return new LoopFacts.Pipeline.None();
        }
        var unnamed =
            config.stages().stream()
                .anyMatch(stage -> stage.type() == StageType.AGENT && stage.agent() == null);
        return new LoopFacts.Pipeline.Staged(
            config, unnamed ? reviewerResolver.apply(project) : null);
      } catch (RuntimeException unreadable) {
        return new LoopFacts.Pipeline.Unreadable(unreadable.getMessage());
      }
    };
  }

  /** The runs this box executed that serve {@code reviewId}, newest first. */
  List<RunStore.RunRow> serving(String reviewId) {
    var node = localHandle.get();
    return runs.forReview(reviewId).stream().filter(run -> run.ownedBy(node)).toList();
  }

  /** Whether a run that serves {@code reviewId} is yet to finish, as the rows stand now. */
  boolean served(String reviewId) {
    return serving(reviewId).stream().anyMatch(run -> !RunStatus.isTerminal(run.status()));
  }

  /** Whether a run holds nothing any more: its row is terminal, or erased, and holds no claim. */
  boolean ended(String runId) {
    return runs.findById(runId).filter(run -> !RunStatus.isTerminal(run.status())).isEmpty();
  }

  private List<LoopFacts.Aged> openFindingsWithAges(ReviewStore.StageRow stage) {
    return reviews.findingsForStage(stage.id()).stream()
        .filter(finding -> finding.resolution() == Finding.Resolution.OPEN)
        .map(finding -> new LoopFacts.Aged(finding, reviews.findingAge(finding.id())))
        .toList();
  }

  /** Errored attempts of the review's iteration in the current dispatch attempt. */
  private long erroredAttempts(ReviewStore.ReviewRow review) {
    return reviews.reviewsForSpec(review.specId()).stream()
        .filter(r -> !r.superseded() && r.errored() && r.iteration() == review.iteration())
        .count();
  }
}
