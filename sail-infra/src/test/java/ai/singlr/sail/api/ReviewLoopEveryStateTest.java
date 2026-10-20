/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static ai.singlr.sail.api.LoopRows.AN_AGENT_THEN_A_PERSON;
import static ai.singlr.sail.api.LoopRows.REVIEW;
import static ai.singlr.sail.api.ReviewLoop.PROJECT;
import static ai.singlr.sail.api.ReviewScripts.CLEAN_REVIEW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.RunStatus;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.store.ReviewStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * No state of the review loop is a dead end: for every combination of rows the stores accept, the
 * steps one stop leads to leave its review served by a run or waiting on a recorded one, a
 * person's, passed, or escalated with a reason. The whole space is walked on {@link
 * LoopDecision#next}, with facts built in memory and each step that leaves something to follow
 * modelled as {@code LoopStepsTest} proves it of the real one; one state for each step the walk
 * takes is then written into the real stores and given a real stop, and ends where the walk said.
 */
class ReviewLoopEveryStateTest {

  @TempDir Path tempDir;
  private ReviewLoop loop;

  @AfterEach
  void tearDown() {
    if (loop != null) {
      loop.close();
    }
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

  /** Where one stop leaves a review. */
  private enum End {
    A_RUNS,
    A_PERSONS,
    PASSED,
    ESCALATED
  }

  /** One combination of rows: {@link #STATE}'s spec, its review and stages, and its runs. */
  private record State(
      ReviewShape shape,
      SpecStatus specStatus,
      List<String> stages,
      Pipeline pipeline,
      Serving serving,
      Wait waiting) {

    String stageName(int place) {
      return place == 0 && pipeline == Pipeline.WITH_ITS_AGENT_STAGE_RENAMED
          ? "the-stage-before-the-rename"
          : AN_AGENT_THEN_A_PERSON.stages().get(place).name();
    }

    @Override
    public String toString() {
      return "review %s, spec %s, stages %s, pipeline %s, %s, waiting %s"
          .formatted(shape, specStatus, stages, pipeline, serving, waiting);
    }
  }

  private static final List<String> STAGE_STATUSES =
      List.of("pending", "running", "passed", "failed", "skipped");

  private static final String STATE = LoopRows.SPEC;

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

  /** Every state of one review shape and one spec status. */
  private static List<State> states(ReviewShape shape, SpecStatus specStatus) {
    var states = new ArrayList<State>();
    for (var stages : stageShapes()) {
      for (var pipeline : Pipeline.values()) {
        for (var serving : Serving.values()) {
          for (var wait : Wait.values()) {
            states.add(new State(shape, specStatus, stages, pipeline, serving, wait));
          }
        }
      }
    }
    return states;
  }

  /** The rows of {@code state} as the reader would hand them to a decision. */
  private static LoopRows.Facts facts(State state) {
    var lane = state.serving().lane;
    var review =
        LoopRows.review(
            1,
            state.shape().status,
            state.shape().error,
            state.waiting() == Wait.ON_NO_RUN ? null : "holder");
    var stages =
        IntStream.range(0, state.stages().size())
            .mapToObj(
                place ->
                    LoopRows.stage(
                        state.stageName(place),
                        place == 0 ? "agent" : "human",
                        state.stages().get(place)))
            .toList();
    var facts =
        LoopRows.facts()
            .spec(state.specStatus())
            .review(review, stages)
            .newestLoopRun(LoopRows.ended("build", "build", null))
            .erroredAttempts(state.shape().error == null ? 0 : 1)
            .holderEnded(state.waiting() == Wait.ON_AN_ENDED_RUN);
    if (lane == null) {
      return facts;
    }
    return facts.serving(
        state.serving().ended
            ? LoopRows.ended("served", lane.wire(), REVIEW)
            : LoopRows.run("served", lane.wire(), REVIEW));
  }

  /** The stop of the spec's newest ended loop run, as the router hands it to the decision. */
  private static LoopTrigger stopOf(LoopFacts facts) {
    var ended =
        facts.rows().serving().stream()
            .filter(run -> RunStatus.isTerminal(run.status()))
            .findFirst();
    if (ended.isEmpty()) {
      return new LoopTrigger.BuildEnded("build", 0);
    }
    return Lane.FIX.matches(ended.get().role())
        ? new LoopTrigger.FixEnded(ended.get(), Optional.empty())
        : new LoopTrigger.ReviewerEnded(ended.get(), Optional.empty());
  }

  /** The steps one stop leads to from a state, and where the last of them leaves its review. */
  private record Walk(List<LoopStep> steps, Optional<End> end) {

    boolean blamesThePipeline() {
      return steps.getLast() instanceof LoopStep.Escalate escalated
          && escalated.reason().contains("review pipeline");
    }
  }

  /**
   * Walks {@code state} from the stop of its newest ended run: each step is the decision's, and a
   * step that leaves something to follow changes the rows as the real one does — a review started
   * is a new one, running, that no run serves; a review resumed is running; a clean reviewer's
   * stage has passed; what a fix agent left is committed.
   */
  private static Walk walk(State state) {
    var rows = facts(state);
    var steps = new ArrayList<LoopStep>();
    var trigger = Optional.of(stopOf(rows.build()));
    while (trigger.isPresent()) {
      var facts = rows.build();
      var step = LoopDecision.next(facts, trigger.get());
      steps.add(step);
      assertTrue(steps.size() <= ReviewPipelineController.MAX_STEPS, () -> state + ": " + steps);
      trigger =
          switch (step) {
            case LoopStep.StartReview start -> {
              rows.review(LoopRows.review(start.iteration(), "running", null, null), List.of())
                  .serving()
                  .spec(SpecStatus.REVIEW);
              yield Optional.of(new LoopTrigger.ReviewRunning());
            }
            case LoopStep.Resume resumed -> {
              var review = resumed.review();
              var running =
                  LoopRows.review(review.iteration(), "running", null, review.waitingOn());
              rows.review(running, facts.rows().stages()).spec(SpecStatus.REVIEW);
              yield Optional.of(new LoopTrigger.ReviewRunning());
            }
            case LoopStep.ReadVerdict verdict -> {
              var stages = new ArrayList<>(facts.rows().stages());
              var judged = stages.get(verdict.stage());
              stages.set(verdict.stage(), LoopRows.stage(judged.name(), "agent", "passed"));
              rows.review(verdict.review(), stages);
              yield Optional.of(
                  new LoopTrigger.StageJudged(
                      verdict.review().id(),
                      verdict.stage(),
                      new StageVerdicts.StageOutcome.Passed()));
            }
            case LoopStep.CommitFixLeftovers leftovers ->
                Optional.of(new LoopTrigger.FixCommitted(leftovers.run()));
            default -> Optional.empty();
          };
    }
    return new Walk(steps, endAfter(steps.getLast(), rows.build()));
  }

  /** Which end the last step of a walk leaves the review at, or empty when it is at none. */
  private static Optional<End> endAfter(LoopStep last, LoopFacts facts) {
    return switch (last) {
      case LoopStep.Nothing nothing -> endOf(facts.rows());
      case LoopStep.Pass passed -> Optional.of(End.PASSED);
      case LoopStep.AwaitAPerson awaited ->
          Optional.of(waitsOnALiveRun(facts.rows()) ? End.A_RUNS : End.A_PERSONS);
      case LoopStep.LaunchReviewer launched -> Optional.of(End.A_RUNS);
      case LoopStep.LaunchFix launched -> Optional.of(End.A_RUNS);
      case LoopStep.Escalate escalated ->
          Optional.of(End.ESCALATED).filter(reasoned -> !escalated.reason().isBlank());
      default -> Optional.empty();
    };
  }

  /**
   * Which end rows the loop left alone are at, or empty when they are at none: the review passed or
   * was escalated, its spec is in a status the loop never acts over, a run serves it, it waits on a
   * recorded run that still lives, or a person's stage is open.
   */
  private static Optional<End> endOf(LoopFacts.Rows facts) {
    var review = facts.review().orElseThrow();
    if ("passed".equals(review.status())) {
      return Optional.of(End.PASSED);
    }
    if ("escalated".equals(review.status())) {
      return Optional.of(End.ESCALATED);
    }
    if (!facts.loops()) {
      return Optional.of(End.A_PERSONS);
    }
    if (LoopFacts.Rows.live(facts.serving()) || waitsOnALiveRun(facts)) {
      return Optional.of(End.A_RUNS);
    }
    return facts.stages().stream()
            .anyMatch(
                stage -> "human".equals(stage.stageType()) && "running".equals(stage.status()))
        ? Optional.of(End.A_PERSONS)
        : Optional.empty();
  }

  private static boolean waitsOnALiveRun(LoopFacts.Rows rows) {
    return rows.review().orElseThrow().waitingOn() != null && !rows.holderEnded();
  }

  @ParameterizedTest(name = "review {0}, spec {1}")
  @MethodSource("reviewAndSpecStates")
  void everyStateTheStoresCanHoldIsOneStepFromServedWaitingOwnedPassedOrEscalated(
      ReviewShape shape, SpecStatus specStatus) {
    var deadEnds = new ArrayList<String>();
    var blamedOnThePipeline = new ArrayList<String>();
    var states = states(shape, specStatus);

    for (var state : states) {
      var walk = walk(state);
      if (state.pipeline() == Pipeline.AS_THE_REVIEW_HOLDS_IT && walk.blamesThePipeline()) {
        blamedOnThePipeline.add(state.toString());
      }
      if (walk.end().isEmpty()) {
        deadEnds.add(state + ": took " + walk.steps());
      }
    }

    assertEquals(List.of(), deadEnds, "every state is one stop from an end");
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
        states.size());
  }

