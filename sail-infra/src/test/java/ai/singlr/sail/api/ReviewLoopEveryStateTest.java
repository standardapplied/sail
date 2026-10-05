/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static ai.singlr.sail.api.ReviewLoop.PROJECT;
import static ai.singlr.sail.api.ReviewScripts.CLEAN_REVIEW;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.RunStatus;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AgentUnit;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * No state of the review loop is a dead end: every combination of rows the stores accept is written
 * straight into them and given one pipeline step, after which its review is served by a run,
 * waiting on a recorded run, a person's, passed, or escalated with a reason.
 */
class ReviewLoopEveryStateTest {

  @TempDir Path tempDir;
  private ReviewLoop loop;

  @AfterEach
  void tearDown() {
    loop.close();
  }

  /** How a review's row can stand: every status its CHECK admits, with and without an error. */
  private enum ReviewShape {
    PENDING("pending", null),
    RUNNING("running", null),
    PASSED("passed", null),
    GATE_FAILED("failed", null),
    ERRORED("failed", "reviewer could not start"),
    ESCALATED("escalated", "reviewer stopped by an operator"),
    ESCALATED_BEFORE_REASONS_WERE_RECORDED("escalated", null);

    private final String status;
    private final String error;

    ReviewShape(String status, String error) {
      this.status = status;
      this.error = error;
    }
  }

  /** The run that serves the review, if any: its lane, and whether it has ended. */
  private enum Serving {
    NO_RUN(null, false),
    A_LIVE_REVIEWER(Lane.REVIEW, false),
    AN_ENDED_REVIEWER(Lane.REVIEW, true),
    A_LIVE_FIX_AGENT(Lane.FIX, false),
    AN_ENDED_FIX_AGENT(Lane.FIX, true);

    private final Lane lane;
    private final boolean ended;

    Serving(Lane lane, boolean ended) {
      this.lane = lane;
      this.ended = ended;
    }
  }

  /** What the review recorded that it waits on. */
  private enum Wait {
    ON_NO_RUN,
    ON_A_LIVE_RUN,
    ON_AN_ENDED_RUN
  }

  /** Whether the review's stage rows are still the stages of the project's pipeline. */
  private enum Pipeline {
    AS_THE_REVIEW_HOLDS_IT,
    WITH_ITS_AGENT_STAGE_RENAMED
  }

  private static final List<String> STAGE_STATUSES =
      List.of("pending", "running", "passed", "failed", "skipped");

  private static final ReviewPipelineConfig AN_AGENT_THEN_A_PERSON =
      ReviewPipelineConfig.fromMap(
          Map.of(
              "max_iterations",
              3,
              "stages",
              List.of(
                  Map.<String, Object>of(
                      "name", "codex", "type", "agent", "agent", "codex", "gate", "no_critical"),
                  Map.<String, Object>of("name", "approve", "type", "human"))));

  /** No stage rows yet, or the agent stage and the person's stage in every pair of statuses. */
  private static List<List<String>> stageShapes() {
    var shapes = new ArrayList<List<String>>();
    shapes.add(List.of());
    for (var agent : STAGE_STATUSES) {
      for (var person : STAGE_STATUSES) {
        shapes.add(List.of(agent, person));
      }
    }
    return shapes;
  }

  private static Stream<Arguments> reviewAndSpecStates() {
    return Arrays.stream(ReviewShape.values())
        .flatMap(
            shape -> Arrays.stream(SpecStatus.values()).map(spec -> Arguments.of(shape, spec)));
  }

  private static final String STATE = "auth";

