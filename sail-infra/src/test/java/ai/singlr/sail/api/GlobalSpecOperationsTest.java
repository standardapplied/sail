/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@ActingAs
class GlobalSpecOperationsTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SpecStore specStore;
  private ReviewStore reviewStore;
  private GlobalSpecOperations ops;

  private static final Actor ADMIN = new Actor("ops", Role.ADMIN, Actor.Lane.API);
  private static final Actor UDAY = new Actor("uday", Role.MEMBER, Actor.Lane.API);
  private static final Actor UDAY_ADMIN = new Actor("uday", Role.ADMIN, Actor.Lane.API);
  private static final Actor NOVA_ADMIN = new Actor("nova", Role.ADMIN, Actor.Lane.API);
  private static final Actor MACHINE = new Actor(null, Role.ADMIN, Actor.Lane.API);

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
    Acting.by(UDAY_ADMIN, () -> ops.create(createReq(Map.of()), UDAY_ADMIN));
    assertEquals("uday", ops.get("auth").spec().createdBy());
  }

  @Test
  void createAutoAssignsToTheCreatorWhenUnassigned() {
    Acting.by(UDAY_ADMIN, () -> ops.create(createReq(Map.of()), UDAY_ADMIN));
    assertEquals(
        "uday",
        ops.get("auth").spec().assignee(),
        "a new room lands on the caller so their box wakes it — no orphan on nobody's board");
  }

  @Test
  void createKeepsAnExplicitAssigneeOverTheCreator() {
    Acting.by(UDAY_ADMIN, () -> ops.create(createReq(Map.of("assignee", "alice")), UDAY_ADMIN));
    assertEquals("alice", ops.get("auth").spec().assignee());
  }

  @Test
  void createLeavesTheAssigneeBlankWhenTheCallerOwnsNoFde() {
    Acting.by(MACHINE, () -> ops.create(createReq(Map.of()), MACHINE));
    assertNull(ops.get("auth").spec().assignee());
  }

  @Test
  void clientSuppliedCreatedByIsIgnored() {
    Acting.by(MACHINE, () -> ops.create(createReq(Map.of("created_by", "attacker")), MACHINE));
    assertNull(ops.get("auth").spec().createdBy());
  }

  @Test
  void createSetsUpdatedByToCreator() {
    Acting.by(UDAY_ADMIN, () -> ops.create(createReq(Map.of()), UDAY_ADMIN));
    assertEquals("uday", ops.get("auth").spec().updatedBy());
  }

  @Test
  void updatePersistsUpdatedByWithoutTouchingCreatedBy() {
    Acting.by(UDAY_ADMIN, () -> ops.create(createReq(Map.of()), UDAY_ADMIN));
    Acting.by(
        NOVA_ADMIN,
        () ->
            ops.update("auth", SpecUpdateRequest.fromMap(Map.of("title", "Auth v2")), NOVA_ADMIN));
    var spec = ops.get("auth").spec();
    assertEquals("uday", spec.createdBy());
    assertEquals("nova", spec.updatedBy());
  }

  @Test
  void createThenGetRoundTrips() {
    var created =
        Acting.by(
            ADMIN,
            () ->
                ops.create(
                    createReq(Map.of("status", "pending", "body", "B", "plan", "P")), ADMIN));
    assertEquals("auth", created.spec().id());

    var detail = ops.get("auth");
    assertEquals("manatee", detail.spec().project());
    assertEquals("B", detail.body());
    assertEquals("P", detail.plan());
  }

  @Test
  void createRejectsMissingId() {
    var ex =
        assertThrows(
            ApiException.class,
            () -> Acting.by(ADMIN, () -> ops.create(createReq(Map.of("id", "")), ADMIN)));
    assertEquals(ErrorCode.INVALID_REQUEST, ex.failure().errorCode());
  }

  @Test
  void createRejectsMissingTitle() {
    assertThrows(
        ApiException.class,
        () -> Acting.by(ADMIN, () -> ops.create(createReq(Map.of("title", "")), ADMIN)));
  }

  @Test
  void createRejectsMissingProject() {
    assertThrows(
        ApiException.class,
        () -> Acting.by(ADMIN, () -> ops.create(createReq(Map.of("project", "")), ADMIN)));
  }

  @Test
  void createRejectsInvalidStatus() {
    assertThrows(
        ApiException.class,
        () -> Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "bogus")), ADMIN)));
  }

  @Test
  void createRejectsInvalidModel() {
    var ex =
        assertThrows(
            ApiException.class,
            () ->
                Acting.by(
                    ADMIN, () -> ops.create(createReq(Map.of("model", "bad model!")), ADMIN)));
    assertEquals(ErrorCode.INVALID_REQUEST, ex.failure().errorCode());
  }

  @Test
  void createRejectsInvalidReasoningEffort() {
    assertThrows(
        ApiException.class,
        () ->
            Acting.by(
                ADMIN, () -> ops.create(createReq(Map.of("reasoning_effort", "huge")), ADMIN)));
  }

  @Test
  void createAcceptsValidModelAndReasoning() {
    var created =
        Acting.by(
            ADMIN,
            () ->
                ops.create(
                    createReq(Map.of("model", "claude-opus-4", "reasoning_effort", "high")),
                    ADMIN));
    assertEquals("auth", created.spec().id());
  }

  @Test
  void updateRejectsInvalidModel() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of()), ADMIN));
    assertThrows(
        ApiException.class,
        () ->
            Acting.by(
                ADMIN,
                () ->
                    ops.update(
                        "auth", SpecUpdateRequest.fromMap(Map.of("model", "bad model!")), ADMIN)));
  }

  @Test
  void updateAcceptsValidModel() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of()), ADMIN));
    var updated =
        Acting.by(
            ADMIN,
            () ->
                ops.update(
                    "auth", SpecUpdateRequest.fromMap(Map.of("model", "claude-opus-4")), ADMIN));
    assertEquals("claude-opus-4", updated.spec().model());
  }

  @Test
  void updateClearsModelWhenBlank() {
    Acting.by(
        ADMIN,
        () ->
            ops.create(
                createReq(Map.of("model", "claude-opus-4", "reasoning_effort", "high")), ADMIN));

    var updated =
        Acting.by(
            ADMIN, () -> ops.update("auth", SpecUpdateRequest.fromMap(Map.of("model", "")), ADMIN));

    assertNull(updated.spec().model(), "an empty model clears the column back to null");
    assertEquals("high", updated.spec().reasoningEffort(), "reasoning_effort is untouched");
  }

  @Test
  void updateSetsClearsAndRejectsTheWakeModeOnTheRoom() {
    var rooms = new RoomStore(db);
    var withRooms = new GlobalSpecOperations(specStore, reviewStore, null, null, () -> rooms);
    Acting.by(ADMIN, () -> withRooms.create(createReq(Map.of()), ADMIN));

    var set =
        Acting.by(
            ADMIN,
            () ->
                withRooms.update(
                    "auth", SpecUpdateRequest.fromMap(Map.of("wake", "mention")), ADMIN));
    assertEquals("mention", set.spec().wake());
    assertEquals("mention", set.spec().toMap().get("wake"));
    assertEquals("mention", rooms.findById("auth").orElseThrow().wake(), "the room is the home");

    var untouched =
        Acting.by(
            ADMIN,
            () ->
                withRooms.update("auth", SpecUpdateRequest.fromMap(Map.of("title", "T2")), ADMIN));
    assertEquals("mention", untouched.spec().wake(), "an unrelated edit never wipes the mode");

    var cleared =
        Acting.by(
            ADMIN,
            () -> withRooms.update("auth", SpecUpdateRequest.fromMap(Map.of("wake", "")), ADMIN));
    assertNull(cleared.spec().wake(), "an empty wake clears the mode back to the default");
    assertFalse(cleared.spec().toMap().containsKey("wake"));

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                Acting.by(
                    ADMIN,
                    () ->
                        withRooms.update(
                            "auth", SpecUpdateRequest.fromMap(Map.of("wake", "loud")), ADMIN)));
    assertTrue(refusal.getMessage().contains("on, mention, or off"));
  }

  @Test
  void updateClearsReasoningEffortWhenBlank() {
    Acting.by(
        ADMIN,
        () ->
            ops.create(
                createReq(Map.of("model", "claude-opus-4", "reasoning_effort", "high")), ADMIN));

    var updated =
        Acting.by(
            ADMIN,
            () ->
                ops.update(
                    "auth", SpecUpdateRequest.fromMap(Map.of("reasoning_effort", "")), ADMIN));

    assertNull(updated.spec().reasoningEffort(), "an empty reasoning_effort clears it to null");
    assertEquals("claude-opus-4", updated.spec().model(), "model is untouched");
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
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of()), ADMIN));
    var list = ops.list(SpecStore.SpecFilter.all());
    assertEquals(1, list.total());
  }

  @Test
  void updateChangesFieldsAndDefaultsStatusToExisting() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "in_progress")), ADMIN));
    var updated =
        Acting.by(
            ADMIN,
            () ->
                ops.update(
                    "auth",
                    SpecUpdateRequest.fromMap(
                        Map.of("title", "New title", "reasoning_effort", "high")),
                    ADMIN));
    assertEquals("New title", updated.spec().title());
    assertEquals("in_progress", updated.spec().status());
  }

  @Test
  void getSurvivesASpecWhoseContentRowIsMissing() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of()), ADMIN));
    db.execute("DELETE FROM spec_content WHERE spec_id = ?", "auth");

    var detail = ops.get("auth");

    assertNull(detail.body());
    assertNull(detail.plan());
  }

  @Test
  void updateReplacesEveryProvidedField() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of()), ADMIN));

    var updated =
        Acting.by(
            ADMIN,
            () ->
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
                            "repos", List.of("api", "web"))),
                    ADMIN));

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
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "done")), ADMIN));

    var board = new GlobalSpecOperations(specStore, null).board(null);

    assertEquals(0, board.doneOpenFindings());
  }

  @Test
  void updateMissingThrowsNotFound() {
    assertThrows(
        ApiException.class,
        () ->
            Acting.by(
                ADMIN,
                () -> ops.update("ghost", SpecUpdateRequest.fromMap(Map.of("title", "x")), ADMIN)));
  }

  @Test
  void reassigningDispatchedSpecIsRejected() {
    Acting.by(
        ADMIN,
        () -> ops.create(createReq(Map.of("status", "in_progress", "assignee", "uday")), ADMIN));
    var ex =
        assertThrows(
            ApiException.class,
            () ->
                Acting.by(
                    ADMIN,
                    () ->
                        ops.update(
                            "auth", SpecUpdateRequest.fromMap(Map.of("assignee", "mady")), ADMIN)));
    assertEquals(ErrorCode.CONFLICT.httpCode(), ex.status());
    assertTrue(ex.getMessage().contains("dispatched"));
  }

  @Test
  void reassigningPendingSpecIsAllowed() {
    Acting.by(
        ADMIN, () -> ops.create(createReq(Map.of("status", "pending", "assignee", "uday")), ADMIN));
    var updated =
        Acting.by(
            ADMIN,
            () -> ops.update("auth", SpecUpdateRequest.fromMap(Map.of("assignee", "mady")), ADMIN));
    assertEquals("mady", updated.spec().assignee());
  }

  @Test
  void forceReassignsDispatchedSpec() {
    Acting.by(
        ADMIN,
        () -> ops.create(createReq(Map.of("status", "in_progress", "assignee", "uday")), ADMIN));
    var updated =
        Acting.by(
            ADMIN,
            () ->
                ops.update(
                    "auth",
                    SpecUpdateRequest.fromMap(Map.of("assignee", "mady", "force", Boolean.TRUE)),
                    ADMIN));
    assertEquals("mady", updated.spec().assignee());
  }

  @Test
  void reassigningToSameOwnerOnDispatchedSpecIsAllowed() {
    Acting.by(
        ADMIN,
        () -> ops.create(createReq(Map.of("status", "in_progress", "assignee", "uday")), ADMIN));
    var updated =
        Acting.by(
            ADMIN,
            () -> ops.update("auth", SpecUpdateRequest.fromMap(Map.of("assignee", "uday")), ADMIN));
    assertEquals("uday", updated.spec().assignee());
  }

  @Test
  void claimingUnassignedDispatchedSpecIsAllowed() {
    Acting.by(MACHINE, () -> ops.create(createReq(Map.of("status", "in_progress")), MACHINE));
    var updated =
        Acting.by(
            ADMIN,
            () -> ops.update("auth", SpecUpdateRequest.fromMap(Map.of("assignee", "uday")), ADMIN));
    assertEquals("uday", updated.spec().assignee());
  }

  @Test
  void deleteRemovesSpec() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of()), ADMIN));
    assertEquals("auth", Acting.by(ADMIN, () -> ops.delete("auth", ADMIN)).id());
    assertThrows(ApiException.class, () -> ops.get("auth"));
  }

  @Test
  void deleteMissingThrowsNotFound() {
    assertThrows(ApiException.class, () -> Acting.by(ADMIN, () -> ops.delete("ghost", ADMIN)));
  }

  @Test
  void contentDefaultsToEmptyWhenUnset() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of()), ADMIN));
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
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of()), ADMIN));
    Acting.by(
        ADMIN,
        () ->
            ops.setContent(
                "auth", SpecContentRequest.fromMap(Map.of("body", "Body", "plan", "Plan")), ADMIN));
    var content = ops.content("auth");
    assertEquals("Body", content.body());
    assertEquals("Plan", content.plan());
  }

  @Test
  void setContentMissingThrowsNotFound() {
    assertThrows(
        ApiException.class,
        () ->
            Acting.by(
                ADMIN,
                () ->
                    ops.setContent(
                        "ghost", SpecContentRequest.fromMap(Map.of("body", "x")), ADMIN)));
  }

  @Test
  void boardReturnsSummary() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "pending")), ADMIN));
    assertNotNull(ops.board("manatee").board());
  }

  @Test
  void updateStatusChangePublishesSpecStatusChangedWithFromTo() throws Exception {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "pending")), ADMIN));
    var event =
        captureOne(
            bus ->
                Acting.by(
                    NOVA_ADMIN,
                    () ->
                        bus.update(
                            "auth",
                            SpecUpdateRequest.fromMap(Map.of("status", "in_progress")),
                            NOVA_ADMIN)));
    assertEquals(Event.WellKnownTypes.SPEC_STATUS_CHANGED, event.type());
    assertEquals("manatee", event.project());
    assertEquals("auth", event.spec());
    assertEquals("nova", event.agent());
    assertEquals("pending", event.data().get("from"));
    assertEquals("in_progress", event.data().get("to"));
  }

  @Test
  void updateNonStatusChangePublishesBoardUpdatedAttributedToActor() throws Exception {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "pending")), ADMIN));
    var event =
        captureOne(
            bus ->
                Acting.by(
                    NOVA_ADMIN,
                    () ->
                        bus.update(
                            "auth",
                            SpecUpdateRequest.fromMap(Map.of("title", "Renamed")),
                            NOVA_ADMIN)));
    assertEquals(Event.WellKnownTypes.BOARD_UPDATED, event.type());
    assertEquals("auth", event.spec());
    assertEquals("nova", event.agent());
  }

  @Test
  void updateWithoutActorFallsBackToSailAgent() throws Exception {
    Acting.by(MACHINE, () -> ops.create(createReq(Map.of("status", "pending")), MACHINE));
    var event =
        captureOne(
            bus ->
                Acting.by(
                    MACHINE,
                    () ->
                        bus.update(
                            "auth",
                            SpecUpdateRequest.fromMap(Map.of("title", "Renamed")),
                            MACHINE)));
    assertEquals(Event.SAIL_AGENT, event.agent());
  }

  @Test
  void createPublishesBoardUpdatedAttributedToAuthor() throws Exception {
    var event =
        captureOne(bus -> Acting.by(UDAY_ADMIN, () -> bus.create(createReq(Map.of()), UDAY_ADMIN)));
    assertEquals(Event.WellKnownTypes.BOARD_UPDATED, event.type());
    assertEquals("manatee", event.project());
    assertEquals("auth", event.spec());
    assertEquals("uday", event.agent());
  }

  @Test
  void deletePublishesBoardUpdatedForTheSpecProject() throws Exception {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of()), ADMIN));
    var event = captureOne(bus -> Acting.by(ADMIN, () -> bus.delete("auth", ADMIN)));
    assertEquals(Event.WellKnownTypes.BOARD_UPDATED, event.type());
    assertEquals("manatee", event.project());
    assertEquals("auth", event.spec());
    assertEquals(Event.SAIL_AGENT, event.agent());
  }

  @Test
  void setContentPublishesBoardUpdated() throws Exception {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of()), ADMIN));
    var event =
        captureOne(
            bus ->
                Acting.by(
                    ADMIN,
                    () ->
                        bus.setContent(
                            "auth", SpecContentRequest.fromMap(Map.of("body", "Body")), ADMIN)));
    assertEquals(Event.WellKnownTypes.BOARD_UPDATED, event.type());
    assertEquals("auth", event.spec());
  }

  @Test
  void restorePublishesBoardUpdated() throws Exception {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of()), ADMIN));
    Acting.by(
        ADMIN, () -> ops.setContent("auth", new SpecContentRequest("good", "good plan"), ADMIN));
    var goodRev = ops.history("auth").revisions().getLast().rev();
    Acting.by(
        ADMIN,
        () -> ops.setContent("auth", new SpecContentRequest("clobbered", "clobbered"), ADMIN));
    var event =
        captureOne(
            bus ->
                Acting.by(
                    ADMIN, () -> bus.restore("auth", new SpecRestoreRequest(goodRev), ADMIN)));
    assertEquals(Event.WellKnownTypes.BOARD_UPDATED, event.type());
    assertEquals("auth", event.spec());
  }

  private Event captureOne(java.util.function.Consumer<GlobalSpecOperations> mutation)
      throws Exception {
    try (var bus = new EventBus()) {
      var seen = new ArrayList<Event>();
      var latch = new CountDownLatch(1);
      bus.subscribe(BusTesting.latching(capture(seen), latch));
      mutation.accept(new GlobalSpecOperations(specStore, reviewStore, bus));
      BusTesting.awaitDelivery(latch);
      return seen.getFirst();
    }
  }

  private static EventSubscriber capture(List<Event> sink) {
    return new EventSubscriber() {
      @Override
      public String name() {
        return "capture";
      }

      @Override
      public Predicate<Event> filter() {
        return EventSubscriber.all();
      }

      @Override
      public void onEvent(Event event) {
        sink.add(event);
      }
    };
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
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "done")), ADMIN));
    seedPassedReviewWithOpenFinding("auth");

    assertEquals(1, ops.get("auth").openFindings());
  }

  @Test
  void updateToDoneResolvesLinkedSourceFindings() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "done")), ADMIN));
    var reviewId = seedPassedReviewWithOpenFinding("auth");
    var findingId = reviewStore.findingsForReview(reviewId).getFirst().id();
    Acting.by(
        ADMIN,
        () ->
            ops.create(
                createReq(Map.of("id", "auth-followup", "title", "Follow-up", "status", "pending")),
                ADMIN));
    reviewStore.linkSourceFindings("auth-followup", List.of(findingId));

    Acting.by(
        ADMIN,
        () ->
            ops.update(
                "auth-followup", SpecUpdateRequest.fromMap(Map.of("status", "done")), ADMIN));

    assertEquals(
        Finding.Resolution.FIXED, reviewStore.findingsForReview(reviewId).getFirst().resolution());
  }

  @Test
  void updateToDoneClosesTheSpecsOwnShippedResidue() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "in_progress")), ADMIN));
    var reviewId = seedPassedReviewWithOpenFinding("auth");

    Acting.by(
        ADMIN,
        () -> ops.update("auth", SpecUpdateRequest.fromMap(Map.of("status", "done")), ADMIN));

    assertEquals(
        Finding.Resolution.SHIPPED,
        reviewStore.findingsForReview(reviewId).getFirst().resolution());
  }

  @Test
  void updateWithoutDoneTransitionLeavesFindingsOpen() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "done")), ADMIN));
    var reviewId = seedPassedReviewWithOpenFinding("auth");
    var findingId = reviewStore.findingsForReview(reviewId).getFirst().id();
    Acting.by(
        ADMIN,
        () ->
            ops.create(
                createReq(Map.of("id", "auth-followup", "title", "Follow-up", "status", "pending")),
                ADMIN));
    reviewStore.linkSourceFindings("auth-followup", List.of(findingId));

    Acting.by(
        ADMIN,
        () ->
            ops.update(
                "auth-followup", SpecUpdateRequest.fromMap(Map.of("title", "Renamed")), ADMIN));

    assertEquals(
        Finding.Resolution.OPEN, reviewStore.findingsForReview(reviewId).getFirst().resolution());
  }

  @Test
  void boardCountsOpenFindingsOnDoneSpecs() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "done")), ADMIN));
    seedPassedReviewWithOpenFinding("auth");
    Acting.by(
        ADMIN,
        () ->
            ops.create(
                createReq(Map.of("id", "clean", "title", "Clean", "status", "done")), ADMIN));

    assertEquals(1, ops.board("manatee").doneOpenFindings());
  }

  @Test
  void historyListsEveryRevisionOldestFirst() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of()), ADMIN));
    Acting.by(
        ADMIN, () -> ops.setContent("auth", new SpecContentRequest("body one", "plan"), ADMIN));

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
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of()), ADMIN));
    Acting.by(
        ADMIN, () -> ops.setContent("auth", new SpecContentRequest("good", "good plan"), ADMIN));
    var goodRev = ops.history("auth").revisions().getLast().rev();
    Acting.by(
        ADMIN,
        () -> ops.setContent("auth", new SpecContentRequest("clobbered", "clobbered"), ADMIN));

    var restored =
        Acting.by(ADMIN, () -> ops.restore("auth", new SpecRestoreRequest(goodRev), ADMIN));

    assertEquals(goodRev, restored.fromRev());
    assertEquals("good", ops.content("auth").body());
    assertEquals("restore", ops.history("auth").revisions().getLast().origin());
  }

  @Test
  void restoreRejectsABlankRev() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of()), ADMIN));
    var ex =
        assertThrows(
            ApiException.class,
            () -> Acting.by(ADMIN, () -> ops.restore("auth", new SpecRestoreRequest("  "), ADMIN)));
    assertEquals(ErrorCode.INVALID_REQUEST, ex.failure().errorCode());
  }

  @Test
  void restoreThatChangesTheAssigneeIsAdminOnly() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("assignee", "alice")), ADMIN));
    Acting.by(
        ADMIN,
        () -> ops.update("auth", SpecUpdateRequest.fromMap(Map.of("assignee", "bob")), ADMIN));
    var aliceRev = ops.history("auth").revisions().getFirst().rev();
    var bob = new Actor("bob", Role.MEMBER, Actor.Lane.API);

    var ex =
        assertThrows(
            ApiException.class,
            () -> Acting.by(bob, () -> ops.restore("auth", new SpecRestoreRequest(aliceRev), bob)));

    assertEquals(ErrorCode.FORBIDDEN_ADMIN_ONLY, ex.failure().errorCode());
    assertEquals("bob", ops.get("auth").spec().assignee());
  }

  @Test
  void anAdminMayRestoreARevisionThatChangesTheAssignee() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("assignee", "alice")), ADMIN));
    Acting.by(
        ADMIN,
        () -> ops.update("auth", SpecUpdateRequest.fromMap(Map.of("assignee", "bob")), ADMIN));
    var aliceRev = ops.history("auth").revisions().getFirst().rev();

    Acting.by(ADMIN, () -> ops.restore("auth", new SpecRestoreRequest(aliceRev), ADMIN));

    assertEquals("alice", ops.get("auth").spec().assignee());
  }

  @Test
  void anAssigneeMayRestoreARevisionThatKeepsTheAssignee() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("assignee", "bob")), ADMIN));
    Acting.by(ADMIN, () -> ops.setContent("auth", new SpecContentRequest("good", ""), ADMIN));
    var goodRev = ops.history("auth").revisions().getLast().rev();
    Acting.by(ADMIN, () -> ops.setContent("auth", new SpecContentRequest("clobbered", ""), ADMIN));
    var bob = new Actor("bob", Role.MEMBER, Actor.Lane.API);

    Acting.by(bob, () -> ops.restore("auth", new SpecRestoreRequest(goodRev), bob));

    assertEquals("good", ops.content("auth").body());
  }

  @Test
  void restoreRejectsAnUnknownRev() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of()), ADMIN));
    var ex =
        assertThrows(
            ApiException.class,
            () ->
                Acting.by(ADMIN, () -> ops.restore("auth", new SpecRestoreRequest("99-x"), ADMIN)));
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
        assertThrows(
                ApiException.class,
                () ->
                    Acting.by(
                        ADMIN, () -> noStore.restore("x", new SpecRestoreRequest("1-a"), ADMIN)))
            .failure()
            .errorCode());
  }

  @Test
  void createMintsTheSpecsIdentityRoomWhenARoomAggregateIsWired() {
    var rooms = new RoomStore(db);
    var withRooms = new GlobalSpecOperations(specStore, reviewStore, null, null, () -> rooms);

    Acting.by(
        UDAY_ADMIN,
        () ->
            withRooms.create(
                SpecCreateRequest.fromMap(
                    java.util.Map.of("id", "roomy", "title", "Roomy spec", "project", "acme")),
                UDAY_ADMIN));

    var room = rooms.findById("roomy").orElseThrow();
    assertEquals("Roomy spec", room.title());
    assertEquals("acme", room.project());
    assertEquals("uday", room.assignee(), "an unassigned create defaults assignee to its creator");
    assertNull(room.roster(), "a fresh room seats nobody");
  }

  @Test
  void createWithoutARoomAggregateStillCreatesTheSpec() {
    Acting.by(
        ADMIN,
        () ->
            ops.create(
                SpecCreateRequest.fromMap(
                    java.util.Map.of(
                        "id",
                        "plain",
                        "title",
                        "Plain spec",
                        "project",
                        "acme",
                        "created_by",
                        "uday")),
                ADMIN));

    assertTrue(specStore.findById("plain").isPresent());
  }

  @Test
  void deleteTombstonesTheIdentityRoomAndAReusedIdGetsAFreshRoom() {
    var rooms = new RoomStore(db);
    var withRooms = new GlobalSpecOperations(specStore, reviewStore, null, null, () -> rooms);
    Acting.by(
        ADMIN,
        () ->
            withRooms.create(
                SpecCreateRequest.fromMap(
                    java.util.Map.of("id", "reused", "title", "First life", "project", "acme")),
                ADMIN));
    rooms.updateRoster(
        "reused", "[{\"agent\":\"claude-code\",\"mode\":\"full\",\"engaged_at\":\"t0\"}]");

    Acting.by(ADMIN, () -> withRooms.delete("reused", ADMIN));
    assertTrue(rooms.findById("reused").isEmpty(), "the identity room dies with its spec");

    Acting.by(
        ADMIN,
        () ->
            withRooms.create(
                SpecCreateRequest.fromMap(
                    java.util.Map.of("id", "reused", "title", "Second life", "project", "acme")),
                ADMIN));
    var reborn = rooms.findById("reused").orElseThrow();
    assertEquals("Second life", reborn.title(), "a reused id mints a fresh room");
    assertNull(reborn.roster(), "no ghost member resurrects from the first life");
  }

  @Test
  void anEditPreservesTheEngagementMirrorAndTheRoomLink() {
    var rooms = new RoomStore(db);
    var withRooms = new GlobalSpecOperations(specStore, reviewStore, null, null, () -> rooms);
    Acting.by(
        ADMIN,
        () ->
            withRooms.create(
                SpecCreateRequest.fromMap(
                    java.util.Map.of("id", "kept", "title", "Kept", "project", "acme")),
                ADMIN));
    rooms.updateRoster(
        "kept", "[{\"agent\":\"claude-code\",\"mode\":\"full\",\"engaged_at\":\"t0\"}]");

    Acting.by(
        ADMIN,
        () ->
            withRooms.update(
                "kept",
                SpecUpdateRequest.fromMap(
                    java.util.Map.of("title", "Kept v2", "updated_by", "uday")),
                ADMIN));

    var after = specStore.findById("kept").orElseThrow();
    assertEquals("Kept v2", after.title());
    assertNotNull(
        rooms.findById("kept").orElseThrow().roster(),
        "an ordinary edit must never unseat the room's member");
    assertEquals("kept", after.roomIdOrIdentity(), "the room link survives every edit");
  }

  @Test
  void createIntoAnExistingRoomAttachesInsteadOfMinting() {
    var rooms = new RoomStore(db);
    var withRooms = new GlobalSpecOperations(specStore, reviewStore, null, null, () -> rooms);
    Acting.as(
        "uday",
        () ->
            rooms.create(
                new RoomStore.RoomRow(
                    "design-room",
                    "acme",
                    "Design talk",
                    "uday",
                    "on",
                    null,
                    "uday",
                    null,
                    null,
                    "uday")));

    Acting.by(
        ADMIN,
        () ->
            withRooms.create(
                SpecCreateRequest.fromMap(
                    java.util.Map.of(
                        "id",
                        "attached",
                        "title",
                        "Attached spec",
                        "project",
                        "acme",
                        "room_id",
                        "design-room")),
                ADMIN));

    var spec = specStore.findById("attached").orElseThrow();
    assertEquals("design-room", spec.roomIdOrIdentity(), "the spec lives in the given room");
    assertEquals(
        "Design talk",
        rooms.findById("design-room").orElseThrow().title(),
        "attaching never rewrites the existing room");
    assertTrue(rooms.findById("attached").isEmpty(), "no identity room is minted beside it");
  }

  @Test
  void aSpecCannotBeBornInARoomThatDoesNotExistOrBelongsElsewhere() {
    var rooms = new RoomStore(db);
    var withRooms = new GlobalSpecOperations(specStore, reviewStore, null, null, () -> rooms);
    Acting.as(
        "uday",
        () ->
            rooms.create(
                new RoomStore.RoomRow(
                    "other-room",
                    "beta",
                    "Other project",
                    "uday",
                    "on",
                    null,
                    "uday",
                    null,
                    null,
                    "uday")));

    var missing =
        assertThrows(
            ApiException.class,
            () ->
                Acting.by(
                    ADMIN,
                    () ->
                        withRooms.create(
                            createReq(java.util.Map.of("room_id", "ghost-room")), ADMIN)));
    assertEquals(ErrorCode.ROOM_NOT_FOUND, missing.failure().errorCode());
    assertTrue(specStore.findById("auth").isEmpty(), "a refused birth creates no spec");
    assertTrue(rooms.findById("ghost-room").isEmpty(), "and mints no room under the wrong id");

    var foreign =
        assertThrows(
            ApiException.class,
            () ->
                Acting.by(
                    ADMIN,
                    () ->
                        withRooms.create(
                            createReq(java.util.Map.of("room_id", "other-room")), ADMIN)));
    assertEquals(ErrorCode.INVALID_REQUEST, foreign.failure().errorCode());
    assertTrue(foreign.getMessage().contains("beta"), foreign.getMessage());
    assertTrue(specStore.findById("auth").isEmpty());
  }

  @Test
  void aHomeRoomNeedsARoomAggregateOnThisBox() {
    var noRooms = new GlobalSpecOperations(specStore, reviewStore, null, null, () -> null);
    var refused =
        assertThrows(
            ApiException.class,
            () ->
                Acting.by(
                    ADMIN,
                    () ->
                        noRooms.create(
                            createReq(java.util.Map.of("room_id", "design-room")), ADMIN)));
    assertEquals(ErrorCode.INTERNAL, refused.failure().errorCode());
    assertTrue(refused.getMessage().contains("design-room"), refused.getMessage());
  }

  @Test
  void aSpecsIdIsReservedForTheRoomItMints() {
    var rooms = new RoomStore(db);
    var withRooms = new GlobalSpecOperations(specStore, reviewStore, null, null, () -> rooms);

    var missing =
        assertThrows(
            ApiException.class,
            () ->
                Acting.by(
                    ADMIN,
                    () -> withRooms.create(createReq(java.util.Map.of("room_id", "auth")), ADMIN)));
    assertEquals(ErrorCode.ROOM_NOT_FOUND, missing.failure().errorCode());
    assertTrue(specStore.findById("auth").isEmpty(), "a refused birth creates no spec");

    Acting.as(
        "uday",
        () ->
            rooms.create(
                new RoomStore.RoomRow(
                    "auth",
                    "manatee",
                    "Auth talk",
                    "uday",
                    "on",
                    null,
                    "uday",
                    null,
                    null,
                    "uday")));
    for (var request :
        List.of(createReq(java.util.Map.of()), createReq(java.util.Map.of("room_id", "auth")))) {
      var taken =
          assertThrows(
              ApiException.class, () -> Acting.by(ADMIN, () -> withRooms.create(request, ADMIN)));
      assertEquals(
          ErrorCode.CONFLICT,
          taken.failure().errorCode(),
          "an existing room on the spec's own id is somebody's room, never a binding target");
      assertTrue(taken.getMessage().contains("reserved"), taken.getMessage());
    }
    assertTrue(specStore.findById("auth").isEmpty());
    assertEquals(
        "Auth talk", rooms.findById("auth").orElseThrow().title(), "the room is untouched");
  }

  @Test
  void aRoomLandingOnTheSpecsIdMidBirthFailsTheBirthInsteadOfBeingBorrowed() {
    var rooms = new RoomStore(db);
    Supplier<RoomStore> raced =
        () -> {
          if (specStore.findById("auth").isPresent() && rooms.findById("auth").isEmpty()) {
            Acting.as(
                "ada",
                () ->
                    rooms.create(
                        new RoomStore.RoomRow(
                            "auth",
                            "manatee",
                            "Ada's room",
                            "ada",
                            "on",
                            null,
                            "ada",
                            null,
                            null,
                            "ada")));
          }
          return rooms;
        };
    var withRooms = new GlobalSpecOperations(specStore, reviewStore, null, null, raced);

    assertThrows(
        RuntimeException.class,
        () -> Acting.by(ADMIN, () -> withRooms.create(createReq(Map.of()), ADMIN)));

    assertTrue(
        specStore.findById("auth").isEmpty(),
        "the birth rolls back whole: no spec row claims a room it did not mint");
  }

  @Test
  void deletingASpecBornElsewhereLeavesItsHomeRoomAndANamesakeRoomAlone() {
    var rooms = new RoomStore(db);
    var withRooms = new GlobalSpecOperations(specStore, reviewStore, null, null, () -> rooms);
    Acting.as(
        "ada",
        () ->
            rooms.create(
                new RoomStore.RoomRow(
                    "adas-room",
                    "manatee",
                    "Ada's room",
                    "ada",
                    "on",
                    null,
                    "ada",
                    null,
                    null,
                    "ada")));
    Acting.by(
        ADMIN,
        () ->
            withRooms.create(
                createReq(java.util.Map.of("room_id", "adas-room", "assignee", "mallory")), ADMIN));
    Acting.as(
        "ada",
        () ->
            rooms.create(
                new RoomStore.RoomRow(
                    "auth", "manatee", "Namesake", "ada", "on", null, "ada", null, null, "ada")));

    Acting.by(
        new Actor("mallory", Role.MEMBER, Actor.Lane.API),
        () -> withRooms.delete("auth", new Actor("mallory", Role.MEMBER, Actor.Lane.API)));

    assertTrue(specStore.findById("auth").isEmpty());
    assertTrue(rooms.findById("adas-room").isPresent(), "the home room outlives the spec");
    assertTrue(
        rooms.findById("auth").isPresent(),
        "a room that merely shares the spec's id was never the spec's to delete");
  }

  @Test
  void aWakeEditOnASpecBornElsewhereLandsOnItsHomeRoom() {
    var rooms = new RoomStore(db);
    var withRooms = new GlobalSpecOperations(specStore, reviewStore, null, null, () -> rooms);
    Acting.as(
        "uday",
        () ->
            rooms.create(
                new RoomStore.RoomRow(
                    "design-room",
                    "manatee",
                    "Design talk",
                    "uday",
                    "on",
                    null,
                    "uday",
                    null,
                    null,
                    "uday")));
    Acting.by(
        ADMIN,
        () -> withRooms.create(createReq(java.util.Map.of("room_id", "design-room")), ADMIN));

    var updated =
        Acting.by(
            ADMIN,
            () ->
                withRooms.update(
                    "auth",
                    SpecUpdateRequest.fromMap(
                        java.util.Map.of("wake", "mention", "updated_by", "uday")),
                    ADMIN));

    assertEquals("mention", rooms.findById("design-room").orElseThrow().wake());
    assertEquals("mention", updated.spec().wake(), "the view reads the same room it wrote");
    assertTrue(rooms.findById("auth").isEmpty(), "no phantom room is minted under the spec id");
  }

  @Test
  void aMemberCannotBindTheirSpecIntoSomebodyElsesRoom() {
    var rooms = new RoomStore(db);
    var withRooms = new GlobalSpecOperations(specStore, reviewStore, null, null, () -> rooms);
    Acting.as(
        "ada",
        () ->
            rooms.create(
                new RoomStore.RoomRow(
                    "adas-room",
                    "manatee",
                    "Ada's room",
                    "ada",
                    "on",
                    null,
                    "ada",
                    null,
                    null,
                    "ada")));
    var mallory = new Actor("mallory", Role.MEMBER, Actor.Lane.API);

    var explicit =
        assertThrows(
            ApiException.class,
            () ->
                Acting.by(
                    mallory,
                    () ->
                        withRooms.create(
                            createReq(
                                java.util.Map.of("room_id", "adas-room", "assignee", "mallory")),
                            mallory)));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, explicit.failure().errorCode());
    assertTrue(specStore.findById("auth").isEmpty(), "a refused binding creates no spec");

    var byId =
        assertThrows(
            ApiException.class,
            () ->
                Acting.by(
                    mallory,
                    () ->
                        withRooms.create(
                            createReq(java.util.Map.of("id", "adas-room", "assignee", "mallory")),
                            mallory)));
    assertEquals(
        ErrorCode.CONFLICT,
        byId.failure().errorCode(),
        "a spec whose id collides with an existing room is refused outright");
    assertTrue(specStore.findById("adas-room").isEmpty());

    var viewer = new Actor("ada", Role.VIEWER, Actor.Lane.API);
    var readOnly =
        assertThrows(
            ApiException.class,
            () ->
                Acting.by(
                    viewer,
                    () ->
                        withRooms.create(
                            createReq(java.util.Map.of("room_id", "adas-room")), viewer)));
    assertEquals(ErrorCode.READ_ONLY_CREDENTIAL, readOnly.failure().errorCode());

    Acting.by(
        ADMIN,
        () ->
            withRooms.create(
                createReq(java.util.Map.of("room_id", "adas-room", "assignee", "mallory")), ADMIN));
    assertEquals(
        "adas-room",
        specStore.findById("auth").orElseThrow().roomIdOrIdentity(),
        "an admin may seat work in any room");
  }

  @Test
  void anIdentityIdThatCollidesWithAnotherProjectsRoomIsRefused() {
    var rooms = new RoomStore(db);
    var withRooms = new GlobalSpecOperations(specStore, reviewStore, null, null, () -> rooms);
    Acting.as(
        "uday",
        () ->
            rooms.create(
                new RoomStore.RoomRow(
                    "auth",
                    "beta",
                    "Beta's auth",
                    "uday",
                    "on",
                    null,
                    "uday",
                    null,
                    null,
                    "uday")));

    var foreign =
        assertThrows(
            ApiException.class,
            () -> Acting.by(ADMIN, () -> withRooms.create(createReq(java.util.Map.of()), ADMIN)));
    assertEquals(ErrorCode.CONFLICT, foreign.failure().errorCode());
    assertTrue(specStore.findById("auth").isEmpty(), "no spec lands across the project line");
  }

  @Test
  void createRefusesAMissingActor() {
    assertThrows(NullPointerException.class, () -> ops.create(createReq(Map.of()), null));
  }

  @Test
  void anExplicitWakeEditWritesTheRoomRow() {
    var rooms = new RoomStore(db);
    var withRooms = new GlobalSpecOperations(specStore, reviewStore, null, null, () -> rooms);
    Acting.by(
        ADMIN,
        () ->
            withRooms.create(
                SpecCreateRequest.fromMap(
                    java.util.Map.of(
                        "id",
                        "wakey",
                        "title",
                        "Wakey spec",
                        "project",
                        "acme",
                        "created_by",
                        "uday")),
                ADMIN));

    Acting.by(
        ADMIN,
        () ->
            withRooms.update(
                "wakey",
                SpecUpdateRequest.fromMap(
                    java.util.Map.of("wake", "mention", "updated_by", "uday")),
                ADMIN));

    assertEquals(
        "mention",
        rooms.findById("wakey").orElseThrow().wake(),
        "the room row is the one home the wake mode has");
  }

  @Test
  void anEditWithoutAWakeChangeLeavesTheRoomRowAlone() {
    var rooms = new RoomStore(db);
    var withRooms = new GlobalSpecOperations(specStore, reviewStore, null, null, () -> rooms);
    Acting.by(
        ADMIN,
        () ->
            withRooms.create(
                SpecCreateRequest.fromMap(
                    java.util.Map.of(
                        "id",
                        "still",
                        "title",
                        "Still spec",
                        "project",
                        "acme",
                        "created_by",
                        "uday")),
                ADMIN));
    var before = rooms.latestRev("still");

    Acting.by(
        ADMIN,
        () ->
            withRooms.update(
                "still",
                SpecUpdateRequest.fromMap(
                    java.util.Map.of("title", "Renamed", "updated_by", "uday")),
                ADMIN));

    assertEquals(before, rooms.latestRev("still"), "no wake edit, no room write");
  }
}
