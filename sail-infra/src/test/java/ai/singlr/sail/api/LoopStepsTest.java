/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static ai.singlr.sail.api.LoopRows.AN_AGENT_THEN_A_PERSON;
import static ai.singlr.sail.api.LoopRows.SPEC;
import static ai.singlr.sail.api.ReviewLoop.PROJECT;
import static ai.singlr.sail.api.ReviewScripts.CLEAN_REVIEW;
import static ai.singlr.sail.api.ReviewScripts.CRITICAL_FINDING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Each step of the review loop leaves the rows in the state its name says, and says on the bus what
 * it did, after the write: {@link LoopSteps} over the real stores and the real launch path, with no
 * decision before it and no stop after it.
 */
class LoopStepsTest {

  /**
   * What main is told when the sync trigger fires: how many events the step had published by then,
   * and how the spec, its review, the run that review waits on and its stages stood.
   */
  private record Told(
      long events, SpecStatus spec, String review, String waitingOn, List<String> stages) {}

  /** What main is told of a review that waits on no run. */
  private static Told told(long events, SpecStatus spec, String review, String... stages) {
    return new Told(events, spec, review, null, List.of(stages));
  }

  @TempDir Path tempDir;
  private ReviewLoop loop;
  private LoopFactsReader reader;
  private LoopSteps steps;
  private final AtomicReference<ReviewLanes> lanes = new AtomicReference<>();
  private final List<Told> told = new ArrayList<>();
  private long eventsBefore;

  @BeforeEach
  void setUp() {
    loop = ReviewLoop.of(tempDir, AN_AGENT_THEN_A_PERSON);
    loop.spec(SPEC, "api");
    lanes.set(loop.operations.reviewLanes());
    var narrator = new LoopNarrator(loop.specs, loop.bus, this::sync);
    narrator.useMessages(loop.messages);
    reader = new LoopFactsReader(loop.specs, loop.reviews, loop.runs, () -> ReviewLoop.HANDLE);
    steps =
        new LoopSteps(
            loop.specs,
            loop.reviews,
            new ReviewLanes() {
              @Override
              public Launch launch(Invocation invocation, String boxHandle, Runnable claimed) {
                return lanes.get().launch(invocation, boxHandle, claimed);
              }

              @Override
              public String output(RunStore.RunRow run) throws Exception {
                return lanes.get().output(run);
              }

              @Override
              public List<Rescue> ensureCommitted(
                  String project, List<String> repos, String branch, String commitMessage)
                  throws Exception {
                return lanes.get().ensureCommitted(project, repos, branch, commitMessage);
              }
            },
            reader,
            narrator,
            this::sync,
            () -> ReviewLoop.HANDLE);
  }

  private void sync() {
    var review = loop.reviews.latestReviewForSpec(SPEC);
    told.add(
        new Told(
            loop.bus.publishedCount() - eventsBefore,
            loop.specStatus(SPEC),
            review.map(ReviewStore.ReviewRow::status).orElse(null),
            review.map(ReviewStore.ReviewRow::waitingOn).orElse(null),
            review.map(latest -> statuses(latest.id())).orElse(List.of())));
  }

  private List<String> statuses(String reviewId) {
    return loop.reviews.stagesForReview(reviewId).stream()
        .map(ReviewStore.StageRow::status)
        .toList();
  }

  /** The real lanes, their launch handed to {@code launch} to run, or not, as it likes. */
  private void launching(Function<Supplier<ReviewLanes.Launch>, ReviewLanes.Launch> launch) {
    var real = lanes.get();
    lanes.set(
        new ReviewLanes() {
          @Override
          public Launch launch(Invocation invocation, String boxHandle, Runnable claimed) {
            return launch.apply(() -> real.launch(invocation, boxHandle, claimed));
          }

          @Override
          public String output(RunStore.RunRow run) throws Exception {
            return real.output(run);
          }

          @Override
          public List<Rescue> ensureCommitted(
              String project, List<String> repos, String branch, String commitMessage)
              throws Exception {
            return real.ensureCommitted(project, repos, branch, commitMessage);
          }
        });
  }

  @AfterEach
  void tearDown() {
    loop.close();
  }

