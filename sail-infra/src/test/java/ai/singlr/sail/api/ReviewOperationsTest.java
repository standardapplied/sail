/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewOperationsTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private ReviewStore reviewStore;
  private ReviewOperations ops;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    reviewStore = new ReviewStore(db);
    var specStore = new SpecStore(db);
    specStore.create(
        new SpecStore.SpecRow(
            "auth",
            "manatee",
            "Auth",
            SpecStatus.IN_PROGRESS,
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            "",
            "",
            null,
            List.of(),
            List.of()));
    ops = new ReviewOperations(reviewStore, specStore);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private String seedReviewWithFinding() {
    var reviewId = reviewStore.createReview("auth", 1);
    var stageId = reviewStore.createStage(reviewId, "security", "agent");
    reviewStore.addFinding(
        stageId,
        Finding.create(
            Finding.Severity.HIGH,
            Finding.Category.SECURITY,
            "Auth.java",
            10,
            12,
            "Issue",
            "Description",
            "Evidence",
            new Finding.Suggestion("bad", "good", "why"),
            0.8));
    return reviewId;
  }

  @Test
  void listForSpecReturnsReviewsWithStages() {
    seedReviewWithFinding();
    var response = ops.listForSpec("auth");
    assertEquals("auth", response.specId());
    assertEquals(1, response.reviews().size());
  }

  @Test
  void detailReturnsStagesAndFindings() {
    var reviewId = seedReviewWithFinding();
    var detail = ops.detail(reviewId);
    assertNotNull(detail.review());
    assertEquals(1, detail.findings().size());
  }

  @Test
  void detailMissingThrowsNotFound() {
    var ex = assertThrows(ApiException.class, () -> ops.detail("nope"));
    assertEquals(ErrorCode.NOT_FOUND, ex.failure().errorCode());
  }

  @Test
  void approveCompletesHumanStageAndMarksSpecDone() {
    var reviewId = reviewStore.createReview("auth", 1);
    var stageId = reviewStore.createStage(reviewId, "human", "human");
    reviewStore.startStage(stageId, "uday");

    var response = ops.approve(reviewId, "uday");

    assertTrue(response.approved());
    var review = reviewStore.findReview(reviewId).orElseThrow();
    assertEquals("passed", review.status());
    assertEquals("uday", review.decidedBy());
  }

  @Test
  void approveWithoutPendingHumanStageThrows() {
    var reviewId = reviewStore.createReview("auth", 1);
    reviewStore.createStage(reviewId, "security", "agent");

    var ex = assertThrows(ApiException.class, () -> ops.approve(reviewId, "uday"));
    assertEquals(ErrorCode.INVALID_REQUEST, ex.failure().errorCode());
  }

  @Test
  void approveMissingThrowsNotFound() {
    assertThrows(ApiException.class, () -> ops.approve("nope", "uday"));
  }

  @Test
  void dismissFindingResolvesIt() {
    var reviewId = seedReviewWithFinding();
    var findingId = reviewStore.findingsForReview(reviewId).getFirst().id();

    var response = ops.dismissFinding(reviewId, findingId);

    assertTrue(response.dismissed());
    assertEquals(
        Finding.Resolution.DISMISSED,
        reviewStore.findingsForReview(reviewId).getFirst().resolution());
  }

  @Test
  void dismissFindingMissingReviewThrowsNotFound() {
    assertThrows(ApiException.class, () -> ops.dismissFinding("nope", "f1"));
  }

  @Test
  void operationsWithoutStoreThrowInternal() {
    var noStore = new ReviewOperations(null, new SpecStore(db));
    var ex = assertThrows(ApiException.class, () -> noStore.listForSpec("auth"));
    assertEquals(ErrorCode.INTERNAL, ex.failure().errorCode());
  }
}
