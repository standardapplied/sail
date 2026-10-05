/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.ReviewPipelineConfig.StageConfig;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore.ReviewRow;
import ai.singlr.sail.store.RunStore;
import java.util.List;
import java.util.Optional;

/**
 * One step of a spec's review loop: what to do, decided by {@link LoopDecision#next} and carried
 * out by {@link LoopSteps}. A stage is named by its place in the pipeline, its row's in the review.
 */
sealed interface LoopStep {

  record Nothing() implements LoopStep {}

  record SayBuildFailed(int exitCode) implements LoopStep {}

  record ParkForAPerson() implements LoopStep {}

  record StartReview(int iteration) implements LoopStep {}

  /** Write iteration {@code iteration} of the spec's review and hand it to a person. */
  record EscalateNew(int iteration, String reason) implements LoopStep {}

  /** Go on with a review no run serves: it is running from here on, with its spec in review. */
  record Resume(ReviewRow review) implements LoopStep {}

  record LaunchReviewer(ReviewRow review, int stage, StageConfig stageConfig, String agent)
      implements LoopStep {}

  record AwaitAPerson(ReviewRow review, int stage) implements LoopStep {}

  record Pass(ReviewRow review) implements LoopStep {}

  /** Judge the stage {@code run} reviewed: on what it said, or as the {@code error} it ended in. */
  record ReadVerdict(ReviewRow review, int stage, RunStore.RunRow run, Optional<String> error)
      implements LoopStep {}

  /** Fail the review by error at a stage, {@code closed} when its verdict already closed it. */
  record ErrorReview(ReviewRow review, int stage, String why, boolean closed) implements LoopStep {}

  record FailGate(ReviewRow review) implements LoopStep {}

  record LaunchFix(ReviewRow review, List<Finding> findings) implements LoopStep {}

  record CommitFixLeftovers(ReviewRow review, RunStore.RunRow run) implements LoopStep {}

  record FailFix(ReviewRow review, String why) implements LoopStep {}

  record Escalate(ReviewRow review, String reason) implements LoopStep {}
}
