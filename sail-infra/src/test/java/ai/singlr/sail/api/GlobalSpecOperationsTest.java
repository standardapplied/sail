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

import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlobalSpecOperationsTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private GlobalSpecOperations ops;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    ops = new GlobalSpecOperations(new SpecStore(db));
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
  void updateMissingThrowsNotFound() {
    assertThrows(
        ApiException.class,
        () -> ops.update("ghost", SpecUpdateRequest.fromMap(Map.of("title", "x"))));
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