  /** Takes {@code step} on the rows as they stand, as this box's machinery, and settles the bus. */
  private Optional<LoopTrigger> take(LoopStep step) {
    told.clear();
    eventsBefore = loop.bus.publishedCount();
    var facts = reader.read(PROJECT, SPEC, LoopRows.staged(AN_AGENT_THEN_A_PERSON));
    var followUp = Acting.system(() -> steps.take(facts, step));
    loop.settle();
    return followUp;
  }

  /** Writes iteration 1 of the spec's review, {@code running}, with no stage rows yet. */
  private ReviewStore.ReviewRow reviewing() {
    Acting.system(() -> loop.reviews.createReview(SPEC, 1));
    return review();
  }

  private ReviewStore.ReviewRow review() {
    return loop.reviews.latestReviewForSpec(SPEC).orElseThrow();
  }

  private List<ReviewStore.StageRow> stages() {
    return loop.reviews.stagesForReview(review().id());
  }

  /** A review whose first stage a reviewer ran to its end, saying {@code log}; its stop unheard. */
  private RunStore.RunRow reviewed(String log) {
    take(new LoopStep.LaunchReviewer(reviewing(), 0, "codex"));
    var reviewer = loop.onlyLive();
    loop.container.exited(reviewer.id(), log, 0);
    Acting.system(() -> loop.runs.complete(reviewer.id(), "stopped", 0));
    return loop.runs.findById(reviewer.id()).orElseThrow();
  }

  private String holder() {
    var holder = loop.run("billing", "build");
    loop.container.started(holder);
    return holder;
  }

  @Test
  void nothingWritesNothingAndSaysNothing() {
    assertEquals(Optional.empty(), take(new LoopStep.Nothing()));

    assertEquals(List.of(), told);
    assertEquals(List.of(), loop.published);
    assertEquals(SpecStatus.IN_PROGRESS, loop.specStatus(SPEC));
  }

  @Test
  void sayBuildFailedSaysTheExitCodeAndLeavesTheSpecForTriage() {
    assertEquals(Optional.empty(), take(new LoopStep.SayBuildFailed(3)));

    assertEquals(List.of("exit 3"), loop.details(Event.WellKnownTypes.AGENT_FAILED));
    assertEquals(SpecStatus.IN_PROGRESS, loop.specStatus(SPEC));
    assertTrue(loop.reviews.latestReviewForSpec(SPEC).isEmpty());
    assertEquals(List.of(), told, "nothing was written");
  }

  @Test
  void parkForAPersonMovesTheSpecToReviewAndWritesNoReview() {
    assertEquals(Optional.empty(), take(new LoopStep.ParkForAPerson()));

    assertEquals(SpecStatus.REVIEW, loop.specStatus(SPEC));
    assertTrue(loop.reviews.latestReviewForSpec(SPEC).isEmpty());
    assertEquals(List.of(told(0, SpecStatus.REVIEW, null)), told, "the move reaches main");
  }

  @Test
  void startReviewWritesTheIterationRunningAndMovesItsSpecToReview() {
    assertEquals(Optional.of(new LoopTrigger.ReviewRunning()), take(new LoopStep.StartReview(2)));

    assertEquals(2, review().iteration());
    assertEquals("running", review().status());
    assertEquals(List.of(), stages(), "its stage rows are the next step's to write");
    assertEquals(SpecStatus.REVIEW, loop.specStatus(SPEC));
    assertEquals(
        List.of(told(0, SpecStatus.IN_PROGRESS, "running"), told(0, SpecStatus.REVIEW, "running")),
        told,
        "main is told of the review row, written first, and then of the spec's move");
  }

  @Test
  void escalateNewWritesTheReviewAndHandsItToAPersonSayingWhy() {
    assertEquals(Optional.empty(), take(new LoopStep.EscalateNew(1, "sail.yaml is unreadable")));

    assertEquals("escalated", review().status());
    assertEquals("sail.yaml is unreadable", review().error());
    assertEquals(SpecStatus.REVIEW, loop.specStatus(SPEC));
    assertEquals(List.of("sail.yaml is unreadable"), loop.details("review_escalated"));
    assertEquals(1, loop.roomLines(SPEC, "Review escalated").size());
    assertEquals(
        List.of(
            told(0, SpecStatus.IN_PROGRESS, "running"), told(0, SpecStatus.REVIEW, "escalated")),
        told,
        "main is told of the review row and of its end, each before anything is said of it");
  }

