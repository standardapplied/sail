/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlobalSpecOperationsTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SpecStore specStore;
  private ReviewStore reviewStore;
  private GlobalSpecOperations ops;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    specStore = new SpecStore(db);
    reviewStore = new ReviewStore(db);
    ops = new GlobalSpecOperations(specStore, reviewStore);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private SpecCreateRequest createReq(Map<String, Object> overrides) {
    var base =
        new java.util.HashMap<String, Object>(
            Map.of("id", "auth", "project", "manatee", "title", "Auth"));
    base.putAll(overrides);
    return SpecCreateRequest.fromMap(base);
  }

  @Test
  void createPersistsAuthenticatedAuthor() {
    ops.create(createReq(Map.of()).withCreatedBy("uday"));
    assertEquals("uday", ops.get("auth").spec().createdBy());
  }

  @Test
  void clientSuppliedCreatedByIsIgnored() {
    ops.create(createReq(Map.of("created_by", "attacker")));
    assertNull(ops.get("auth").spec().createdBy());
  }

  @Test
  void createSetsUpdatedByToCreator() {
    ops.create(createReq(Map.of()).withCreatedBy("uday"));
    assertEquals("uday", ops.get("auth").spec().updatedBy());
  }

  @Test
  void updatePersistsUpdatedByWithoutTouchingCreatedBy() {
    ops.create(createReq(Map.of()).withCreatedBy("uday"));
    ops.update("auth", SpecUpdateRequest.fromMap(Map.of("title", "Auth v2")).withUpdatedBy("nova"));
    var spec = ops.get("auth").spec();
    assertEquals("uday", spec.createdBy());
    assertEquals("nova", spec.updatedBy());
  }

  @Test
  void createThenGetRoundTrips() {
    var created = ops.create(createReq(Map.of("status", "pending", "body", "B", "plan", "P")));
    assertEquals("auth", created.spec().id());

    var detail = ops.get("auth");
    assertEquals("manatee", detail.spec().project());
    assertEquals("B", detail.body());
    assertEquals("P", detail.plan());
  }

  @Test
  void createRejectsMissingId() {
    var ex = assertThrows(ApiException.class, () -> ops.create(createReq(Map.of("id", ""))));
    assertEquals(ErrorCode.INVALID_REQUEST, ex.failure().errorCode());
  }

  @Test
  void createRejectsMissingTitle() {
    assertThrows(ApiException.class, () -> ops.create(createReq(Map.of("title", ""))));
  }

  @Test
  void createRejectsMissingProject() {
    assertThrows(ApiException.class, () -> ops.create(createReq(Map.of("project", ""))));
  }

  @Test
  void createRejectsInvalidStatus() {
    assertThrows(ApiException.class, () -> ops.create(createReq(Map.of("status", "bogus"))));
  }

  @Test
  void createRejectsInvalidModel() {
    var ex =
        assertThrows(
            ApiException.class, () -> ops.create(createReq(Map.of("model", "bad model!"))));
    assertEquals(ErrorCode.INVALID_REQUEST, ex.failure().errorCode());
  }

  @Test
  void createRejectsInvalidReasoningEffort() {
    assertThrows(
        ApiException.class, () -> ops.create(createReq(Map.of("reasoning_effort", "huge"))));
  }

  @Test
  void createAcceptsValidModelAndReasoning() {
    var created =
        ops.create(createReq(Map.of("model", "claude-opus-4", "reasoning_effort", "high")));
    assertEquals("auth", created.spec().id());
  }

  @Test
  void updateRejectsInvalidModel() {
    ops.create(createReq(Map.of()));
    assertThrows(
        ApiException.class,
        () -> ops.update("auth", SpecUpdateRequest.fromMap(Map.of("model", "bad model!"))));
  }

  @Test
  void updateAcceptsValidModel() {
    ops.create(createReq(Map.of()));
    var updated = ops.update("auth", SpecUpdateRequest.fromMap(Map.of("model", "claude-opus-4")));
    assertEquals("claude-opus-4", updated.spec().model());
  }

  @Test
  void listRejectsInvalidStatusFilter() {
    var ex =
        assertThrows(
            ApiException.class,
            () -> ops.list(new SpecStore.SpecFilter(null, "bogus", null, null, null)));
    assertEquals(ErrorCode.INVALID_REQUEST, ex.failure().errorCode());
  }

  @Test
  void getMissingThrowsNotFound() {
    var ex = assertThrows(ApiException.class, () -> ops.get("ghost"));
    assertEquals(ErrorCode.SPEC_NOT_FOUND, ex.failure().errorCode());
  }

  @Test
  void listReturnsCreatedSpecs() {
    ops.create(createReq(Map.of()));
    var list = ops.list(SpecStore.SpecFilter.all());
    assertEquals(1, list.total());
  }

  @Test
  void updateChangesFieldsAndDefaultsStatusToExisting() {
    ops.create(createReq(Map.of("status", "in_progress")));
    var updated =
        ops.update(
            "auth",
            SpecUpdateRequest.fromMap(Map.of("title", "New title", "reasoning_effort", "high")));
    assertEquals("New title", updated.spec().title());
    assertEquals("in_progress", updated.spec().status());
  }

  @Test
  void getSurvivesASpecWhoseContentRowIsMissing() {
    ops.create(createReq(Map.of()));
    db.execute("DELETE FROM spec_content WHERE spec_id = ?", "auth");

    var detail = ops.get("auth");

    assertNull(detail.body());
    assertNull(detail.plan());
  }

  @Test
  void updateReplacesEveryProvidedField() {
    ops.create(createReq(Map.of()));

    var updated =
        ops.update(
            "auth",
            SpecUpdateRequest.fromMap(
                Map.of(
                    "project", "zenith",
                    "status", "pending",
                    "agent", "codex",
                    "model", "claude-opus-4",
                    "branch", "feat/x",
                    "priority", 9,
                    "depends_on", List.of("other"),
                    "repos", List.of("api", "web"))));

    assertEquals("zenith", updated.spec().project());
    assertEquals("codex", updated.spec().agent());
    assertEquals("claude-opus-4", updated.spec().model());
    assertEquals("feat/x", updated.spec().branch());
    assertEquals(9, updated.spec().priority());
    assertEquals(List.of("other"), updated.spec().dependsOn());
    assertEquals(List.of("api", "web"), updated.spec().repos());
  }

  @Test
  void boardCountsNoResidualFindingsWithoutAReviewStore() {
    ops.create(createReq(Map.of("status", "done")));

    var board = new GlobalSpecOperations(specStore, null).board(null);

    assertEquals(0, board.doneOpenFindings());
  }

  @Test
  void updateMissingThrowsNotFound() {
    assertThrows(
        ApiException.class,
        () -> ops.update("ghost", SpecUpdateRequest.fromMap(Map.of("title", "x"))));
  }

  @Test
  void reassigningDispatchedSpecIsRejected() {
    ops.create(createReq(Map.of("status", "in_progress", "assignee", "uday")));
    var ex =
        assertThrows(
            ApiException.class,
            () -> ops.update("auth", SpecUpdateRequest.fromMap(Map.of("assignee", "mady"))));
    assertEquals(ErrorCode.CONFLICT.httpCode(), ex.status());
    assertTrue(ex.getMessage().contains("dispatched"));
  }

  @Test
  void reassigningPendingSpecIsAllowed() {
    ops.create(createReq(Map.of("status", "pending", "assignee", "uday")));
    var updated = ops.update("auth", SpecUpdateRequest.fromMap(Map.of("assignee", "mady")));
    assertEquals("mady", updated.spec().assignee());
  }

  @Test
  void forceReassignsDispatchedSpec() {
    ops.create(createReq(Map.of("status", "in_progress", "assignee", "uday")));
    var updated =
        ops.update(
            "auth", SpecUpdateRequest.fromMap(Map.of("assignee", "mady", "force", Boolean.TRUE)));
    assertEquals("mady", updated.spec().assignee());
  }

  @Test
  void reassigningToSameOwnerOnDispatchedSpecIsAllowed() {
    ops.create(createReq(Map.of("status", "in_progress", "assignee", "uday")));
    var updated = ops.update("auth", SpecUpdateRequest.fromMap(Map.of("assignee", "uday")));
    assertEquals("uday", updated.spec().assignee());
  }

  @Test
  void claimingUnassignedDispatchedSpecIsAllowed() {
    ops.create(createReq(Map.of("status", "in_progress")));
    var updated = ops.update("auth", SpecUpdateRequest.fromMap(Map.of("assignee", "uday")));
    assertEquals("uday", updated.spec().assignee());
  }

  @Test
  void deleteRemovesSpec() {
    ops.create(createReq(Map.of()));
    assertEquals("auth", ops.delete("auth").id());
    assertThrows(ApiException.class, () -> ops.get("auth"));
  }

  @Test
  void deleteMissingThrowsNotFound() {
    assertThrows(ApiException.class, () -> ops.delete("ghost"));
  }

  @Test
  void contentDefaultsToEmptyWhenUnset() {
    ops.create(createReq(Map.of()));
    var content = ops.content("auth");
    assertEquals("", content.body());
    assertEquals("", content.plan());
  }

  @Test
  void contentMissingThrowsNotFound() {
    assertThrows(ApiException.class, () -> ops.content("ghost"));
  }

  @Test
  void setContentThenReadBack() {
    ops.create(createReq(Map.of()));
    ops.setContent("auth", SpecContentRequest.fromMap(Map.of("body", "Body", "plan", "Plan")));
    var content = ops.content("auth");
    assertEquals("Body", content.body());
    assertEquals("Plan", content.plan());
  }

  @Test
  void setContentMissingThrowsNotFound() {
    assertThrows(
        ApiException.class,
        () -> ops.setContent("ghost", SpecContentRequest.fromMap(Map.of("body", "x"))));
  }

  @Test
  void boardReturnsSummary() {
    ops.create(createReq(Map.of("status", "pending")));
    assertNotNull(ops.board("manatee").board());
  }

  @Test
  void operationsWithoutStoreThrowInternal() {
    var noStore = new GlobalSpecOperations(null);
    var ex = assertThrows(ApiException.class, () -> noStore.list(SpecStore.SpecFilter.all()));
    assertEquals(ErrorCode.INTERNAL, ex.failure().errorCode());
  }

  private String seedPassedReviewWithOpenFinding(String specId) {
    var reviewId = reviewStore.createReview(specId, 1);
    var stageId = reviewStore.createStage(reviewId, "security", "agent");
    reviewStore.addFinding(
        stageId,
        Finding.create(
            Finding.Severity.HIGH,
            Finding.Category.SECURITY,
            "Auth.java",
            1,
            1,
            "Issue",
            "Description",
            "",
            null,
            0.9));
    reviewStore.updateReviewStatus(reviewId, "passed");
    return reviewId;
  }

  @Test
  void getReportsOpenFindingsOfLatestPassedReview() {
    ops.create(createReq(Map.of("status", "done")));
    seedPassedReviewWithOpenFinding("auth");

    assertEquals(1, ops.get("auth").openFindings());
  }

  @Test
  void updateToDoneResolvesLinkedSourceFindings() {
    ops.create(createReq(Map.of("status", "done")));
    var reviewId = seedPassedReviewWithOpenFinding("auth");
    var findingId = reviewStore.findingsForReview(reviewId).getFirst().id();
    ops.create(createReq(Map.of("id", "auth-followup", "title", "Follow-up", "status", "pending")));
    reviewStore.linkSourceFindings("auth-followup", List.of(findingId));

    ops.update(
        "auth-followup", SpecUpdateRequest.fromMap(Map.of("status", "done")).withUpdatedBy("uday"));

    assertEquals(
        Finding.Resolution.FIXED, reviewStore.findingsForReview(reviewId).getFirst().resolution());
  }

  @Test
  void updateWithoutDoneTransitionLeavesFindingsOpen() {
    ops.create(createReq(Map.of("status", "done")));
    var reviewId = seedPassedReviewWithOpenFinding("auth");
    var findingId = reviewStore.findingsForReview(reviewId).getFirst().id();
    ops.create(createReq(Map.of("id", "auth-followup", "title", "Follow-up", "status", "pending")));
    reviewStore.linkSourceFindings("auth-followup", List.of(findingId));

    ops.update(
        "auth-followup",
        SpecUpdateRequest.fromMap(Map.of("title", "Renamed")).withUpdatedBy("uday"));

    assertEquals(
        Finding.Resolution.OPEN, reviewStore.findingsForReview(reviewId).getFirst().resolution());
  }

  @Test
  void boardCountsOpenFindingsOnDoneSpecs() {
    ops.create(createReq(Map.of("status", "done")));
    seedPassedReviewWithOpenFinding("auth");
    ops.create(createReq(Map.of("id", "clean", "title", "Clean", "status", "done")));

    assertEquals(1, ops.board("manatee").doneOpenFindings());
  }

  @Test
  void historyListsEveryRevisionOldestFirst() {
    ops.create(createReq(Map.of()).withCreatedBy("uday"));
    ops.setContent("auth", new SpecContentRequest("body one", "plan"));

    var history = ops.history("auth");

    assertEquals("auth", history.specId());
    assertEquals(2, history.revisions().size());
    assertEquals("local", history.revisions().getFirst().origin());
  }

  @Test
  void historyIsEmptyForAnUnknownSpec() {
    assertTrue(ops.history("ghost").revisions().isEmpty());
  }

  @Test
  void restoreBringsBackPriorContentAsANewRevision() {
    ops.create(createReq(Map.of()).withCreatedBy("uday"));
    ops.setContent("auth", new SpecContentRequest("good", "good plan"));
    var goodRev = ops.history("auth").revisions().getLast().rev();
    ops.setContent("auth", new SpecContentRequest("clobbered", "clobbered"));

    var restored = ops.restore("auth", new SpecRestoreRequest(goodRev));

    assertEquals(goodRev, restored.fromRev());
    assertEquals("good", ops.content("auth").body());
    assertEquals("restore", ops.history("auth").revisions().getLast().origin());
  }

  @Test
  void restoreRejectsABlankRev() {
    ops.create(createReq(Map.of()));
    var ex =
        assertThrows(ApiException.class, () -> ops.restore("auth", new SpecRestoreRequest("  ")));
    assertEquals(ErrorCode.INVALID_REQUEST, ex.failure().errorCode());
  }

  @Test
  void restoreRejectsAnUnknownRev() {
    ops.create(createReq(Map.of()));
    var ex =
        assertThrows(ApiException.class, () -> ops.restore("auth", new SpecRestoreRequest("99-x")));
    assertEquals(ErrorCode.INVALID_REQUEST, ex.failure().errorCode());
    assertTrue(ex.failure().errorMessage().contains("99-x"));
  }

  @Test
  void historyAndRestoreWithoutStoreThrowInternal() {
    var noStore = new GlobalSpecOperations(null);
    assertEquals(
        ErrorCode.INTERNAL,
        assertThrows(ApiException.class, () -> noStore.history("x")).failure().errorCode());
    assertEquals(
        ErrorCode.INTERNAL,
        assertThrows(ApiException.class, () -> noStore.restore("x", new SpecRestoreRequest("1-a")))
            .failure()
            .errorCode());
  }
}