  /**
   * Writes one state straight into the tables, as rows, in place of the one before it — whether or
   * not the loop would ever write it: {@link #STATE} in {@code specStatus} with a finished build,
   * its review in {@code shape}, the review's stage rows, the run that serves it and the run it
   * waits on.
   */
  private void state(
      ReviewShape shape,
      SpecStatus specStatus,
      List<String> stages,
      Pipeline pipeline,
      Serving serving,
      Wait wait) {
    loop.container.live().forEach(runId -> loop.container.exited(runId, "", 0));
    loop.db.transaction(
        () -> {
          loop.db.execute(
              """
              DELETE FROM review_stages
              WHERE review_id IN (SELECT id FROM reviews WHERE spec_id = ?)""",
              STATE);
          loop.db.execute("DELETE FROM reviews WHERE spec_id = ?", STATE);
          loop.db.execute("DELETE FROM runs WHERE spec_id = ?", STATE);
          loop.db.execute("UPDATE specs SET status = ? WHERE id = ?", specStatus.wire(), STATE);
          runRow("build", null, true);
          var review = DateTimeUtils.newId().toString();
          var served = serving.lane == null ? null : runRow(serving.lane.wire(), review, false);
          var holder = wait == Wait.ON_NO_RUN ? null : runRow(Lane.ROOM_FULL.wire(), null, false);
          loop.db.execute(
              """
              INSERT INTO reviews (id, spec_id, iteration, status, created_at, error, waiting_on)
              VALUES (?, ?, 1, ?, ?, ?, ?)""",
              review,
              STATE,
              shape.status,
              DateTimeUtils.now().toString(),
              shape.error,
              holder);
          for (var i = 0; i < stages.size(); i++) {
            loop.db.execute(
                """
                INSERT INTO review_stages (id, review_id, name, stage_type, status, started_at)
                VALUES (?, ?, ?, ?, ?, ?)""",
                DateTimeUtils.newId().toString(),
                review,
                i == 0 && pipeline == Pipeline.WITH_ITS_AGENT_STAGE_RENAMED
                    ? "the-stage-before-the-rename"
                    : AN_AGENT_THEN_A_PERSON.stages().get(i).name(),
                i == 0 ? "agent" : "human",
                stages.get(i),
                "running".equals(stages.get(i)) ? DateTimeUtils.now().toString() : null);
          }
          if (serving.ended) {
            ends(served, CLEAN_REVIEW);
          }
          if (wait == Wait.ON_AN_ENDED_RUN) {
            ends(holder, "replied");
          }
        });
  }

  /** A run row of {@link #STATE} in lane {@code role}: ended, or with its agent at work. */
  private String runRow(String role, String review, boolean ended) {
    var id = DateTimeUtils.newId().toString();
    var unit = AgentUnit.forRun(id);
    loop.db.execute(
        """
        INSERT INTO runs (id, project, spec_id, node, owner, role, agent, branch, task, status,
            started_at, log_path, unit, repos, review_id)
        VALUES (?, ?, ?, ?, ?, ?, 'claude-code', 'feat/test', 'work', 'running', ?, ?, ?, '[]', ?)""",
        id,
        PROJECT,
        STATE,
        ReviewLoop.HANDLE,
        ReviewLoop.HANDLE,
        role,
        DateTimeUtils.now().toString(),
        unit.logPath(),
        unit.unitName(),
        review);
    loop.container.started(id);
    if (ended) {
      ends(id, "done");
    }
    return id;
  }

  private void ends(String runId, String log) {
    loop.container.exited(runId, log, 0);
    loop.db.execute(
        "UPDATE runs SET status = 'stopped', exit_code = 0, completed_at = ? WHERE id = ?",
        DateTimeUtils.now().toString(),
        runId);
  }

  /** One pipeline step: the stop of the spec's newest ended loop run, as the reconciler replays. */
  private void step(String spec) {
    var newestEnded =
        loop.runs.listForSpec(spec).stream()
            .filter(run -> !Lane.ROOM_FULL.matches(run.role()))
            .filter(run -> RunStatus.isTerminal(run.status()))
            .findFirst()
            .orElseThrow();
    loop.onEvent(MissedStopReconciler.stopEvent(newestEnded, newestEnded.exitCode(), null));
  }

