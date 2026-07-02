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
  private SpecStore specStore;
  private ReviewOperations ops;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    reviewStore = new ReviewStore(db);
    specStore = new SpecStore(db);
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
            List.of("api", "web")));
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

  private String seedPassedReviewWithOpenFindings() {
    var reviewId = reviewStore.createReview("auth", 1);
    var stageId = reviewStore.createStage(reviewId, "security", "agent");
    reviewStore.addFinding(
        stageId,
        Finding.create(
            Finding.Severity.MEDIUM,
            Finding.Category.EDGE_CASE,
            "Flow.java",
            5,
            5,
            "Unchecked null",
            "Null flows through.",
            "",
            null,
            0.6));
    reviewStore.addFinding(
        stageId,
        Finding.create(
            Finding.Severity.HIGH,
            Finding.Category.SECURITY,
            "Auth.java",
            10,
            12,
            "Token leak",
            "Token is logged.",
            "log.info(token)",
            new Finding.Suggestion("log.info(token)", "log.info(mask(token))", "Never log secrets"),
            0.9));
    reviewStore.updateReviewStatus(reviewId, "passed");
    return reviewId;
  }

  @Test
  void createFollowupDraftsSpecFromOpenFindings() {
    var reviewId = seedPassedReviewWithOpenFindings();

    var response = ops.createFollowup("auth", new FollowupCreateRequest(null, "uday"));

    assertEquals("auth-followup", response.spec().id());
    assertEquals("Address review findings: Auth", response.spec().title());
    assertEquals("draft", response.spec().status());
    assertEquals("manatee", response.spec().project());
    assertEquals(List.of("api", "web"), response.spec().repos());
    assertEquals(3, response.spec().priority());
    assertEquals("uday", response.spec().createdBy());
    assertEquals("auth", response.sourceSpecId());
    assertEquals(reviewId, response.reviewId());
    assertEquals(2, response.findingCount());
    assertEquals(2, reviewStore.sourceFindingIds("auth-followup").size());

    var body = specStore.getContent("auth-followup").orElseThrow().body();
    assertTrue(body.indexOf("Token leak") < body.indexOf("Unchecked null"));
    assertTrue(body.contains("HIGH"));
    assertTrue(body.contains("SECURITY"));
    assertTrue(body.contains("`Auth.java:10-12`"));
    assertTrue(body.contains("Token is logged."));
    assertTrue(body.contains("log.info(mask(token))"));
    assertTrue(body.contains("Never log secrets"));
  }

  @Test
  void createFollowupRejectsAnInvalidExplicitId() {
    seedPassedReviewWithOpenFindings();

    var ex =
        assertThrows(
            ApiException.class,
            () -> ops.createFollowup("auth", new FollowupCreateRequest("../bad id!", "uday")));
    assertEquals(ErrorCode.INVALID_REQUEST, ex.failure().errorCode());
  }

  @Test
  void createFollowupHonorsExplicitId() {
    seedPassedReviewWithOpenFindings();

    var response = ops.createFollowup("auth", new FollowupCreateRequest("auth-round2", "uday"));

    assertEquals("auth-round2", response.spec().id());
  }

  @Test
  void createFollowupMissingSpecThrowsSpecNotFound() {
    var ex =
        assertThrows(
            ApiException.class,
            () -> ops.createFollowup("nope", new FollowupCreateRequest(null, "uday")));
    assertEquals(ErrorCode.SPEC_NOT_FOUND, ex.failure().errorCode());
  }

  @Test
  void createFollowupWithoutReviewsExplainsItself() {
    var ex =
        assertThrows(
            ApiException.class,
            () -> ops.createFollowup("auth", new FollowupCreateRequest(null, "uday")));
    assertEquals(ErrorCode.NOT_FOUND, ex.failure().errorCode());
    assertTrue(ex.failure().errorMessage().contains("no reviews"));
  }

  @Test
  void createFollowupWithOnlySupersededReviewsExplainsItself() {
    seedPassedReviewWithOpenFindings();
    reviewStore.supersedeForSpec("auth");

    var ex =
        assertThrows(
            ApiException.class,
            () -> ops.createFollowup("auth", new FollowupCreateRequest(null, "uday")));
    assertEquals(ErrorCode.CONFLICT, ex.failure().errorCode());
    assertTrue(ex.failure().errorMessage().contains("superseded"));
  }

  @Test
  void createFollowupWithoutOpenFindingsExplainsItself() {
    var reviewId = seedPassedReviewWithOpenFindings();
    for (var finding : reviewStore.findingsForReview(reviewId)) {
      reviewStore.resolveFinding(finding.id(), Finding.Resolution.DISMISSED);
    }

    var ex =
        assertThrows(
            ApiException.class,
            () -> ops.createFollowup("auth", new FollowupCreateRequest(null, "uday")));
    assertEquals(ErrorCode.INVALID_REQUEST, ex.failure().errorCode());
    assertTrue(ex.failure().errorMessage().contains("no open findings"));
  }

  @Test
  void createFollowupWithExistingIdThrowsConflict() {
    seedPassedReviewWithOpenFindings();
    ops.createFollowup("auth", new FollowupCreateRequest(null, "uday"));

    var ex =
        assertThrows(
            ApiException.class,
            () -> ops.createFollowup("auth", new FollowupCreateRequest(null, "uday")));
    assertEquals(ErrorCode.CONFLICT, ex.failure().errorCode());
    assertTrue(ex.failure().errorMessage().contains("auth-followup"));
  }

  @Test
  void approveResolvesSourceFindingsOfTheApprovedSpec() {
    seedPassedReviewWithOpenFindings();
    ops.createFollowup("auth", new FollowupCreateRequest(null, "uday"));
    var followupReview = reviewStore.createReview("auth-followup", 1);
    var humanStage = reviewStore.createStage(followupReview, "human", "human");
    reviewStore.startStage(humanStage, "uday");

    ops.approve(followupReview, "uday");

    var resolutions =
        reviewStore.findingsForReview(reviewStore.reviewsForSpec("auth").getFirst().id()).stream()
            .map(Finding::resolution)
            .toList();
    assertEquals(List.of(Finding.Resolution.FIXED, Finding.Resolution.FIXED), resolutions);
  }

  @Test
  void operationsWithoutStoreThrowInternal() {
    var noStore = new ReviewOperations(null, new SpecStore(db));
    var ex = assertThrows(ApiException.class, () -> noStore.listForSpec("auth"));
    assertEquals(ErrorCode.INTERNAL, ex.failure().errorCode());
  }
}
