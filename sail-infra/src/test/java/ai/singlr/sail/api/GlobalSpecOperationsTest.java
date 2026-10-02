/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
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
        new HashMap<String, Object>(Map.of("id", "auth", "project", "manatee", "title", "Auth"));
    base.putAll(overrides);
    return SpecCreateRequest.fromMap(base);
  }

  @Test
  void createPersistsAuthenticatedAuthor() {
    Acting.by(UDAY_ADMIN, () -> ops.create(createReq(Map.of())));
    assertEquals("uday", ops.get("auth").spec().createdBy());
  }

  @Test
  void createLeavesABlankAssigneeUnassignedAndRecordsTheCreator() {
    Acting.by(UDAY_ADMIN, () -> ops.create(createReq(Map.of("assignee", " "))));
    var spec = ops.get("auth").spec();
    assertNull(spec.assignee(), "anyone may claim it; its creator's box wakes its room");
    assertEquals("uday", spec.createdBy());
  }

  @Test
  void createKeepsAnExplicitAssigneeOverTheCreator() {
    Acting.by(UDAY_ADMIN, () -> ops.create(createReq(Map.of("assignee", "alice"))));
    assertEquals("alice", ops.get("auth").spec().assignee());
  }

  @Test
  void createLeavesTheAssigneeBlankWhenTheCallerOwnsNoFde() {
    Acting.by(MACHINE, () -> ops.create(createReq(Map.of())));
    assertNull(ops.get("auth").spec().assignee());
  }

  @Test
  void clientSuppliedCreatedByIsIgnored() {
    Acting.by(MACHINE, () -> ops.create(createReq(Map.of("created_by", "attacker"))));
    assertNull(ops.get("auth").spec().createdBy());
  }

  @Test
  void createSetsUpdatedByToCreator() {
    Acting.by(UDAY_ADMIN, () -> ops.create(createReq(Map.of())));
    assertEquals("uday", ops.get("auth").spec().updatedBy());
  }

  @Test
  void updatePersistsUpdatedByWithoutTouchingCreatedBy() {
    Acting.by(UDAY_ADMIN, () -> ops.create(createReq(Map.of())));
    Acting.by(
        NOVA_ADMIN,
        () -> ops.update("auth", SpecUpdateRequest.fromMap(Map.of("title", "Auth v2"))));
    var spec = ops.get("auth").spec();
    assertEquals("uday", spec.createdBy());
    assertEquals("nova", spec.updatedBy());
  }

  @Test
  void createThenGetRoundTrips() {
    var created =
        Acting.by(
            ADMIN,
            () -> ops.create(createReq(Map.of("status", "pending", "body", "B", "plan", "P"))));
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
            () -> Acting.by(ADMIN, () -> ops.create(createReq(Map.of("id", "")))));
    assertEquals(ErrorCode.INVALID_REQUEST, ex.failure().errorCode());
  }

  @Test
  void createRejectsMissingTitle() {
    assertThrows(
        ApiException.class,
        () -> Acting.by(ADMIN, () -> ops.create(createReq(Map.of("title", "")))));
  }

  @Test
  void createRejectsMissingProject() {
    assertThrows(
        ApiException.class,
        () -> Acting.by(ADMIN, () -> ops.create(createReq(Map.of("project", "")))));
  }

  @Test
  void createRejectsInvalidStatus() {
    assertThrows(
        ApiException.class,
        () -> Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "bogus")))));
  }

  @Test
  void createRejectsInvalidModel() {
    var ex =
        assertThrows(
            ApiException.class,
            () -> Acting.by(ADMIN, () -> ops.create(createReq(Map.of("model", "bad model!")))));
    assertEquals(ErrorCode.INVALID_REQUEST, ex.failure().errorCode());
  }

  @Test
  void createRejectsInvalidReasoningEffort() {
    assertThrows(
        ApiException.class,
        () -> Acting.by(ADMIN, () -> ops.create(createReq(Map.of("reasoning_effort", "huge")))));
  }

  @Test
  void createAcceptsValidModelAndReasoning() {
    var created =
        Acting.by(
            ADMIN,
            () ->
                ops.create(
                    createReq(Map.of("model", "claude-opus-4", "reasoning_effort", "high"))));
    assertEquals("auth", created.spec().id());
  }

  @Test
  void updateRejectsInvalidModel() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of())));
    assertThrows(
        ApiException.class,
        () ->
            Acting.by(
                ADMIN,
                () ->
                    ops.update("auth", SpecUpdateRequest.fromMap(Map.of("model", "bad model!")))));
  }

  @Test
  void updateAcceptsValidModel() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of())));
    var updated =
        Acting.by(
            ADMIN,
            () -> ops.update("auth", SpecUpdateRequest.fromMap(Map.of("model", "claude-opus-4"))));
    assertEquals("claude-opus-4", updated.spec().model());
  }

  @Test
  void updateClearsModelWhenBlank() {
    Acting.by(
        ADMIN,
        () -> ops.create(createReq(Map.of("model", "claude-opus-4", "reasoning_effort", "high"))));

    var updated =
        Acting.by(ADMIN, () -> ops.update("auth", SpecUpdateRequest.fromMap(Map.of("model", ""))));

    assertNull(updated.spec().model(), "an empty model clears the column back to null");
    assertEquals("high", updated.spec().reasoningEffort(), "reasoning_effort is untouched");
  }

  @Test
  void updateSetsClearsAndRejectsTheWakeModeOnTheRoom() {
    var rooms = new RoomStore(db);
    var withRooms = new GlobalSpecOperations(specStore, reviewStore, null, null, () -> rooms);
    Acting.by(ADMIN, () -> withRooms.create(createReq(Map.of())));

    var set =
        Acting.by(
            ADMIN,
            () -> withRooms.update("auth", SpecUpdateRequest.fromMap(Map.of("wake", "mention"))));
    assertEquals("mention", set.spec().wake());
    assertEquals("mention", set.spec().toMap().get("wake"));
    assertEquals("mention", rooms.findById("auth").orElseThrow().wake(), "the room is the home");

    var untouched =
        Acting.by(
            ADMIN,
            () -> withRooms.update("auth", SpecUpdateRequest.fromMap(Map.of("title", "T2"))));
    assertEquals("mention", untouched.spec().wake(), "an unrelated edit never wipes the mode");

    var cleared =
        Acting.by(
            ADMIN, () -> withRooms.update("auth", SpecUpdateRequest.fromMap(Map.of("wake", ""))));
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
                            "auth", SpecUpdateRequest.fromMap(Map.of("wake", "loud")))));
    assertTrue(refusal.getMessage().contains("on, mention, or off"));
  }

  @Test
  void aMemberClaimingAnUnassignedSpecSetsItsWakeInTheSameUpdate() {
    var rooms = new RoomStore(db);
    var withRooms = new GlobalSpecOperations(specStore, reviewStore, null, null, () -> rooms);
    var bob = new Actor("bob", Role.MEMBER, Actor.Lane.API);
    Acting.by(bob, () -> withRooms.create(createReq(Map.of())));

    var claimed =
        Acting.by(
            UDAY,
            () ->
                withRooms.update(
                    "auth", SpecUpdateRequest.fromMap(Map.of("assignee", "uday", "wake", "off"))));

    assertEquals("uday", claimed.spec().assignee());
    assertEquals("off", rooms.findById("auth").orElseThrow().wake());
  }

  @Test
  void aWakeEditOnABoxThatKeepsNoRoomsHasNothingToWrite() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of())));

    var updated =
        Acting.by(
            ADMIN,
            () ->
                ops.update(
                    "auth", SpecUpdateRequest.fromMap(Map.of("wake", "mention", "title", "T2"))));

    assertEquals("T2", updated.spec().title());
    assertNull(updated.spec().wake());
  }

  @Test
  void updateClearsReasoningEffortWhenBlank() {
    Acting.by(
        ADMIN,
        () -> ops.create(createReq(Map.of("model", "claude-opus-4", "reasoning_effort", "high"))));

    var updated =
        Acting.by(
            ADMIN,
            () -> ops.update("auth", SpecUpdateRequest.fromMap(Map.of("reasoning_effort", ""))));

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
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of())));
    var list = ops.list(SpecStore.SpecFilter.all());
    assertEquals(1, list.total());
  }

  @Test
  void updateChangesFieldsAndDefaultsStatusToExisting() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "in_progress"))));
    var updated =
        Acting.by(
            ADMIN,
            () ->
                ops.update(
                    "auth",
                    SpecUpdateRequest.fromMap(
                        Map.of("title", "New title", "reasoning_effort", "high"))));
    assertEquals("New title", updated.spec().title());
    assertEquals("in_progress", updated.spec().status());
  }

  @Test
  void getSurvivesASpecWhoseContentRowIsMissing() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of())));
    db.execute("DELETE FROM spec_content WHERE spec_id = ?", "auth");

    var detail = ops.get("auth");

    assertNull(detail.body());
    assertNull(detail.plan());
  }

  @Test
  void updateReplacesEveryProvidedField() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of())));

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
                            "repos", List.of("api", "web")))));

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
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "done"))));

    var board = new GlobalSpecOperations(specStore, null).board(null);

    assertEquals(0, board.doneOpenFindings());
  }

  @Test
  void updateMissingThrowsNotFound() {
    assertThrows(
        ApiException.class,
        () ->
            Acting.by(
                ADMIN, () -> ops.update("ghost", SpecUpdateRequest.fromMap(Map.of("title", "x")))));
  }

  @Test
  void reassigningDispatchedSpecIsRejected() {
    Acting.by(
        ADMIN, () -> ops.create(createReq(Map.of("status", "in_progress", "assignee", "uday"))));
    var ex =
        assertThrows(
            ApiException.class,
            () ->
                Acting.by(
                    ADMIN,
                    () ->
                        ops.update("auth", SpecUpdateRequest.fromMap(Map.of("assignee", "mady")))));
    assertEquals(ErrorCode.CONFLICT.httpCode(), ex.status());
    assertTrue(ex.getMessage().contains("dispatched"));
  }

  @Test
  void reassigningPendingSpecIsAllowed() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "pending", "assignee", "uday"))));
    var updated =
        Acting.by(
            ADMIN, () -> ops.update("auth", SpecUpdateRequest.fromMap(Map.of("assignee", "mady"))));
    assertEquals("mady", updated.spec().assignee());
  }

  @Test
  void forceReassignsDispatchedSpec() {
    Acting.by(
        ADMIN, () -> ops.create(createReq(Map.of("status", "in_progress", "assignee", "uday"))));
    var updated =
        Acting.by(
            ADMIN,
            () ->
                ops.update(
                    "auth",
                    SpecUpdateRequest.fromMap(Map.of("assignee", "mady", "force", Boolean.TRUE))));
    assertEquals("mady", updated.spec().assignee());
  }

  @Test
  void reassigningToSameOwnerOnDispatchedSpecIsAllowed() {
    Acting.by(
        ADMIN, () -> ops.create(createReq(Map.of("status", "in_progress", "assignee", "uday"))));
    var updated =
        Acting.by(
            ADMIN, () -> ops.update("auth", SpecUpdateRequest.fromMap(Map.of("assignee", "uday"))));
    assertEquals("uday", updated.spec().assignee());
  }

  @Test
  void claimingUnassignedDispatchedSpecIsAllowed() {
    Acting.by(MACHINE, () -> ops.create(createReq(Map.of("status", "in_progress"))));
    var updated =
        Acting.by(
            ADMIN, () -> ops.update("auth", SpecUpdateRequest.fromMap(Map.of("assignee", "uday"))));
    assertEquals("uday", updated.spec().assignee());
  }

  @Test
  void deleteRemovesSpec() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of())));
    assertEquals("auth", Acting.by(ADMIN, () -> ops.delete("auth")).id());
    assertThrows(ApiException.class, () -> ops.get("auth"));
  }

  @Test
  void deleteMissingThrowsNotFound() {
    assertThrows(ApiException.class, () -> Acting.by(ADMIN, () -> ops.delete("ghost")));
  }

  @Test
  void contentDefaultsToEmptyWhenUnset() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of())));
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
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of())));
    Acting.by(
        ADMIN,
        () ->
            ops.setContent(
                "auth", SpecContentRequest.fromMap(Map.of("body", "Body", "plan", "Plan"))));
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
                () -> ops.setContent("ghost", SpecContentRequest.fromMap(Map.of("body", "x")))));
  }

  @Test
  void boardReturnsSummary() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "pending"))));
    assertNotNull(ops.board("manatee").board());
  }

  @Test
  void updateStatusChangePublishesSpecStatusChangedWithFromTo() throws Exception {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "pending"))));
    var event =
        captureOne(
            bus ->
                Acting.by(
                    NOVA_ADMIN,
                    () ->
                        bus.update(
                            "auth", SpecUpdateRequest.fromMap(Map.of("status", "in_progress")))));
    assertEquals(Event.WellKnownTypes.SPEC_STATUS_CHANGED, event.type());
    assertEquals("manatee", event.project());
    assertEquals("auth", event.spec());
    assertEquals("nova", event.agent());
    assertEquals("pending", event.data().get("from"));
    assertEquals("in_progress", event.data().get("to"));
  }

  @Test
  void updateNonStatusChangePublishesBoardUpdatedAttributedToActor() throws Exception {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "pending"))));
    var event =
        captureOne(
            bus ->
                Acting.by(
                    NOVA_ADMIN,
                    () ->
                        bus.update("auth", SpecUpdateRequest.fromMap(Map.of("title", "Renamed")))));
    assertEquals(Event.WellKnownTypes.BOARD_UPDATED, event.type());
    assertEquals("auth", event.spec());
    assertEquals("nova", event.agent());
  }

  @Test
  void updateWithoutActorFallsBackToSailAgent() throws Exception {
    Acting.by(MACHINE, () -> ops.create(createReq(Map.of("status", "pending"))));
    var event =
        captureOne(
            bus ->
                Acting.by(
                    MACHINE,
                    () ->
                        bus.update("auth", SpecUpdateRequest.fromMap(Map.of("title", "Renamed")))));
    assertEquals(Event.SAIL_AGENT, event.agent());
  }

  @Test
  void createPublishesBoardUpdatedAttributedToAuthor() throws Exception {
    var event = captureOne(bus -> Acting.by(UDAY_ADMIN, () -> bus.create(createReq(Map.of()))));
    assertEquals(Event.WellKnownTypes.BOARD_UPDATED, event.type());
    assertEquals("manatee", event.project());
    assertEquals("auth", event.spec());
    assertEquals("uday", event.agent());
  }

  @Test
  void deletePublishesBoardUpdatedForTheSpecProject() throws Exception {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of())));
    var event = captureOne(bus -> Acting.by(ADMIN, () -> bus.delete("auth")));
    assertEquals(Event.WellKnownTypes.BOARD_UPDATED, event.type());
    assertEquals("manatee", event.project());
    assertEquals("auth", event.spec());
    assertEquals(Event.SAIL_AGENT, event.agent());
  }

  @Test
  void setContentPublishesBoardUpdated() throws Exception {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of())));
    var event =
        captureOne(
            bus ->
                Acting.by(
                    ADMIN,
                    () ->
                        bus.setContent(
                            "auth", SpecContentRequest.fromMap(Map.of("body", "Body")))));
    assertEquals(Event.WellKnownTypes.BOARD_UPDATED, event.type());
    assertEquals("auth", event.spec());
  }

  @Test
  void restorePublishesBoardUpdated() throws Exception {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of())));
    Acting.by(ADMIN, () -> ops.setContent("auth", new SpecContentRequest("good", "good plan")));
    var goodRev = ops.history("auth").revisions().getLast().rev();
    Acting.by(
        ADMIN, () -> ops.setContent("auth", new SpecContentRequest("clobbered", "clobbered")));
    var event =
        captureOne(
            bus -> Acting.by(ADMIN, () -> bus.restore("auth", new SpecRestoreRequest(goodRev))));
    assertEquals(Event.WellKnownTypes.BOARD_UPDATED, event.type());
    assertEquals("auth", event.spec());
  }

  private Event captureOne(Consumer<GlobalSpecOperations> mutation) throws Exception {
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
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "done"))));
    seedPassedReviewWithOpenFinding("auth");

    assertEquals(1, ops.get("auth").openFindings());
  }

  @Test
  void updateToDoneResolvesLinkedSourceFindings() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "done"))));
    var reviewId = seedPassedReviewWithOpenFinding("auth");
    var findingId = reviewStore.findingsForReview(reviewId).getFirst().id();
    Acting.by(
        ADMIN,
        () ->
            ops.create(
                createReq(
                    Map.of("id", "auth-followup", "title", "Follow-up", "status", "pending"))));
    reviewStore.linkSourceFindings("auth-followup", List.of(findingId));

    Acting.by(
        ADMIN,
        () -> ops.update("auth-followup", SpecUpdateRequest.fromMap(Map.of("status", "done"))));
    assertEquals(
        Finding.Resolution.FIXED, reviewStore.findingsForReview(reviewId).getFirst().resolution());

    Acting.by(
        ADMIN,
        () -> ops.update("auth-followup", SpecUpdateRequest.fromMap(Map.of("status", "archived"))));

    assertEquals(
        Finding.Resolution.FIXED,
        reviewStore.findingsForReview(reviewId).getFirst().resolution(),
        "the fix outlives the follow-up's archive");
  }

  @Test
  void archivingAShippedFollowUpFixesItsSourceFindingsFirstWhenAnEarlierRunWasLost() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "done"))));
    var reviewId = seedPassedReviewWithOpenFinding("auth");
    var findingId = reviewStore.findingsForReview(reviewId).getFirst().id();
    Acting.by(
        ADMIN,
        () ->
            ops.create(
                createReq(
                    Map.of("id", "auth-followup", "title", "Follow-up", "status", "pending"))));
    reviewStore.linkSourceFindings("auth-followup", List.of(findingId));
    specStore.updateStatus("auth-followup", SpecStatus.DONE);
    assertEquals(
        Finding.Resolution.OPEN, reviewStore.findingsForReview(reviewId).getFirst().resolution());

    Acting.by(
        ADMIN,
        () -> ops.update("auth-followup", SpecUpdateRequest.fromMap(Map.of("status", "archived"))));

    var fixed = reviewStore.findingsForReview(reviewId).getFirst();
    assertEquals(Finding.Resolution.FIXED, fixed.resolution());
    assertEquals(
        Actor.system().handle(),
        new ChangeLog(db).head("review", reviewId).orElseThrow().actor(),
        "the fix is this box's machinery's, not the archiving user's");
  }

  @Test
  void aNodeMarkingItsFollowUpDoneLeavesTheSourceFindingsToMain() {
    var node =
        new GlobalSpecOperations(specStore, reviewStore, null, null, () -> null, () -> false);
    Acting.by(ADMIN, () -> node.create(createReq(Map.of("status", "done"))));
    var reviewId = seedPassedReviewWithOpenFinding("auth");
    var findingId = reviewStore.findingsForReview(reviewId).getFirst().id();
    Acting.by(
        ADMIN,
        () ->
            node.create(
                createReq(
                    Map.of("id", "auth-followup", "title", "Follow-up", "status", "pending"))));
    reviewStore.linkSourceFindings("auth-followup", List.of(findingId));
    var rev = reviewStore.latestRev(reviewId);

    Acting.by(
        ADMIN,
        () -> node.update("auth-followup", SpecUpdateRequest.fromMap(Map.of("status", "done"))));

    assertEquals(
        Finding.Resolution.OPEN, reviewStore.findingsForReview(reviewId).getFirst().resolution());
    assertEquals(
        rev, reviewStore.latestRev(reviewId), "a node writes nothing to the source review");
  }

  @Test
  void updateToDoneClosesTheSpecsOwnShippedResidue() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "in_progress"))));
    var reviewId = seedPassedReviewWithOpenFinding("auth");

    Acting.by(ADMIN, () -> ops.update("auth", SpecUpdateRequest.fromMap(Map.of("status", "done"))));

    assertEquals(
        Finding.Resolution.SHIPPED,
        reviewStore.findingsForReview(reviewId).getFirst().resolution());
  }

  @Test
  void updateWithoutDoneTransitionLeavesFindingsOpen() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "done"))));
    var reviewId = seedPassedReviewWithOpenFinding("auth");
    var findingId = reviewStore.findingsForReview(reviewId).getFirst().id();
    Acting.by(
        ADMIN,
        () ->
            ops.create(
                createReq(
                    Map.of("id", "auth-followup", "title", "Follow-up", "status", "pending"))));
    reviewStore.linkSourceFindings("auth-followup", List.of(findingId));

    Acting.by(
        ADMIN,
        () -> ops.update("auth-followup", SpecUpdateRequest.fromMap(Map.of("title", "Renamed"))));

    assertEquals(
        Finding.Resolution.OPEN, reviewStore.findingsForReview(reviewId).getFirst().resolution());
  }

  @Test
  void boardCountsOpenFindingsOnDoneSpecs() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("status", "done"))));
    seedPassedReviewWithOpenFinding("auth");
    Acting.by(
        ADMIN,
        () -> ops.create(createReq(Map.of("id", "clean", "title", "Clean", "status", "done"))));

    assertEquals(1, ops.board("manatee").doneOpenFindings());
  }

  @Test
  void historyListsEveryRevisionOldestFirst() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of())));
    Acting.by(ADMIN, () -> ops.setContent("auth", new SpecContentRequest("body one", "plan")));

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
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of())));
    Acting.by(ADMIN, () -> ops.setContent("auth", new SpecContentRequest("good", "good plan")));
    var goodRev = ops.history("auth").revisions().getLast().rev();
    Acting.by(
        ADMIN, () -> ops.setContent("auth", new SpecContentRequest("clobbered", "clobbered")));

    var restored = Acting.by(ADMIN, () -> ops.restore("auth", new SpecRestoreRequest(goodRev)));

    assertEquals(goodRev, restored.fromRev());
    assertEquals("good", ops.content("auth").body());
    assertEquals("restore", ops.history("auth").revisions().getLast().origin());
  }

  @Test
  void restoreRejectsABlankRev() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of())));
    var ex =
        assertThrows(
            ApiException.class,
            () -> Acting.by(ADMIN, () -> ops.restore("auth", new SpecRestoreRequest("  "))));
    assertEquals(ErrorCode.INVALID_REQUEST, ex.failure().errorCode());
  }

  @Test
  void restoreThatChangesTheAssigneeIsAdminOnly() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("assignee", "alice"))));
    Acting.by(
        ADMIN, () -> ops.update("auth", SpecUpdateRequest.fromMap(Map.of("assignee", "bob"))));
    var aliceRev = ops.history("auth").revisions().getFirst().rev();
    var bob = new Actor("bob", Role.MEMBER, Actor.Lane.API);

    var ex =
        assertThrows(
            ApiException.class,
            () -> Acting.by(bob, () -> ops.restore("auth", new SpecRestoreRequest(aliceRev))));

    assertEquals(ErrorCode.FORBIDDEN_ADMIN_ONLY, ex.failure().errorCode());
    assertEquals("bob", ops.get("auth").spec().assignee());
  }

  @Test
  void anAdminMayRestoreARevisionThatChangesTheAssignee() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("assignee", "alice"))));
    Acting.by(
        ADMIN, () -> ops.update("auth", SpecUpdateRequest.fromMap(Map.of("assignee", "bob"))));
    var aliceRev = ops.history("auth").revisions().getFirst().rev();

    Acting.by(ADMIN, () -> ops.restore("auth", new SpecRestoreRequest(aliceRev)));

    assertEquals("alice", ops.get("auth").spec().assignee());
  }

  @Test
  void anAssigneeMayRestoreARevisionThatKeepsTheAssignee() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of("assignee", "bob"))));
    Acting.by(ADMIN, () -> ops.setContent("auth", new SpecContentRequest("good", "")));
    var goodRev = ops.history("auth").revisions().getLast().rev();
    Acting.by(ADMIN, () -> ops.setContent("auth", new SpecContentRequest("clobbered", "")));
    var bob = new Actor("bob", Role.MEMBER, Actor.Lane.API);

    Acting.by(bob, () -> ops.restore("auth", new SpecRestoreRequest(goodRev)));

    assertEquals("good", ops.content("auth").body());
  }

  @Test
  void restoreRejectsAnUnknownRev() {
    Acting.by(ADMIN, () -> ops.create(createReq(Map.of())));
    var ex =
        assertThrows(
            ApiException.class,
            () -> Acting.by(ADMIN, () -> ops.restore("auth", new SpecRestoreRequest("99-x"))));
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
                () -> Acting.by(ADMIN, () -> noStore.restore("x", new SpecRestoreRequest("1-a"))))
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
                    Map.of("id", "roomy", "title", "Roomy spec", "project", "acme"))));

    var room = rooms.findById("roomy").orElseThrow();
    assertEquals("Roomy spec", room.title());
    assertEquals("acme", room.project());
    assertNull(room.assignee(), "an unassigned spec's identity room is unassigned too");
    assertEquals("uday", room.createdBy());
    assertNull(room.roster(), "a fresh room seats nobody");
  }

  @Test
  void createWithoutARoomAggregateStillCreatesTheSpec() {
    Acting.by(
        ADMIN,
        () ->
            ops.create(
                SpecCreateRequest.fromMap(
                    Map.of(
                        "id",
                        "plain",
                        "title",
                        "Plain spec",
                        "project",
                        "acme",
                        "created_by",
                        "uday"))));

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
                    Map.of("id", "reused", "title", "First life", "project", "acme"))));
    rooms.updateRoster(
        "reused", "[{\"agent\":\"claude-code\",\"mode\":\"full\",\"engaged_at\":\"t0\"}]");

    Acting.by(ADMIN, () -> withRooms.delete("reused"));
    assertTrue(rooms.findById("reused").isEmpty(), "the identity room dies with its spec");

    Acting.by(
        ADMIN,
        () ->
            withRooms.create(
                SpecCreateRequest.fromMap(
                    Map.of("id", "reused", "title", "Second life", "project", "acme"))));
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
                    Map.of("id", "kept", "title", "Kept", "project", "acme"))));
    rooms.updateRoster(
        "kept", "[{\"agent\":\"claude-code\",\"mode\":\"full\",\"engaged_at\":\"t0\"}]");

    Acting.by(
        ADMIN,
        () ->
            withRooms.update(
                "kept",
                SpecUpdateRequest.fromMap(Map.of("title", "Kept v2", "updated_by", "uday"))));

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
                    Map.of(
                        "id",
                        "attached",
                        "title",
                        "Attached spec",
                        "project",
                        "acme",
                        "room_id",
                        "design-room"))));

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
                    ADMIN, () -> withRooms.create(createReq(Map.of("room_id", "ghost-room")))));
    assertEquals(ErrorCode.ROOM_NOT_FOUND, missing.failure().errorCode());
    assertTrue(specStore.findById("auth").isEmpty(), "a refused birth creates no spec");
    assertTrue(rooms.findById("ghost-room").isEmpty(), "and mints no room under the wrong id");

    var foreign =
        assertThrows(
            ApiException.class,
            () ->
                Acting.by(
                    ADMIN, () -> withRooms.create(createReq(Map.of("room_id", "other-room")))));
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
                    ADMIN, () -> noRooms.create(createReq(Map.of("room_id", "design-room")))));
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
            () -> Acting.by(ADMIN, () -> withRooms.create(createReq(Map.of("room_id", "auth")))));
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
    for (var request : List.of(createReq(Map.of()), createReq(Map.of("room_id", "auth")))) {
      var taken =
          assertThrows(ApiException.class, () -> Acting.by(ADMIN, () -> withRooms.create(request)));
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
        () -> Acting.by(ADMIN, () -> withRooms.create(createReq(Map.of()))));

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
        () -> withRooms.create(createReq(Map.of("room_id", "adas-room", "assignee", "mallory"))));
    Acting.as(
        "ada",
        () ->
            rooms.create(
                new RoomStore.RoomRow(
                    "auth", "manatee", "Namesake", "ada", "on", null, "ada", null, null, "ada")));

    Acting.by(new Actor("mallory", Role.MEMBER, Actor.Lane.API), () -> withRooms.delete("auth"));

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
    Acting.by(ADMIN, () -> withRooms.create(createReq(Map.of("room_id", "design-room"))));

    var updated =
        Acting.by(
            ADMIN,
            () ->
                withRooms.update(
                    "auth",
                    SpecUpdateRequest.fromMap(Map.of("wake", "mention", "updated_by", "uday"))));

    assertEquals("mention", rooms.findById("design-room").orElseThrow().wake());
    assertEquals("mention", updated.spec().wake(), "the view reads the same room it wrote");
    assertTrue(rooms.findById("auth").isEmpty(), "no phantom room is minted under the spec id");
  }

  @Test
  void aWakeThroughASpecBornInSomeoneElsesRoomIsRefusedWithTheRoomOwnersText() {
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
                    null,
                    null,
                    null,
                    null)));
    Acting.by(
        ADMIN,
        () -> withRooms.create(createReq(Map.of("room_id", "design-room", "assignee", "bob"))));
    var bob = new Actor("bob", Role.MEMBER, Actor.Lane.API);

    var refused =
        assertThrows(
            ApiException.class,
            () ->
                Acting.by(
                    bob,
                    () ->
                        withRooms.update(
                            "auth",
                            SpecUpdateRequest.fromMap(
                                Map.of("wake", "off", "title", "Bob's retitle")))));

    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, refused.failure().errorCode());
    assertEquals("Room 'design-room' belongs to 'uday', not you.", refused.getMessage());
    assertEquals("on", rooms.findById("design-room").orElseThrow().wake());
    assertNotEquals(
        "Bob's retitle", specStore.findById("auth").orElseThrow().title(), "nothing is written");
    var retitled =
        Acting.by(
            bob,
            () ->
                withRooms.update(
                    "auth", SpecUpdateRequest.fromMap(Map.of("title", "Bob's retitle"))));
    assertEquals("Bob's retitle", retitled.spec().title(), "the spec itself is still bob's");
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
                            createReq(Map.of("room_id", "adas-room", "assignee", "mallory")))));
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
                            createReq(Map.of("id", "adas-room", "assignee", "mallory")))));
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
                    viewer, () -> withRooms.create(createReq(Map.of("room_id", "adas-room")))));
    assertEquals(ErrorCode.READ_ONLY_CREDENTIAL, readOnly.failure().errorCode());

    Acting.by(
        ADMIN,
        () -> withRooms.create(createReq(Map.of("room_id", "adas-room", "assignee", "mallory"))));
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
            () -> Acting.by(ADMIN, () -> withRooms.create(createReq(Map.of()))));
    assertEquals(ErrorCode.CONFLICT, foreign.failure().errorCode());
    assertTrue(specStore.findById("auth").isEmpty(), "no spec lands across the project line");
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
                    Map.of(
                        "id",
                        "wakey",
                        "title",
                        "Wakey spec",
                        "project",
                        "acme",
                        "created_by",
                        "uday"))));

    Acting.by(
        ADMIN,
        () ->
            withRooms.update(
                "wakey",
                SpecUpdateRequest.fromMap(Map.of("wake", "mention", "updated_by", "uday"))));

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
                    Map.of(
                        "id",
                        "still",
                        "title",
                        "Still spec",
                        "project",
                        "acme",
                        "created_by",
                        "uday"))));
    var before = rooms.latestRev("still");

    Acting.by(
        ADMIN,
        () ->
            withRooms.update(
                "still",
                SpecUpdateRequest.fromMap(Map.of("title", "Renamed", "updated_by", "uday"))));

    assertEquals(before, rooms.latestRev("still"), "no wake edit, no room write");
  }
}