  @Test
  void resumeTurnsALegacyPendingReviewRunningAndMovesItsSpecToReview() {
    var pending = reviewing();
    loop.db.execute("UPDATE reviews SET status = 'pending' WHERE id = ?", pending.id());

    assertEquals(Optional.of(new LoopTrigger.ReviewRunning()), take(new LoopStep.Resume(review())));

    assertEquals("running", review().status());
    assertEquals(SpecStatus.REVIEW, loop.specStatus(SPEC));
    assertEquals(List.of(told(0, SpecStatus.REVIEW, "running")), told, "the spec's move");
  }

  @Test
  void resumeOfASpecSomeoneElseMovedGoesOnWithItsReviewAndTellsMainOfNoMove() {
    var running = reviewing();
    Acting.system(() -> loop.specs.updateStatus(SPEC, SpecStatus.CANCELLED));

    assertEquals(Optional.of(new LoopTrigger.ReviewRunning()), take(new LoopStep.Resume(running)));

    assertEquals(SpecStatus.CANCELLED, loop.specStatus(SPEC));
    assertEquals(List.of(), told, "a spec that did not move is nothing to tell main of");
  }

  @Test
  void resumeMovesTheSpecOfAReviewAlreadyRunningAndKeepsTheWaitItRecorded() {
    var running = reviewing();
    loop.db.execute("UPDATE reviews SET waiting_on = 'holder-1' WHERE id = ?", running.id());

    assertEquals(Optional.of(new LoopTrigger.ReviewRunning()), take(new LoopStep.Resume(review())));

    assertEquals("running", review().status());
    assertEquals("holder-1", review().waitingOn(), "only the launch that serves it clears a wait");
    assertEquals(SpecStatus.REVIEW, loop.specStatus(SPEC));
  }

  @Test
  void launchReviewerStartsTheStageOnceARunServesItsReviewAndAnnouncesIt() {
    var review = reviewing();
    loop.db.execute("UPDATE reviews SET waiting_on = 'holder-1' WHERE id = ?", review.id());

    assertEquals(Optional.empty(), take(new LoopStep.LaunchReviewer(review, 0, "codex")));

    var reviewer = loop.onlyLive();
    assertEquals("review", reviewer.role());
    assertEquals(review.id(), reviewer.reviewId());
    assertEquals(List.of("running", "pending"), statuses(review.id()));
    assertEquals("codex", stages().getFirst().reviewer());
    assertNull(review().waitingOn(), "a run serves it now: it waits on that run's stop");
    assertEquals(List.of("codex"), loop.details("review_stage_started"));
    assertEquals(
        List.of(List.of("pending", "pending"), List.of("running", "pending")),
        told.stream().map(Told::stages).toList(),
        "main is told of the stage rows once they are written, and then that a run serves it");
    assertEquals(
        Arrays.asList("holder-1", null),
        told.stream().map(Told::waitingOn).toList(),
        "by when the wait it recorded is cleared");
  }

  @Test
  void aLaunchCompletesTheStageRowsOfAReviewACrashLeftWithTooFew() {
    var review = reviewing();
    Acting.system(() -> loop.reviews.createStage(review.id(), "codex", "agent"));

    take(new LoopStep.LaunchReviewer(review, 0, "codex"));

    assertEquals(
        List.of("codex", "approve"),
        stages().stream().map(ReviewStore.StageRow::name).toList(),
        "the row it held is kept, and only the missing one is written");
  }

  @Test
  void launchReviewerWhoseLaunchFailsAfterItsAgentStartedWaitsForThatAgentsStop() {
    var review = reviewing();
    loop.db.execute("UPDATE reviews SET waiting_on = 'holder-1' WHERE id = ?", review.id());
    launching(
        real -> {
          real.get();
          throw new IllegalStateException("status read failed");
        });

    assertEquals(Optional.empty(), take(new LoopStep.LaunchReviewer(review, 0, "codex")));

    assertEquals(review.id(), loop.onlyLive().reviewId());
    assertEquals(List.of("running", "pending"), statuses(review.id()));
    assertNull(review().waitingOn(), "a run serves it: it waits on that run's stop");
    assertEquals(List.of("codex"), loop.details("review_stage_started"));
    assertEquals("running", review().status(), "a working agent is never called a failure");
  }

