/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Lane;
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
   * Why the run a stop tells of did not end well, or empty when it did: the guardrail the watcher
   * ended it for ({@code killed: time limit (45m)}, {@code killed: stall (20m)}), else its non-zero
   * exit ({@code failed: exit 1}). A stop that names neither — a clean exit, or a reconciled stop
   * of a run nothing is known to have ended — is no failure, and the run's work is judged on what
   * it left.
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
   * A build or an ad-hoc run ended, or a run of no known lane: {@code runId} when this box holds
   * its row, else null, and the exit code its stop carried, or null.
   */
  record BuildEnded(String runId, Integer exitCode) implements LoopTrigger {}

  /** A reviewer ended; {@code failure} is how it ended badly ({@link #failureOf}), if it did. */
  record ReviewerEnded(RunStore.RunRow run, Optional<String> failure) implements LoopTrigger {}

  /** A fix agent ended; {@code failure} as for {@link ReviewerEnded}. */
  record FixEnded(RunStore.RunRow run, Optional<String> failure) implements LoopTrigger {}

  /** An operator stopped {@code run}: its cancel, or any stop of a run their stop claimed. */
  record OperatorStopped(RunStore.RunRow run) implements LoopTrigger {}

  /**
   * Nothing new: a duplicate or replayed stop, one nothing waited on, a wait whose holder ended.
   */
  record GoOn() implements LoopTrigger {}

  /** A step left the spec's review {@code running} with its spec in {@code review}. */
  record ReviewRunning() implements LoopTrigger {}

  /** A step judged the stage in place {@code stage} of the pipeline. */
  record StageJudged(int stage, LoopSteps.StageOutcome outcome) implements LoopTrigger {}

  /**
   * A launch threw — building its prompt or its task included — and no live run serves the review.
   * {@code stage} is the place of the stage a reviewer was for, unread for a fix agent.
   */
  record LaunchFailed(Lane lane, int stage, String why) implements LoopTrigger {}

  /** What fix agent {@code run} left uncommitted, if anything, is committed and pushed. */
  record FixCommitted(RunStore.RunRow run) implements LoopTrigger {}

  /** What a fix agent left uncommitted could not be committed and pushed, for {@code why}. */
  record FixNotCommitted(String why) implements LoopTrigger {}
}
