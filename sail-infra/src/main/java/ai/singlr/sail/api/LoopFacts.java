/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.ReviewPipelineConfig.StageConfig;
import ai.singlr.sail.config.RunStatus;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.MissedStops;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Everything a decision of the review loop reads about one spec, as the rows stood at one moment
 * ({@link LoopFactsReader}). What they say the loop owes the spec's review ({@link #owed}) is the
 * one reading the pipeline and the missed-stop reconciler share, so the step the pipeline takes
 * when a stop arrives is always the step the reconciler replayed that stop for.
 *
 * <p>Only the runs this box ({@code node}) executed count as serving a review: a run row another
 * box pushed can name any review, and would otherwise hold this box's loop waiting on a stop that
 * is never coming here. And a box moves a spec's loop unasked only when it executed the spec's
 * newest loop run ({@link #drivenHere}): a review it merely holds a copy of is its executing box's.
 *
 * @param review the latest review of the spec's current dispatch attempt, with its {@code stages}
 * @param serving the runs this box executed that serve the review, newest first
 * @param newestLoopRun the spec's newest build, reviewer or fix run, whichever box executed it
 * @param openFindings the open findings of a review that failed its gate; none for any other
 * @param failedStageFindings those of them its first failed stage holds, each with its age
 * @param erroredAttempts how many attempts of the review's iteration failed by infrastructure error
 * @param holderEnded whether the run the review recorded that it waits on holds nothing any more
 */
record LoopFacts(
    String project,
    String specId,
    String node,
    Optional<SpecStore.SpecRow> spec,
    Optional<ReviewStore.ReviewRow> review,
    List<ReviewStore.StageRow> stages,
    List<RunStore.RunRow> serving,
    Optional<RunStore.RunRow> newestLoopRun,
    Pipeline pipeline,
    List<Finding> openFindings,
    List<Aged> failedStageFindings,
    long erroredAttempts,
    boolean holderEnded) {

  /** What a project's review pipeline is, as far as it can be read. */
  sealed interface Pipeline {

    /**
     * The project's pipeline, with stages for a review to run, and the project's default reviewer
     * ({@code AgentRoster.reviewer}) for a stage that names none: null when it installs no agent.
     */
    record Staged(ReviewPipelineConfig config, String rosterReviewer) implements Pipeline {

      /** The agent that reviews {@code stage}, or empty when none resolves. */
      Optional<String> reviewer(StageConfig stage) {
        return Optional.ofNullable(stage.agent() != null ? stage.agent() : rosterReviewer);
      }
    }

    /** The project has no pipeline with stages. */
    record None() implements Pipeline {}

    /** The descriptor could not be read: nothing is known of the pipeline, not even its absence. */
    record Unreadable(String why) implements Pipeline {}
  }

  /** A finding and how many fix iterations it has survived ({@link ReviewStore#findingAge}). */
  record Aged(Finding finding, int age) {}

  /** The step a review's rows say comes next. */
  sealed interface Owed {

    /** No step: no review, a run still serves it, it waits on a person, or it ended. */
    record Nothing() implements Owed {}

    /** The review failed by infrastructure error: its iteration runs again, within its budget. */
    record Retry(ReviewStore.ReviewRow review) implements Owed {}

    /**
     * The review is running, no reviewer was ever launched for the stage it is in, and no wait is
     * recorded: the launch was cut short before the gate answered.
     */
    record Advance(ReviewStore.ReviewRow review) implements Owed {}

    /** The review failed its gate, and neither a fix agent nor a wait was recorded for it. */
    record Fix(ReviewStore.ReviewRow review) implements Owed {}

    /**
     * The gate refused the review's last launch, and the review recorded the run that held the
     * claim. It is owed the {@link Advance} or {@link Fix} it was refused, once that run has ended.
     */
    record Waiting(ReviewStore.ReviewRow review, Owed step) implements Owed {}

    /**
     * The run the review waited on — its running stage's reviewer, or its fix agent — has ended,
     * and nothing has come of it yet: only that run's own stop says what its work is worth.
     */
    record Stop(ReviewStore.ReviewRow review, RunStore.RunRow run) implements Owed {}
  }

  /** Whether this box executed the newest run of the spec's loop, and so drives it. */
  boolean drivenHere() {
    return newestLoopRun.filter(run -> run.ownedBy(node)).isPresent();
  }

  /** Whether a run that serves the review — a reviewer or its fix agent — is yet to finish. */
  boolean served() {
    return serving.stream().anyMatch(run -> !RunStatus.isTerminal(run.status()));
  }

  /**
   * Whether the spec is the loop's to move: {@code in_progress}, or {@code review} — a spec can be
   * moved there out of band while its agent still runs here. Every other status, {@code cancelled}
   * above all, is someone's decision the loop never acts over.
   */
  boolean loops() {
    return spec.map(SpecStore.SpecRow::status)
        .filter(status -> status == SpecStatus.IN_PROGRESS || status == SpecStatus.REVIEW)
        .isPresent();
  }

  /** The pipeline, for a decision that has found the review fit to run under it. */
  Pipeline.Staged staged() {
    return (Pipeline.Staged) pipeline;
  }

  /**
   * Why the review cannot run under the project's pipeline, or empty when it can: the pipeline
   * cannot be read, has no stages left, or a stage the review holds is not the pipeline's stage in
   * that place any more, by name and by kind. Such a review is a person's.
   */
  Optional<String> unfit() {
    return switch (pipeline) {
      case Pipeline.Staged staged -> changedUnder(staged.config());
      case Pipeline.None none -> Optional.of(ReviewNarration.noStages());
      case Pipeline.Unreadable unreadable ->
          Optional.of(ReviewNarration.pipelineUnreadable(unreadable.why()));
    };
  }

  private Optional<String> changedUnder(ReviewPipelineConfig config) {
    var configured = config.stages();
    for (var place = 0; place < stages.size(); place++) {
      var row = stages.get(place);
      if (place >= configured.size()
          || !configured.get(place).name().equals(row.name())
          || !stageType(configured.get(place)).equals(row.stageType())) {
        return Optional.of(ReviewNarration.pipelineChanged(row.name()));
      }
    }
    return Optional.empty();
  }

  static String stageType(StageConfig stage) {
    return stage.type().name().toLowerCase(Locale.ROOT);
  }

  /**
   * The place of the stage {@code run} reviewed, or empty when no stage waits on it: the agent
   * stage that is {@code running} and was started while the run was live — a stage starts once its
   * reviewer's claim has landed, and before that reviewer's unit does. A stage started before the
   * run was recorded, or after it ended, is another reviewer's to judge.
   */
  Optional<Integer> stageReviewedBy(RunStore.RunRow run) {
    var recorded = MissedStops.parseOr(run.startedAt(), Instant.MAX);
    var ended = MissedStops.parseOr(run.completedAt(), Instant.MAX);
    return stages.stream()
        .filter(stage -> "running".equals(stage.status()) && !"human".equals(stage.stageType()))
        .filter(
            stage -> {
              var started = MissedStops.parseOr(stage.startedAt(), Instant.MIN);
              return !started.isBefore(recorded) && !started.isAfter(ended);
            })
        .findFirst()
        .map(stages::indexOf);
  }

  /** Whether a newer run of the spec's loop has come after run {@code runId}. */
  boolean replaced(String runId) {
    return newestLoopRun.filter(newest -> !newest.id().equals(runId)).isPresent();
  }

  /**
   * The review {@code run} serves, when the loop is waiting on this very run: the run is this box's
   * and the newest of its spec's loop, its spec is still the loop's to move, and its review is the
   * spec's latest and in one of {@code statuses}. Any other stop is past, or not the loop's.
   */
  Optional<ReviewStore.ReviewRow> awaited(RunStore.RunRow run, String... statuses) {
    if (!run.ownedBy(node) || run.reviewId() == null || !loops() || replaced(run.id())) {
      return Optional.empty();
    }
    return latest(run.reviewId()).filter(review -> List.of(statuses).contains(review.status()));
  }

  /** Review {@code reviewId} while it is the spec's latest: a follow-up is about no other. */
  Optional<ReviewStore.ReviewRow> latest(String reviewId) {
    return review.filter(latest -> latest.id().equals(reviewId));
  }

  /** What the latest review of the spec's current dispatch attempt is owed. */
  Owed owed() {
    var latest = review.orElse(null);
    if (latest == null || served()) {
      return new Owed.Nothing();
    }
    var newest = serving.stream().findFirst();
    return switch (latest.status()) {
      case "pending", "running" -> whileRunning(latest, newest);
      case "failed" -> latest.errored() ? new Owed.Retry(latest) : afterGateFailure(latest, newest);
      default -> new Owed.Nothing();
    };
  }

  private Owed whileRunning(ReviewStore.ReviewRow review, Optional<RunStore.RunRow> newest) {
    if (waitsOnPerson()) {
      return new Owed.Nothing();
    }
    return newest
        .filter(run -> Lane.REVIEW.matches(run.role()))
        .filter(reviewer -> stageReviewedBy(reviewer).isPresent())
        .<Owed>map(reviewer -> new Owed.Stop(review, reviewer))
        .orElseGet(() -> unserved(review, new Owed.Advance(review)));
  }

  private static Owed afterGateFailure(
      ReviewStore.ReviewRow review, Optional<RunStore.RunRow> newest) {
    return newest
        .filter(run -> Lane.FIX.matches(run.role()))
        .<Owed>map(fix -> new Owed.Stop(review, fix))
        .orElseGet(() -> unserved(review, new Owed.Fix(review)));
  }

  /** What a review no run serves is owed: {@code step}, or to wait for the run that holds it. */
  private static Owed unserved(ReviewStore.ReviewRow review, Owed step) {
    return review.waitingOn() == null ? step : new Owed.Waiting(review, step);
  }

  private boolean waitsOnPerson() {
    return stages.stream()
        .anyMatch(stage -> "human".equals(stage.stageType()) && "running".equals(stage.status()));
  }
}