  /** The first state of the space whose walk takes each kind of step, by that step's name. */
  private static Stream<Arguments> aStateForEachStepTheLoopTakes() {
    var states = new LinkedHashMap<String, State>();
    reviewAndSpecStates()
        .flatMap(of -> states((ReviewShape) of.get()[0], (SpecStatus) of.get()[1]).stream())
        .forEach(
            state ->
                walk(state)
                    .steps()
                    .forEach(step -> states.putIfAbsent(step.getClass().getSimpleName(), state)));
    return states.entrySet().stream().map(of -> Arguments.of(of.getKey(), of.getValue()));
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("aStateForEachStepTheLoopTakes")
  void aStateForEachStepTheLoopTakesEndsInTheRealStoresWhereTheDecisionSaidItWould(
      String step, State state) {
    loop = ReviewLoop.of(tempDir, AN_AGENT_THEN_A_PERSON);
    loop.spec(STATE, "api");
    write(state);

    stop(STATE);

    assertEquals(walk(state).end(), endInTheStores(state), step);
    assertEquals(List.of(), loop.details("review_pipeline_error"), "and no step failed");
  }

  /**
   * What the walk says of a state is what the stores say of it: every state of a spec the loop
   * moves is written into the real stores and given a real stop, and so is every thirteenth state
   * of a spec it never acts over, starting a place further for each review and status so that
   * between them they try every combination of stages, pipeline, run and wait.
   */
  @ParameterizedTest(name = "review {0}, spec {1}")
  @MethodSource("reviewAndSpecStates")
  void theWalkEndsWhereTheRealStoresDoAcrossTheSpace(ReviewShape shape, SpecStatus specStatus) {
    loop = ReviewLoop.of(tempDir, AN_AGENT_THEN_A_PERSON);
    loop.spec(STATE, "api");
    var states = states(shape, specStatus);
    var apart = new ArrayList<String>();

    var loops = specStatus == SpecStatus.IN_PROGRESS || specStatus == SpecStatus.REVIEW;
    var stride = loops ? 1 : 13;
    var first =
        loops ? 0 : (shape.ordinal() * SpecStatus.values().length + specStatus.ordinal()) % 13;
    for (var place = first; place < states.size(); place += stride) {
      var state = states.get(place);
      write(state);
      stop(STATE);
      var walked = walk(state).end();
      var stored = endInTheStores(state);
      if (!walked.equals(stored)) {
        apart.add(state + ": walked to " + walked + ", stored " + stored);
      }
    }

    assertEquals(List.of(), apart);
    assertEquals(List.of(), loop.details("review_pipeline_error"), "and no step failed");
  }

  /**
   * Writes one state straight into the tables, as rows, in place of the one before it — whether or
   * not the loop would ever write it: {@link #STATE} in its status with a finished build, its
   * review, the review's stage rows, the run that serves it and the run it waits on.
   */
  private void write(State state) {
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
          loop.db.execute(
              "UPDATE specs SET status = ? WHERE id = ?", state.specStatus().wire(), STATE);
          runRow("build", null, true);
          var review = DateTimeUtils.newId().toString();
          var lane = state.serving().lane;
          var served = lane == null ? null : runRow(lane.wire(), review, false);
          var holder =
              state.waiting() == Wait.ON_NO_RUN ? null : runRow(Lane.ROOM_FULL.wire(), null, false);
          loop.db.execute(
              """
              INSERT INTO reviews (id, spec_id, iteration, status, created_at, error, waiting_on)
              VALUES (?, ?, 1, ?, ?, ?, ?)""",
              review,
              STATE,
              state.shape().status,
              DateTimeUtils.now().toString(),
              state.shape().error,
              holder);
          for (var place = 0; place < state.stages().size(); place++) {
            var status = state.stages().get(place);
            loop.db.execute(
                """
                INSERT INTO review_stages (id, review_id, name, stage_type, status, started_at)
                VALUES (?, ?, ?, ?, ?, ?)""",
                DateTimeUtils.newId().toString(),
                review,
                state.stageName(place),
                place == 0 ? "agent" : "human",
                status,
                "running".equals(status) ? DateTimeUtils.now().toString() : null);
          }
          if (state.serving().ended) {
            ends(served, CLEAN_REVIEW);
          }
          if (state.waiting() == Wait.ON_AN_ENDED_RUN) {
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

  /** The stop of the spec's newest ended loop run, as the reconciler replays it. */
  private void stop(String spec) {
    var newestEnded =
        loop.runs.listForSpec(spec).stream()
            .filter(run -> !Lane.ROOM_FULL.matches(run.role()))
            .filter(run -> RunStatus.isTerminal(run.status()))
            .findFirst()
            .orElseThrow();
    loop.onEvent(MissedStopReconciler.stopEvent(newestEnded, newestEnded.exitCode(), null));
  }

  /**
   * Which end the spec's latest review is at in the stores, or empty when it is at none. An
   * escalation counts only when it says why, unless the review was escalated before the stop.
   */
  private Optional<End> endInTheStores(State before) {
    var review = loop.reviews.latestReviewForSpec(STATE).orElseThrow();
    var status = loop.specStatus(STATE);
    if ("passed".equals(review.status())) {
      return Optional.of(End.PASSED);
    }
    if ("escalated".equals(review.status())) {
      return Optional.of(End.ESCALATED)
          .filter(reasoned -> "escalated".equals(before.shape().status) || review.error() != null);
    }
    if (status != SpecStatus.IN_PROGRESS && status != SpecStatus.REVIEW) {
      return Optional.of(End.A_PERSONS);
    }
    if (LoopFacts.Rows.live(loop.runs.forReview(review.id())) || waitsOnALiveRun(review)) {
      return Optional.of(End.A_RUNS);
    }
    return loop.reviews.stagesForReview(review.id()).stream()
            .anyMatch(
                stage -> "human".equals(stage.stageType()) && "running".equals(stage.status()))
        ? Optional.of(End.A_PERSONS)
        : Optional.empty();
  }

  private boolean waitsOnALiveRun(ReviewStore.ReviewRow review) {
    return review.waitingOn() != null
        && LoopFacts.Rows.live(loop.runs.findById(review.waitingOn()).stream().toList());
  }
}