  /**
   * Which of the five ends {@code spec}'s latest review is at, or empty when it is at none: it is
   * served by a run, waits on a recorded run that still lives, is a person's — a person's stage is
   * open, or its spec is in a status the loop never acts over — passed, or escalated, saying why
   * whenever it was the loop that escalated it.
   */
  private Optional<String> endOf(String spec, ReviewShape before) {
    var review = loop.reviews.latestReviewForSpec(spec).orElseThrow();
    var state = new ReviewLoopState(loop.reviews, loop.runs, () -> ReviewLoop.HANDLE);
    var status = loop.specStatus(spec);
    if ("passed".equals(review.status())) {
      return Optional.of("passed");
    }
    if ("escalated".equals(review.status())) {
      return "escalated".equals(before.status) || review.error() != null
          ? Optional.of("escalated")
          : Optional.empty();
    }
    if (status != SpecStatus.IN_PROGRESS && status != SpecStatus.REVIEW) {
      return Optional.of("a person's: its spec is " + status.wire());
    }
    if (state.served(review.id())) {
      return Optional.of("served by a run");
    }
    if (review.waitingOn() != null
        && loop.runs
            .findById(review.waitingOn())
            .filter(run -> !RunStatus.isTerminal(run.status()))
            .isPresent()) {
      return Optional.of("waiting on a recorded run");
    }
    return loop.reviews.stagesForReview(review.id()).stream()
            .anyMatch(
                stage -> "human".equals(stage.stageType()) && "running".equals(stage.status()))
        ? Optional.of("a person's: a stage waits on them")
        : Optional.empty();
  }

  @ParameterizedTest(name = "review {0}, spec {1}")
  @MethodSource("reviewAndSpecStates")
  void everyStateTheStoresCanHoldIsOneStepFromServedWaitingOwnedPassedOrEscalated(
      ReviewShape shape, SpecStatus specStatus) {
    loop = ReviewLoop.of(tempDir, AN_AGENT_THEN_A_PERSON);
    loop.spec(STATE, "api");
    var read = new ReviewLoopState(loop.reviews, loop.runs, () -> ReviewLoop.HANDLE);
    var deadEnds = new ArrayList<String>();
    var blamedOnThePipeline = new ArrayList<String>();
    var states = 0;
    for (var stages : stageShapes()) {
      for (var pipeline : Pipeline.values()) {
        for (var serving : Serving.values()) {
          for (var wait : Wait.values()) {
            states++;
            state(shape, specStatus, stages, pipeline, serving, wait);
            var owed = read.owed(STATE).getClass().getSimpleName();

            step(STATE);

            var left = loop.reviews.latestReviewForSpec(STATE).orElseThrow();
            if (pipeline == Pipeline.AS_THE_REVIEW_HOLDS_IT
                && left.error() != null
                && left.error().contains("review pipeline")) {
              blamedOnThePipeline.add("stages %s, %s, waiting %s".formatted(stages, serving, wait));
            }
            if (endOf(STATE, shape).isEmpty()) {
              deadEnds.add(
                  "stages %s, pipeline %s, %s, waiting %s: owed %s, left %s (%s) with its spec %s"
                      .formatted(
                          stages,
                          pipeline,
                          serving,
                          wait,
                          owed,
                          left.status(),
                          left.error(),
                          loop.specStatus(STATE).wire()));
            }
          }
        }
      }
    }

    assertEquals(List.of(), deadEnds, "every state is one step from an end");
    assertEquals(
        List.of(),
        blamedOnThePipeline,
        "and no review whose stages are still the pipeline's is handed to a person for a"
            + " pipeline that changed");
    assertEquals(
        stageShapes().size()
            * Pipeline.values().length
            * Serving.values().length
            * Wait.values().length,
        states);
    assertEquals(List.of(), loop.details("review_pipeline_error"), "and no step failed");
  }
}