  @Test
  void launchReviewerRefusedItsClaimRecordsTheWaitAndItsRoomLineAndStartsNoStage() {
    var review = reviewing();
    var holder = holder();

    assertEquals(Optional.empty(), take(new LoopStep.LaunchReviewer(review, 0, "codex")));

    assertEquals(holder, review().waitingOn());
    assertEquals(List.of("pending", "pending"), statuses(review.id()));
    assertEquals(1, loop.roomLines(SPEC, "Review is waiting for run").size());
    assertEquals(List.of(), loop.details("review_stage_started"));
    assertEquals(List.of(holder), loop.container.live());
    assertEquals(
        Arrays.asList(null, holder),
        told.stream().map(Told::waitingOn).toList(),
        "main is told of the stage rows, and then of the wait once it is recorded");
  }

  @Test
  void launchReviewerThatStartedNothingSaysTheLaunchFailedAndLeavesTheReviewToTheNextStep() {
    var review = reviewing();
    loop.refuseLaunches();

    var followUp = take(new LoopStep.LaunchReviewer(review, 0, "codex"));

    var failed = (LoopTrigger.ReviewerNotStarted) followUp.orElseThrow();
    assertEquals(review.id(), failed.reviewId());
    assertEquals(0, failed.stage());
    assertFalse(failed.why().isBlank());
    assertEquals("running", review().status());
    assertEquals(
        List.of("running", "pending"),
        statuses(review.id()),
        "its claim had landed before the launch failed: the step that follows closes the stage");
    assertEquals(List.of(), loop.container.live());
    assertEquals(1, told.size(), "main is told of the stage rows, and of nothing else");
  }

  @Test
  void aLaunchThatFailedSaysWhatRefusedItAsFarDownAsItIsSaid() {
    var review = reviewing();
    launching(
        real -> {
          throw new IllegalStateException("launch failed:", new IllegalStateException("no codex"));
        });

    var wrapped = take(new LoopStep.LaunchReviewer(review, 0, "codex"));

    assertEquals(
        Optional.of(new LoopTrigger.ReviewerNotStarted(review.id(), 0, "launch failed: no codex")),
        wrapped);

    launching(
        real -> {
          throw new IllegalStateException("launch failed", new IllegalStateException(" "));
        });

    assertEquals(
        Optional.of(new LoopTrigger.ReviewerNotStarted(review.id(), 0, "launch failed")),
        take(new LoopStep.LaunchReviewer(review, 0, "codex")),
        "a cause that says nothing adds nothing");
  }

  @Test
  void awaitAPersonOpensTheirStageAnnouncesItAndTellsTheRoom() {
    var review = reviewing();

    assertEquals(Optional.empty(), take(new LoopStep.AwaitAPerson(review, 1)));

    assertEquals(List.of("pending", "running"), statuses(review.id()));
    assertEquals("human", stages().get(1).reviewer());
    assertEquals(List.of("approve"), loop.details("review_stage_started"));
    assertEquals(1, loop.roomLines(SPEC, "Automated review stages passed.").size());
    assertEquals(
        List.of(
            told(0, SpecStatus.IN_PROGRESS, "running", "pending", "pending"),
            told(1, SpecStatus.IN_PROGRESS, "running", "pending", "running"),
            told(1, SpecStatus.IN_PROGRESS, "running", "pending", "running")),
        told,
        "main is told of the stage rows, then of the room line that follows the event, then last");
  }

  @Test
  void passEndsTheReviewParksItsSpecAndSaysTheVerdictInOneWrite() {
    var review = reviewing();

    assertEquals(Optional.empty(), take(new LoopStep.Pass(review)));

    assertEquals("passed", review().status());
    assertEquals(SpecStatus.AWAITING_MERGE, loop.specStatus(SPEC));
    assertEquals(1, loop.roomLines(SPEC, "Review passed").size());
    assertEquals(1, loop.events("review_completed").size());
    assertEquals(
        List.of(told(0, SpecStatus.AWAITING_MERGE, "passed")),
        told,
        "main is told once the end is written, before it is announced");
  }

  @Test
  void aStepNeverMovesASpecSomeoneElseMovedAndTheReviewStillEnds() {
    var review = reviewing();
    Acting.system(() -> loop.specs.updateStatus(SPEC, SpecStatus.CANCELLED));

    take(new LoopStep.Pass(review));

    assertEquals("passed", review().status());
    assertEquals(SpecStatus.CANCELLED, loop.specStatus(SPEC));
  }

