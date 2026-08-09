/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The review aggregate reconciles up to main and on to every other box through the same {@link
 * SyncEngine} as runs, so main can narrate the review loop from replicated state: the review row,
 * its stages, and each stage's finding counts land on main and every reader box, while the finding
 * ROWS stay on the executing node. Single-writer: only the executing node mutates its own reviews.
 */
class ReviewSyncTest {

  @TempDir Path tempDir;
  private final SyncEngine engine = new SyncEngine();

  private Box main;
  private Box node;
  private Box other;

  private final class Box implements AutoCloseable {
    final Sqlite db;
    final ReviewStore reviews;
    final SyncConflicts conflicts;
    final ReviewReplica replica;

    Box(String id) {
      this.db = Sqlite.open(tempDir.resolve(id + ".db"));
      new SchemaManager(db).migrate();
      this.reviews = new ReviewStore(db);
      this.conflicts = new SyncConflicts(db);
      this.replica =
          new ReviewReplica(id, reviews, new ChangeLog(db), conflicts, new SyncState(db));
    }

    @Override
    public void close() {
      db.close();
    }
  }

  @BeforeEach
  void setUp() {
    main = new Box("main");
    node = new Box("node");
    other = new Box("other");
  }

  @AfterEach
  void tearDown() {
    other.close();
    node.close();
    main.close();
  }

  private void sync(Box box) {
    engine.reconcile(box.replica, main.replica);
  }

  private static Finding finding(Finding.Severity severity) {
    return Finding.create(
        severity,
        Finding.Category.SECURITY,
        "src/Auth.java",
        1,
        2,
        "issue",
        "desc",
        "evidence",
        new Finding.Suggestion("a", "b", "c"),
        0.9);
  }

  @Test
  void aReviewAndItsStageCountsReplicateToMainAndOtherBoxes() {
    var reviewId = node.reviews.createReview("auth", 1);
    var stageId = node.reviews.createStage(reviewId, "security", "agent");
    node.reviews.startStage(stageId, "codex");
    node.reviews.addFinding(stageId, finding(Finding.Severity.HIGH));
    node.reviews.addFinding(stageId, finding(Finding.Severity.HIGH));
    node.reviews.completeStage(stageId, "failed");
    node.reviews.updateReviewStatus(reviewId, "failed");

    sync(node);

    var onMain = main.reviews.findReview(reviewId).orElseThrow();
    assertEquals("failed", onMain.status());
    var mainStage = main.reviews.stagesForReview(reviewId).getFirst();
    assertEquals("failed", mainStage.status());
    assertEquals(2, main.reviews.findingCountsForStage(mainStage.id()).get("HIGH"));
    assertTrue(
        main.reviews.findingsForStage(mainStage.id()).isEmpty(),
        "finding rows stay on the executing node; only counts replicate");

    sync(other);
    var otherStage = other.reviews.stagesForReview(reviewId).getFirst();
    assertEquals(2, other.reviews.findingCountsForStage(otherStage.id()).get("HIGH"));
  }

  @Test
  void aSuccessfulPushLeavesTheExecutingNodesFindingRowsIntact() {
    var reviewId = node.reviews.createReview("auth", 1);
    var stageId = node.reviews.createStage(reviewId, "security", "agent");
    node.reviews.startStage(stageId, "codex");
    node.reviews.addFinding(stageId, finding(Finding.Severity.HIGH));
    node.reviews.completeStage(stageId, "failed");
    node.reviews.updateReviewStatus(reviewId, "failed");

    sync(node);

    assertEquals(
        1,
        node.reviews.findingsForStage(stageId).size(),
        "adopting main's identical aggregate after a push must link the revision without"
            + " rebuilding — the rebuild deletes the finding rows that carry-forward and"
            + " dispute resolution read");
  }

  @Test
  void aLaterTransitionOnTheOwningNodePropagates() {
    var reviewId = node.reviews.createReview("auth", 1);
    sync(node);
    sync(other);

    node.reviews.updateReviewStatus(reviewId, "passed");
    sync(node);
    assertEquals("passed", main.reviews.findReview(reviewId).orElseThrow().status());

    sync(other);
    assertEquals("passed", other.reviews.findReview(reviewId).orElseThrow().status());
  }

  @Test
  void aReaderBoxNeverPushesAForeignReviewAndStaysConflictFree() {
    var reviewId = node.reviews.createReview("auth", 1);
    sync(node);
    sync(other);

    var mainRevAfterPull = main.reviews.latestRev(reviewId);
    var report = engine.reconcile(other.replica, main.replica);

    assertEquals(0, report.pushed(), "a reader box pushes nothing for a foreign review");
    assertEquals(0, report.conflicts());
    assertEquals(mainRevAfterPull, main.reviews.latestRev(reviewId));
    assertTrue(other.conflicts.pending().isEmpty());
  }
}
