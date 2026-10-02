/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static ai.singlr.sail.sync.SyncFixtures.finding;
import static ai.singlr.sail.sync.SyncFixtures.ownSpec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ReviewStore;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The review aggregate reconciles up to main and on to every other box through the same sessions as
 * runs, so main can narrate the review loop and every box reads the same findings: the review row,
 * its stages, and its findings as content land on main and every reader box. Single-writer: only
 * the executing node mutates its own reviews, and its writes landing while an offer is in flight
 * are never lost.
 */
@ActingAs
class ReviewSyncTest {

  @TempDir Path tempDir;

  private SyncBox main;
  private SyncBox node;
  private SyncBox other;
  private ReviewStore reviews;

  @BeforeEach
  void setUp() {
    main = new SyncBox(tempDir, "main");
    node = new SyncBox(tempDir, "node");
    other = new SyncBox(tempDir, "other");
    ownSpec(main, "node", "auth", "node");
    sync(node);
    sync(other);
    reviews = new ReviewStore(node.db);
  }

  @AfterEach
  void tearDown() {
    other.close();
    node.close();
    main.close();
  }

  private List<SyncSession.TypeReport> sync(SyncBox box) {
    return SyncBox.round(main, box);
  }

  private static SyncSession.TypeReport reviewReport(List<SyncSession.TypeReport> reports) {
    return reports.stream().filter(report -> report.type().equals("review")).findFirst().get();
  }

  private String failedReviewWithFindings(int findings) {
    return Acting.as(
        "node",
        () -> {
          var reviewId = reviews.createReview("auth", 1);
          var stageId = reviews.createStage(reviewId, "security", "agent");
          reviews.startStage(stageId, "codex");
          for (var i = 0; i < findings; i++) {
            reviews.addFinding(stageId, finding("issue " + i));
          }
          reviews.completeStage(stageId, "failed");
          reviews.updateReviewStatus(reviewId, "failed");
          return reviewId;
        });
  }

  private String stageOf(String reviewId) {
    return reviews.stagesForReview(reviewId).getFirst().id();
  }

  @Test
  void aReviewAndItsFindingsReplicateToMainAndOtherBoxes() {
    var reviewId = failedReviewWithFindings(2);

    sync(node);

    var mains = new ReviewStore(main.db);
    var onMain = mains.findReview(reviewId).orElseThrow();
    assertEquals("failed", onMain.status());
    var mainStage = mains.stagesForReview(reviewId).getFirst();
    assertEquals("failed", mainStage.status());
    assertEquals(2, mains.findingCountsForStage(mainStage.id()).get("HIGH"));
    assertEquals(
        reviews.findingsForReview(reviewId),
        mains.findingsForReview(reviewId),
        "the findings are the review's content and land with it");

    sync(other);
    var others = new ReviewStore(other.db);
    assertEquals(2, others.findingCountsForStage(stageOf(reviewId)).get("HIGH"));
    assertEquals(reviews.findingsForReview(reviewId), others.findingsForReview(reviewId));

    SyncBox.assertConverged(main, node, other);
  }

  @Test
  void aSuccessfulPushLeavesTheExecutingNodesFindingRowsIntact() {
    var reviewId = failedReviewWithFindings(1);

    sync(node);

    assertEquals(
        1,
        reviews.findingsForStage(stageOf(reviewId)).size(),
        "adopting main's identical aggregate after a push links the revision and rewrites nothing");

    SyncBox.assertConverged(main, node, other);
  }

  @Test
  void aFindingAddedWhileThePushIsInFlightSurvivesTheAcceptedSnapshot() {
    var reviewId = failedReviewWithFindings(1);
    var stageId = stageOf(reviewId);
    var landed = new AtomicBoolean();
    var landing =
        main.serverInterceptingCommitsOf(
            node.session(),
            "review",
            reviewId,
            () -> {
              if (landed.compareAndSet(false, true)) {
                Acting.as("node", () -> reviews.addFinding(stageId, finding("racing")));
              }
            });

    SyncBox.round(landing, node);

    assertEquals(
        2,
        reviews.findingsForStage(stageId).size(),
        "a write landing while the offer is in flight is kept on top of the accepted version");

    SyncBox.assertConverged(main, node, other);
    assertEquals(2, new ReviewStore(main.db).findingsForStage(stageId).size());
  }

  @Test
  void syncRoundsRacingAConcurrentLocalWriterNeverLoseFindingRows() throws InterruptedException {
    var reviewId = failedReviewWithFindings(1);
    var stageId = stageOf(reviewId);

    var rounds = 25;
    for (var round = 0; round < rounds; round++) {
      var writer =
          new Thread(
              Actor.carrying(
                  () -> Acting.as("node", () -> reviews.addFinding(stageId, finding("racing")))));
      writer.start();
      sync(node);
      writer.join();
    }
    sync(node);

    assertEquals(
        1 + rounds,
        reviews.findingsForStage(stageId).size(),
        "snapshot capture and adoption are atomic against concurrent local writes");

    SyncBox.assertConverged(main, node, other);
    assertEquals(1 + rounds, new ReviewStore(main.db).findingsForStage(stageId).size());
  }

  @Test
  void aLaterTransitionOnTheOwningNodePropagates() {
    var reviewId = Acting.as("node", () -> reviews.createReview("auth", 1));
    sync(node);
    sync(other);

    Acting.as("node", () -> reviews.updateReviewStatus(reviewId, "passed"));
    sync(node);
    assertEquals("passed", new ReviewStore(main.db).findReview(reviewId).orElseThrow().status());

    sync(other);
    assertEquals("passed", new ReviewStore(other.db).findReview(reviewId).orElseThrow().status());

    SyncBox.assertConverged(main, node, other);
  }

  @Test
  void aReaderBoxNeverPushesAForeignReviewAndStaysConflictFree() {
    var reviewId = failedReviewWithFindings(1);
    sync(node);
    sync(other);

    var mainRevAfterPull = new ReviewStore(main.db).latestRev(reviewId);
    var report = reviewReport(sync(other));

    assertEquals(0, report.report().pushed(), "a reader box pushes nothing for a foreign review");
    assertEquals(0, report.report().conflicts());
    assertEquals(mainRevAfterPull, new ReviewStore(main.db).latestRev(reviewId));
    assertTrue(other.conflicts.pending().isEmpty());

    SyncBox.assertConverged(main, node, other);
  }
}