  @Test
  void readVerdictPassesAStageWhoseReviewerFoundNothingBlocking() {
    var reviewer = reviewed(CLEAN_REVIEW);

    var followUp = take(new LoopStep.ReadVerdict(review(), 0, reviewer, Optional.empty()));

    assertEquals(
        Optional.of(
            new LoopTrigger.StageJudged(review().id(), 0, new StageVerdicts.StageOutcome.Passed())),
        followUp);
    assertEquals("passed", stages().getFirst().status());
    assertEquals("running", review().status(), "the review's end is the next step's");
    assertEquals(List.of("codex"), loop.details("review_stage_passed"));
    assertEquals(
        List.of(told(1, SpecStatus.IN_PROGRESS, "running", "passed", "pending")),
        told,
        "main is told once the stage is closed and said to have passed");
  }

  @Test
  void readVerdictFailsAStageWhoseFindingsTripItsGateAndRecordsThem() {
    var reviewer = reviewed(CRITICAL_FINDING);

    var followUp = take(new LoopStep.ReadVerdict(review(), 0, reviewer, Optional.empty()));

    assertEquals(
        Optional.of(
            new LoopTrigger.StageJudged(
                review().id(), 0, new StageVerdicts.StageOutcome.GateFailed())),
        followUp);
    assertEquals("failed", stages().getFirst().status());
    assertNull(stages().getFirst().error(), "a verdict, not an error");
    assertEquals(1, loop.reviews.openFindingsForReview(review().id()).size());
    assertEquals(List.of("codex"), loop.details("review_stage_failed"));
  }

  @Test
  void readVerdictClosesTheStageOfAReviewerThatDidNotEndWellForHowItEnded() {
    var reviewer = reviewed(CLEAN_REVIEW);
    var error = "reviewer killed: time limit (45m)";

    var followUp = take(new LoopStep.ReadVerdict(review(), 0, reviewer, Optional.of(error)));

    assertEquals(
        Optional.of(
            new LoopTrigger.StageJudged(
                review().id(), 0, new StageVerdicts.StageOutcome.Errored(error))),
        followUp);
    assertEquals("failed", stages().getFirst().status());
    assertEquals(error, stages().getFirst().error(), "its log is never read for a verdict");
    assertEquals(List.of(), loop.details("review_stage_passed"));
    assertEquals(List.of(told(0, SpecStatus.IN_PROGRESS, "running", "failed", "pending")), told);
  }

  @Test
  void readVerdictErrsAStageWhoseReviewersLogCannotBeRead() {
    var reviewer = reviewed(CLEAN_REVIEW);
    var real = lanes.get();
    lanes.set(
        new ReviewLanes() {
          @Override
          public Launch launch(Invocation invocation, String boxHandle, Runnable claimed) {
            return real.launch(invocation, boxHandle, claimed);
          }

          @Override
          public String output(RunStore.RunRow run) throws Exception {
            throw new IllegalStateException("log unreadable");
          }

          @Override
          public List<Rescue> ensureCommitted(
              String project, List<String> repos, String branch, String commitMessage) {
            return List.of();
          }
        });

    var followUp = take(new LoopStep.ReadVerdict(review(), 0, reviewer, Optional.empty()));

    var outcome = new StageVerdicts.StageOutcome.Errored("log unreadable");
    assertEquals(Optional.of(new LoopTrigger.StageJudged(review().id(), 0, outcome)), followUp);
    assertEquals("log unreadable", stages().getFirst().error());
  }

  @Test
  void errorReviewClosesAStageThatCouldNotStartAndFailsItsReviewByError() {
    var review = reviewing();

    assertEquals(Optional.empty(), take(new LoopStep.ErrorReview(review, 0, "no reviewer", false)));

    assertEquals("failed", stages().getFirst().status());
    assertEquals("no reviewer", stages().getFirst().error());
    assertEquals("failed", review().status());
    assertEquals("no reviewer", review().error(), "an error, so its iteration runs again");
    assertEquals(List.of("no reviewer"), loop.details("review_errored"));
    assertEquals(
        List.of(
            told(0, SpecStatus.IN_PROGRESS, "running", "pending", "pending"),
            told(0, SpecStatus.IN_PROGRESS, "failed", "failed", "pending")),
        told,
        "main is told of the stage rows, then of the failed review before it is announced");
  }

