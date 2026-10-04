/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.RunStatus;
import ai.singlr.sail.store.MissedStops;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What the rows of a spec's review say the loop owes it — the one reading of them the review
 * pipeline and the missed-stop reconciler share, so the step the pipeline takes when a stop arrives
 * is always the step the reconciler replayed that stop for.
 *
 * <p>Only the runs this box executed count as serving a review: a run row another box pushed can
 * name any review, and one that did would otherwise hold this box's loop waiting on a stop that is
 * never coming here. And a box moves a spec's loop unasked only when it executed the spec's newest
 * loop run ({@link #drivenHere}): a review it merely holds a copy of is its executing box's.
 */
final class ReviewLoopState {

  /** The step a review's rows say comes next. */
  sealed interface Owed {

    /**
     * No step: the spec has no review, a run still serves it, it waits on a person, or it ended —
     * passed, or escalated to a human.
     */
    record Nothing() implements Owed {}

    /** The review failed by infrastructure error: its iteration runs again, within its budget. */
    record Retry(ReviewStore.ReviewRow review) implements Owed {}

    /**
     * The review is running and no reviewer was ever launched for the stage it is in: the launch
     * was cut short, or the gate refused its claim. It goes on from its stage rows.
     */
    record Advance(ReviewStore.ReviewRow review) implements Owed {}

    /**
     * The review failed its gate and no fix agent ever served it: its findings go to one, or the
     * spec escalates.
     */
    record Fix(ReviewStore.ReviewRow review) implements Owed {}

    /**
     * The run the review waited on — the reviewer of its running stage, or the fix agent that
     * answered its gate failure — has ended, and nothing has come of it yet: only that run's own
     * stop says how it ended and what its work is worth. The stop is in flight, or was lost.
     */
    record Stop(ReviewStore.ReviewRow review, RunStore.RunRow run) implements Owed {}
  }

  private final ReviewStore reviews;
  private final RunStore runs;
  private final Supplier<String> localHandle;

  ReviewLoopState(ReviewStore reviews, RunStore runs, Supplier<String> localHandle) {
    this.reviews = reviews;
    this.runs = runs;
    this.localHandle = localHandle;
  }

  /** Whether this box executed the newest run of {@code specId}'s loop, and so drives it. */
  boolean drivenHere(String specId) {
    var node = localHandle.get();
    return runs.latestLoopRun(specId).filter(run -> run.ownedBy(node)).isPresent();
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
   * The stage of {@code reviewId} that {@code run} reviewed, or empty when no stage waits on it:
   * the agent stage that is {@code running} and was started no later than the run was recorded. A
   * stage started after the run is another reviewer's to judge — one whose launch never happened,
   * or still waits for its claim — and reading this run's log for it would pass a stage nobody
   * reviewed.
   */
  Optional<ReviewStore.StageRow> stageReviewedBy(String reviewId, RunStore.RunRow run) {
    var recorded = MissedStops.parseOr(run.startedAt(), Instant.MIN);
    return reviews.stagesForReview(reviewId).stream()
        .filter(stage -> "running".equals(stage.status()) && !"human".equals(stage.stageType()))
        .filter(stage -> !MissedStops.parseOr(stage.startedAt(), Instant.MAX).isAfter(recorded))
        .findFirst();
  }

  /** What the latest review of {@code specId}'s current dispatch attempt is owed. */
  Owed owed(String specId) {
    var latest = reviews.latestReviewForSpec(specId).orElse(null);
    if (latest == null || served(latest.id())) {
      return new Owed.Nothing();
    }
    var newest = serving(latest.id()).stream().findFirst();
    return switch (latest.status()) {
      case "running" -> whileRunning(latest, newest);
      case "failed" -> latest.errored() ? new Owed.Retry(latest) : afterGateFailure(latest, newest);
      default -> new Owed.Nothing();
    };
  }

  private Owed whileRunning(ReviewStore.ReviewRow review, Optional<RunStore.RunRow> newest) {
    if (waitsOnPerson(review.id())) {
      return new Owed.Nothing();
    }
    return newest
        .filter(run -> Lane.REVIEW.matches(run.role()))
        .filter(reviewer -> stageReviewedBy(review.id(), reviewer).isPresent())
        .<Owed>map(reviewer -> new Owed.Stop(review, reviewer))
        .orElseGet(() -> new Owed.Advance(review));
  }

  private static Owed afterGateFailure(
      ReviewStore.ReviewRow review, Optional<RunStore.RunRow> newest) {
    return newest
        .filter(run -> Lane.FIX.matches(run.role()))
        .<Owed>map(fix -> new Owed.Stop(review, fix))
        .orElseGet(() -> new Owed.Fix(review));
  }

  private boolean waitsOnPerson(String reviewId) {
    return reviews.stagesForReview(reviewId).stream()
        .anyMatch(stage -> "human".equals(stage.stageType()) && "running".equals(stage.status()));
  }
}
