/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The rows a decision of the review loop reads, built in memory: a {@link LoopFacts} with no store
 * behind it. Times are three fixed instants, in order: a run is recorded at {@link #RECORDED}, a
 * stage starts at {@link #STARTED}, and a run ends at {@link #ENDED}.
 */
final class LoopRows {

  static final String PROJECT = ReviewLoop.PROJECT;
  static final String NODE = ReviewLoop.HANDLE;
  static final String SPEC = "auth";
  static final String REVIEW = "review-1";
  static final Instant RECORDED = Instant.parse("2026-01-01T10:00:00Z");
  static final Instant STARTED = RECORDED.plusSeconds(1);
  static final Instant ENDED = RECORDED.plusSeconds(60);

  /** An agent stage reviewed by {@code codex}, then a person's. */
  static final ReviewPipelineConfig AN_AGENT_THEN_A_PERSON =
      TestDefinitions.pipeline(
          Map.of(
              "max_iterations",
              3,
              "stages",
              List.of(
                  Map.<String, Object>of(
                      "name", "codex", "type", "agent", "agent", "codex", "gate", "no_critical"),
                  Map.<String, Object>of("name", "approve", "type", "human"))));

  private LoopRows() {}

  static LoopFacts.Pipeline staged(ReviewPipelineConfig config) {
    return new LoopFacts.Pipeline.Staged(config, null);
  }

  static SpecStore.SpecRow spec(SpecStatus status) {
    return new SpecStore.SpecRow(
        SPEC,
        PROJECT,
        "Test spec",
        status,
        NODE,
        "claude-code",
        null,
        null,
        "feat/test",
        0,
        null,
        null,
        null,
        null,
        List.of(),
        List.of("api"));
  }

  static ReviewStore.ReviewRow review(String status) {
    return review(1, status, null, null);
  }

  static ReviewStore.ReviewRow review(int iteration, String status, String error, String holder) {
    return new ReviewStore.ReviewRow(
        REVIEW, SPEC, iteration, status, RECORDED, null, null, null, error, holder);
  }

  /** A stage row of {@link #REVIEW}; one that is {@code running} started at {@link #STARTED}. */
  static ReviewStore.StageRow stage(String name, String type, String status) {
    return new ReviewStore.StageRow(
        "stage-" + name,
        REVIEW,
        name,
        type,
        status,
        null,
        "running".equals(status) ? STARTED : null,
        null,
        null);
  }

  /** The rows of {@link #AN_AGENT_THEN_A_PERSON}'s two stages, in these statuses. */
  static List<ReviewStore.StageRow> stages(String agent, String person) {
    return List.of(stage("codex", "agent", agent), stage("approve", "human", person));
  }

  /** A run this box executed, recorded at {@link #RECORDED} and still running. */
  static RunStore.RunRow run(String id, String role, String reviewId) {
    return run(id, NODE, role, reviewId, "running", null);
  }

  /** A run this box executed that ended, cleanly, at {@link #ENDED}. */
  static RunStore.RunRow ended(String id, String role, String reviewId) {
    return run(id, NODE, role, reviewId, "stopped", ENDED);
  }

  /** A run this box executed that ended at {@link #ENDED} with {@code exitCode}. */
  static RunStore.RunRow exited(String id, String role, String reviewId, int exitCode) {
    return run(id, NODE, role, reviewId, "stopped", ENDED, exitCode);
  }

  static RunStore.RunRow run(
      String id, String node, String role, String reviewId, String status, Instant completedAt) {
    return run(id, node, role, reviewId, status, completedAt, completedAt == null ? null : 0);
  }

  private static RunStore.RunRow run(
      String id,
      String node,
      String role,
      String reviewId,
      String status,
      Instant completedAt,
      Integer exitCode) {
    return new RunStore.RunRow(
        id,
        PROJECT,
        SPEC,
        node,
        role,
        "claude-code",
        "feat/test",
        "work",
        null,
        null,
        status,
        exitCode,
        null,
        null,
        RECORDED,
        completedAt,
        List.of(),
        null,
        null,
        node,
        null,
        null,
        null,
        null,
        null,
        reviewId,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  static Finding finding(String id, Finding.Severity severity) {
    return new Finding(
        id,
        severity,
        Finding.Category.LOGIC,
        "src/Auth.java",
        1,
        1,
        "finding " + id,
        "",
        "",
        null,
        1.0,
        Finding.Resolution.OPEN,
        null,
        null,
        null);
  }

  /** The facts of {@link #SPEC}, in {@code review} under {@link #AN_AGENT_THEN_A_PERSON}. */
  static Facts facts() {
    return new Facts();
  }

  /** A builder of {@link LoopFacts}: each call replaces one thing the rows say. */
  static final class Facts {

    private SpecStore.SpecRow spec = LoopRows.spec(SpecStatus.REVIEW);
    private ReviewStore.ReviewRow review;
    private List<ReviewStore.StageRow> stages = List.of();
    private List<RunStore.RunRow> serving = List.of();
    private RunStore.RunRow newest;
    private LoopFacts.Pipeline pipeline = staged(AN_AGENT_THEN_A_PERSON);
    private List<Finding> open = List.of();
    private List<LoopFacts.Aged> failedStage = List.of();
    private long erroredAttempts;
    private boolean holderEnded;
    private MessageStore.Asked asked;

    Facts spec(SpecStatus status) {
      this.spec = status == null ? null : LoopRows.spec(status);
      return this;
    }

    Facts reassigned(String assignee) {
      this.spec =
          new SpecStore.SpecRow(
              spec.id(),
              spec.project(),
              spec.title(),
              spec.status(),
              assignee,
              spec.agent(),
              spec.model(),
              spec.reasoningEffort(),
              spec.branch(),
              spec.priority(),
              spec.createdBy(),
              spec.createdAt(),
              spec.updatedAt(),
              spec.updatedBy(),
              spec.dependsOn(),
              spec.repos(),
              spec.roomId());
      return this;
    }

    Facts review(ReviewStore.ReviewRow review, List<ReviewStore.StageRow> stages) {
      this.review = review;
      this.stages = stages;
      return this;
    }

    /** The runs that serve the review, newest first; the first is the spec's newest loop run. */
    Facts serving(RunStore.RunRow... runs) {
      this.serving = List.of(runs);
      this.newest = runs.length == 0 ? newest : runs[0];
      return this;
    }

    Facts newestLoopRun(RunStore.RunRow run) {
      this.newest = run;
      return this;
    }

    Facts pipeline(LoopFacts.Pipeline pipeline) {
      this.pipeline = pipeline;
      return this;
    }

    Facts open(Finding... findings) {
      this.open = List.of(findings);
      return this;
    }

    Facts failedStage(LoopFacts.Aged... findings) {
      this.failedStage = List.of(findings);
      return this;
    }

    Facts erroredAttempts(long attempts) {
      this.erroredAttempts = attempts;
      return this;
    }

    Facts holderEnded(boolean ended) {
      this.holderEnded = ended;
      return this;
    }

    /**
     * The newest loop run's newest question: answered or not, and an answer the run heard, as one
     * that came while it ran is.
     */
    Facts asked(String question, boolean answered) {
      this.asked =
          new MessageStore.Asked("question-1", question, answered ? "answer-1" : null, answered);
      return this;
    }

    /** The newest loop run's newest question, answered after it stopped hearing the room. */
    Facts answeredUnheard(String question) {
      this.asked = new MessageStore.Asked("question-1", question, "answer-1", false);
      return this;
    }

    LoopFacts build() {
      var rows =
          new LoopFacts.Rows(
              NODE,
              Optional.ofNullable(spec),
              Optional.ofNullable(review),
              stages,
              serving,
              Optional.ofNullable(newest),
              holderEnded);
      return new LoopFacts(
          PROJECT,
          SPEC,
          rows,
          pipeline,
          open,
          failedStage,
          erroredAttempts,
          Optional.ofNullable(asked));
    }
  }
}