  @Test
  void errorReviewLeavesAStageItsVerdictAlreadyClosedAsThatVerdictLeftIt() {
    var reviewer = reviewed(CLEAN_REVIEW);
    take(new LoopStep.ReadVerdict(review(), 0, reviewer, Optional.of("reviewer failed: exit 1")));
    var closedAt = stages().getFirst().completedAt();

    take(new LoopStep.ErrorReview(review(), 0, "reviewer failed: exit 1", true));

    assertEquals(closedAt, stages().getFirst().completedAt());
    assertEquals("reviewer failed: exit 1", review().error());
    assertEquals(List.of("reviewer failed: exit 1"), loop.details("review_errored"));
    assertEquals(List.of(told(0, SpecStatus.IN_PROGRESS, "failed", "failed", "pending")), told);
  }

  @Test
  void errorReviewOfAStageItsVerdictClosedWritesNoStageRowThePipelineHasSinceGained() {
    var review = reviewing();
    Acting.system(
        () -> {
          var stage = loop.reviews.createStage(review.id(), "codex", "agent");
          loop.reviews.completeStage(stage, "failed", "reviewer failed: exit 1");
        });

    take(new LoopStep.ErrorReview(review, 0, "reviewer failed: exit 1", true));

    assertEquals(List.of("failed"), statuses(review.id()), "a review that ended gains no stage");
    assertEquals(List.of(told(0, SpecStatus.IN_PROGRESS, "failed", "failed")), told);
  }

  @Test
  void failGateFailsTheReviewByVerdictAndTellsTheRoomWhatItFound() {
    var reviewer = reviewed(CRITICAL_FINDING);
    take(new LoopStep.ReadVerdict(review(), 0, reviewer, Optional.empty()));

    assertEquals(Optional.of(new LoopTrigger.GoOn()), take(new LoopStep.FailGate(review())));

    assertEquals("failed", review().status());
    assertNull(review().error(), "a failed gate is a verdict: its findings go to a fix agent");
    assertEquals(1, loop.roomLines(SPEC, "Review failed").size());
    assertEquals(SpecStatus.IN_PROGRESS, loop.specStatus(SPEC), "the spec moves with the fix");
    assertEquals(
        List.of(told(0, SpecStatus.IN_PROGRESS, "failed", "failed", "pending")),
        told,
        "main is told of the verdict in the room");
  }

  /** A review that failed its gate on one critical finding, its reviewer's stop unheard. */
  private List<Finding> gateFailed() {
    var reviewer = reviewed(CRITICAL_FINDING);
    take(new LoopStep.ReadVerdict(review(), 0, reviewer, Optional.empty()));
    take(new LoopStep.FailGate(review()));
    return loop.reviews.openFindingsForReview(review().id());
  }

  @Test
  void launchFixHandsTheFindingsToAFixRunAndMovesTheSpecBackToInProgress() {
    var findings = gateFailed();
    Acting.system(() -> loop.specs.updateStatus(SPEC, SpecStatus.REVIEW));

    assertEquals(Optional.empty(), take(new LoopStep.LaunchFix(review(), findings)));

    var fix = loop.onlyLive();
    assertEquals("fix", fix.role());
    assertEquals(review().id(), fix.reviewId());
    assertTrue(fix.task().contains("Bad"), "the fix task names what to fix");
    assertEquals(SpecStatus.IN_PROGRESS, loop.specStatus(SPEC));
    assertEquals(1, loop.events("review_iteration_started").size());
    assertEquals(
        List.of(SpecStatus.REVIEW, SpecStatus.IN_PROGRESS),
        told.stream().map(Told::spec).toList(),
        "main is told the fix run serves the review, and then of the spec's move");
  }

  @Test
  void launchFixThatServesAReviewThatWaitedTellsMainOnceTheWaitIsCleared() {
    var findings = gateFailed();
    loop.db.execute("UPDATE reviews SET waiting_on = 'holder-1' WHERE id = ?", review().id());

    take(new LoopStep.LaunchFix(review(), findings));

    assertNull(review().waitingOn());
    assertEquals(
        Arrays.asList(null, null), told.stream().map(Told::waitingOn).toList(), told.toString());
  }

