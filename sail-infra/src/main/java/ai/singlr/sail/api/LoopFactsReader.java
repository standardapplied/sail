/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.RunStatus;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** The one reader of the stores for a decision of the review loop ({@link LoopFacts}). */
final class LoopFactsReader {

  private final SpecStore specs;
  private final ReviewStore reviews;
  private final RunStore runs;
  private final Function<String, ReviewPipelineConfig> configResolver;
  private final Supplier<String> localHandle;

  /**
   * @param configResolver a project's review pipeline, null for none; it throws for a descriptor
   *     that is there and cannot be read
   * @param localHandle this box's FDE handle: only the runs this box executed serve a review here
   */
  LoopFactsReader(
      SpecStore specs,
      ReviewStore reviews,
      RunStore runs,
      Function<String, ReviewPipelineConfig> configResolver,
      Supplier<String> localHandle) {
    this.specs = specs;
    this.reviews = reviews;
    this.runs = runs;
    this.configResolver = configResolver;
    this.localHandle = localHandle;
  }

  /** The facts of {@code specId} under its project's pipeline as it reads now. */
  LoopFacts read(String project, String specId) {
    return read(project, specId, pipeline(project));
  }

  /** The facts of {@code specId} under {@code pipeline}, resolved once for a whole event. */
  LoopFacts read(String project, String specId, LoopFacts.Pipeline pipeline) {
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

  LoopFacts.Pipeline pipeline(String project) {
    try {
      return Optional.ofNullable(configResolver.apply(project))
          .filter(config -> !config.stages().isEmpty())
          .<LoopFacts.Pipeline>map(LoopFacts.Pipeline.Staged::new)
          .orElseGet(LoopFacts.Pipeline.None::new);
    } catch (RuntimeException unreadable) {
      return new LoopFacts.Pipeline.Unreadable(unreadable.getMessage());
    }
  }

  /** The runs this box executed that serve {@code reviewId}, newest first. */
  List<RunStore.RunRow> serving(String reviewId) {
    var node = localHandle.get();
    return runs.forReview(reviewId).stream().filter(run -> run.ownedBy(node)).toList();
  }

  /**
   * Whether a run that serves {@code reviewId} — a reviewer or its fix agent — is yet to finish.
   */
  boolean served(String reviewId) {
    return serving(reviewId).stream().anyMatch(run -> !RunStatus.isTerminal(run.status()));
  }

  /**
   * Whether the run a waiting review waits on holds nothing any more: its row is terminal, or this
   * box holds no row of it — erased since — and a run with no row holds no claim.
   */
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
