/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.store.RunStore;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What happened to a spec's review loop: the stop the router heard, or what the step just taken
 * came to. A trigger carries no decision; {@link LoopDecision#next} makes it.
 */
sealed interface LoopTrigger {

  /**
   * Why the run a stop tells of did not end well: the guardrail the watcher ended it for ({@code
   * killed: time limit (45m)}), else its non-zero exit ({@code failed: exit 1}). A stop that names
   * neither — a clean exit, or a reconciled stop of a run nothing is known to have ended — is no
   * failure, and the run's work is judged on what it left.
   */
  static Optional<String> failureOf(Map<String, Object> stop) {
    var reason = Objects.toString(stop.get(Event.WellKnownData.REASON), null);
    if (Strings.isNotBlank(reason)) {
      return Optional.of("killed: " + reason);
    }
    var exitCode = Event.WellKnownData.exitCode(stop);
    return exitCode != null && exitCode != 0
        ? Optional.of("failed: exit " + exitCode)
        : Optional.empty();
  }

  /**
   * A build, an ad-hoc run or a run of no known lane ended. {@code runId} is null when this box
   * holds no row of the run, {@code exitCode} when the stop says none.
   */
  record BuildEnded(String runId, Integer exitCode) implements LoopTrigger {}

  /** A reviewer ended; {@code failure} is how it ended badly ({@link #failureOf}), if it did. */
  record ReviewerEnded(RunStore.RunRow run, Optional<String> failure) implements LoopTrigger {}

  /** A fix agent ended; {@code failure} as for {@link ReviewerEnded}. */
  record FixEnded(RunStore.RunRow run, Optional<String> failure) implements LoopTrigger {}

  /** An operator stopped {@code run}: its cancel, or any stop of a run their stop claimed. */
  record OperatorStopped(RunStore.RunRow run) implements LoopTrigger {}

  /** A stop freed whatever its run held, which a review this box drives may be waiting for. */
  record Freed() implements LoopTrigger {}

  /**
   * A step left the review with something owed and nothing new to decide on: a gate just failed.
   * The loop goes on from what the rows owe, as it does for a late or replayed stop.
   */
  record GoOn() implements LoopTrigger {}

  /** A step left the spec's review {@code running} with its spec in {@code review}. */
  record ReviewRunning() implements LoopTrigger {}

  /** A step judged the stage in place {@code stage} of review {@code reviewId}. */
  record StageJudged(String reviewId, int stage, StageVerdicts.StageOutcome outcome)
      implements LoopTrigger {}

  /**
   * The launch of the reviewer of stage {@code stage} threw, building its prompt included, and no
   * run serves the review.
   */
  record ReviewerNotStarted(String reviewId, int stage, String why) implements LoopTrigger {}

  /** The launch of the review's fix agent threw, building its task included, and no run serves. */
  record FixNotStarted(String reviewId, String why) implements LoopTrigger {}

  /** What fix agent {@code run} left uncommitted, if anything, is committed and pushed. */
  record FixCommitted(RunStore.RunRow run) implements LoopTrigger {}

  /** What fix agent {@code run} left uncommitted could not be committed and pushed. */
  record FixNotCommitted(RunStore.RunRow run, String why) implements LoopTrigger {}
}