  @Test
  void launchFixWhoseLaunchFailsAfterItsAgentStartedWaitsForThatAgentsStop() {
    var findings = gateFailed();
    Acting.system(() -> loop.specs.updateStatus(SPEC, SpecStatus.REVIEW));
    launching(
        real -> {
          real.get();
          throw new IllegalStateException("status read failed");
        });

    assertEquals(Optional.empty(), take(new LoopStep.LaunchFix(review(), findings)));

    assertEquals("fix", loop.onlyLive().role());
    assertEquals(SpecStatus.IN_PROGRESS, loop.specStatus(SPEC));
    assertEquals(1, loop.events("review_iteration_started").size());
    assertEquals("failed", review().status(), "it still waits for its fix agent's stop");
  }

  @Test
  void launchFixRefusedItsClaimRecordsTheWaitAndLeavesTheSpecWhereItWas() {
    var findings = gateFailed();
    Acting.system(() -> loop.specs.updateStatus(SPEC, SpecStatus.REVIEW));
    var holder = holder();

    assertEquals(Optional.empty(), take(new LoopStep.LaunchFix(review(), findings)));

    assertEquals(holder, review().waitingOn());
    assertEquals(SpecStatus.REVIEW, loop.specStatus(SPEC));
    assertEquals(List.of(), loop.events("review_iteration_started"));
    assertEquals(
        List.of(holder),
        told.stream().map(Told::waitingOn).toList(),
        "main is told of the wait once it is recorded");
  }

  @Test
  void launchFixThatStartedNothingSaysTheLaunchFailed() {
    var findings = gateFailed();
    loop.refuseLaunches();

    var followUp = take(new LoopStep.LaunchFix(review(), findings));

    var failed = (LoopTrigger.FixNotStarted) followUp.orElseThrow();
    assertEquals(review().id(), failed.reviewId());
    assertFalse(failed.why().isBlank());
    assertEquals("failed", review().status(), "what a failed launch comes to is the next step's");
    assertEquals(List.of(), told, "nothing was written");
  }

  /** The real lanes, with {@code before} run each time a reviewer's log is about to be read. */
  private void beforeALogIsRead(Runnable before) {
    var real = lanes.get();
    lanes.set(
        new ReviewLanes() {
          @Override
          public Launch launch(Invocation invocation, String boxHandle, Runnable claimed) {
            return real.launch(invocation, boxHandle, claimed);
          }

          @Override
          public String output(RunStore.RunRow run) throws Exception {
            before.run();
            return real.output(run);
          }

          @Override
          public List<Rescue> ensureCommitted(
              String project, List<String> repos, String branch, String commitMessage)
              throws Exception {
            return real.ensureCommitted(project, repos, branch, commitMessage);
          }
        });
  }

  /** The loop's own drive of {@code reviewer}'s clean stop: decide, act, and follow up. */
  private void stopOf(RunStore.RunRow reviewer) {
    Acting.system(
        () ->
            ReviewPipelineController.drive(
                SPEC,
                () -> reader.read(PROJECT, SPEC, LoopRows.staged(AN_AGENT_THEN_A_PERSON)),
                new LoopTrigger.ReviewerEnded(reviewer, Optional.empty()),
                steps::take));
    loop.settle();
  }

  @Test
  void aGateThatFailsForASpecSomeoneTookWhileItsVerdictWasReadStartsNoFixOverTheirDecision() {
    var reviewer = reviewed(CRITICAL_FINDING);
    beforeALogIsRead(
        () -> Acting.system(() -> loop.specs.updateStatus(SPEC, SpecStatus.CANCELLED)));

    stopOf(reviewer);

    assertEquals("failed", review().status(), "the verdict stands");
    assertEquals(1, loop.roomLines(SPEC, "Review failed").size(), "and the room is told of it");
    assertEquals(List.of(), loop.container.live(), "but no fix agent is launched");
    assertEquals(List.of(), loop.details("review_escalated"));
    assertEquals(SpecStatus.CANCELLED, loop.specStatus(SPEC));
  }

  @Test
  void aStageThatPassesForASpecSomeoneTookWhileItsVerdictWasReadOpensNoStageAfterIt() {
    var reviewer = reviewed(CLEAN_REVIEW);
    beforeALogIsRead(
        () -> Acting.system(() -> loop.specs.updateStatus(SPEC, SpecStatus.CANCELLED)));

    stopOf(reviewer);

    assertEquals(List.of("passed", "pending"), statuses(review().id()));
    assertEquals(List.of("codex"), loop.details("review_stage_started"), "no person is asked");
    assertEquals("running", review().status());
  }

