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
 * out by {@link LoopSteps}. A stage is named by its place in the project's pipeline, which is its
 * row's place in the review.
 */
sealed interface LoopStep {

  /** Nothing is owed, or the step is someone else's: a run's, a person's, another box's. */
  record Nothing() implements LoopStep {}

  /** Say the build failed with {@code exitCode}, and leave the spec for triage. */
  record SayBuildFailed(int exitCode) implements LoopStep {}

  /** The project has no pipeline: move the spec to {@code review} for a person. */
  record ParkForAPerson() implements LoopStep {}

  /** Write iteration {@code iteration} of the spec's review, and move the spec to review. */
  record StartReview(int iteration) implements LoopStep {}

  /** Write iteration {@code iteration} of the spec's review and hand it to a person. */
  record EscalateNew(int iteration, String reason) implements LoopStep {}

  /** Go on with a review no run serves: it is running from here on, with its spec in review. */
  record Resume(ReviewRow review) implements LoopStep {}

  /** Launch {@code agent} as the reviewer of an agent stage. */
  record LaunchReviewer(ReviewRow review, int stage, StageConfig stageConfig, String agent)
      implements LoopStep {}

  /** Open a person's stage and wait for them. */
  record AwaitAPerson(ReviewRow review, int stage) implements LoopStep {}

  /** Every stage passed: the review has. */
  record Pass(ReviewRow review) implements LoopStep {}

  /**
   * Judge the stage {@code run} reviewed: on what the run said, or — for one that did not end well
   * — as an infrastructure error saying {@code error}.
   */
  record ReadVerdict(ReviewRow review, int stage, RunStore.RunRow run, Optional<String> error)
      implements LoopStep {}

  /**
   * Fail the review by infrastructure error at a stage; {@code closed} when the stage's verdict
   * already closed it failed for {@code why}.
   */
  record ErrorReview(ReviewRow review, int stage, String why, boolean closed) implements LoopStep {}

  /** A stage failed its gate: the review has failed, and the room is told what it found. */
  record FailGate(ReviewRow review) implements LoopStep {}

  /** Hand the review's open findings to a fix agent. */
  record LaunchFix(ReviewRow review, List<Finding> findings) implements LoopStep {}

  /** Commit and push what fix agent {@code run} left uncommitted. */
  record CommitFixLeftovers(ReviewRow review, RunStore.RunRow run) implements LoopStep {}

  /** The fix iteration did not address the findings: say so and hand the review to a person. */
  record FailFix(ReviewRow review, String why) implements LoopStep {}

  /** Hand the review to a person, for {@code reason}. */
  record Escalate(ReviewRow review, String reason) implements LoopStep {}
}