  /** A fix agent that worked the review's findings and ended, its stop unheard. */
  private RunStore.RunRow fixedBy() {
    var findings = gateFailed();
    take(new LoopStep.LaunchFix(review(), findings));
    var fix = loop.onlyLive();
    loop.container.exited(fix.id(), "fixed", 0);
    Acting.system(() -> loop.runs.complete(fix.id(), "stopped", 0));
    return loop.runs.findById(fix.id()).orElseThrow();
  }

  @Test
  void commitFixLeftoversCommitsWhatTheFixAgentLeftAndSaysSo() {
    var fix = fixedBy();
    loop.container.dirty("api", " M src/Fixed.java\n");

    var followUp = take(new LoopStep.CommitFixLeftovers(review(), fix));

    assertEquals(Optional.of(new LoopTrigger.FixCommitted(fix)), followUp);
    assertEquals(1, loop.container.commits().size());
    var guardrail = loop.events(Event.WellKnownTypes.GUARDRAIL_TRIGGERED).getFirst();
    assertTrue(guardrail.data().get("reason").toString().contains("src/Fixed.java"));
  }

  @Test
  void commitFixLeftoversOfACleanTreeCommitsNothingAndSaysNothing() {
    var fix = fixedBy();

    var followUp = take(new LoopStep.CommitFixLeftovers(review(), fix));

    assertEquals(Optional.of(new LoopTrigger.FixCommitted(fix)), followUp);
    assertEquals(List.of(), loop.container.commits());
    assertEquals(List.of(), loop.events(Event.WellKnownTypes.GUARDRAIL_TRIGGERED));
  }

  @Test
  void commitFixLeftoversThatCannotCommitSaysWhyAndLeavesTheReviewToTheNextStep() {
    var fix = fixedBy();
    loop.container.dirty("api", " M src/Fixed.java\n");
    loop.container.gitFails("fatal: index.lock exists", "status");

    var followUp = take(new LoopStep.CommitFixLeftovers(review(), fix));

    var failed = (LoopTrigger.FixNotCommitted) followUp.orElseThrow();
    assertEquals(fix, failed.run());
    assertTrue(failed.why().contains("index.lock"), failed.why());
    assertEquals("failed", review().status());
  }

  @Test
  void failFixSaysTheIterationFailedAndHandsTheReviewToAPerson() {
    gateFailed();

    assertEquals(
        Optional.empty(), take(new LoopStep.FailFix(review(), "fix agent failed: exit 1")));

    assertEquals(List.of("fix agent failed: exit 1"), loop.details("review_iteration_failed"));
    assertEquals("escalated", review().status());
    assertEquals(ReviewNarration.fixFailed("fix agent failed: exit 1"), review().error());
    assertEquals(SpecStatus.REVIEW, loop.specStatus(SPEC));
    assertEquals(
        List.of(told(0, SpecStatus.REVIEW, "escalated", "failed", "pending")),
        told,
        "main is told once the end is written, before anything is said of it");
    var said =
        loop.published.stream()
            .map(Event::type)
            .filter(type -> type.startsWith("review_"))
            .toList();
    assertEquals(
        List.of("review_iteration_failed", "review_escalated"),
        said.subList(said.size() - 2, said.size()),
        "the iteration is said to have failed, and then its review to be a person's");
  }

  @Test
  void escalateEndsTheReviewClosesItsRunningStageAndMovesItsSpecInOneWrite() {
    take(new LoopStep.LaunchReviewer(reviewing(), 0, "codex"));

    assertEquals(Optional.empty(), take(new LoopStep.Escalate(review(), "stopped by an operator")));

    assertEquals("escalated", review().status());
    assertEquals("stopped by an operator", review().error());
    assertEquals("failed", stages().getFirst().status(), "no run and no person owns it any more");
    assertEquals(SpecStatus.REVIEW, loop.specStatus(SPEC));
    assertEquals(List.of("stopped by an operator"), loop.details("review_escalated"));
    assertEquals(
        List.of("Review escalated: stopped by an operator."),
        loop.roomLines(SPEC, "Review escalated"));
    assertEquals(
        List.of(told(0, SpecStatus.REVIEW, "escalated", "failed", "pending")),
        told,
        "main is told once the end is written, before it is announced");
  }
}
