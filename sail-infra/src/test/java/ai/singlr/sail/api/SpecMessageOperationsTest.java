/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecMessageOperationsTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private EventBus bus;
  private SailOperations operations;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("messages-api.db"));
    new SchemaManager(db).migrate();
    db.execute(
        """
        INSERT INTO specs
            (id, title, project, assignee, created_by, created_at, updated_at)
        VALUES ('room', 'Room', 'acme', 'ada', 'ada', 'now', 'now')""");
    bus = new EventBus();
    operations =
        new SailOperations(
                new ShellExecutor(false),
                "sail.yaml",
                bus,
                null,
                new SpecStore(db),
                new ReviewStore(db))
            .useMessages(new MessageStore(db));
  }

  @AfterEach
  void tearDown() {
    bus.close();
    db.close();
  }

  @Test
  void postAttributesPublishesAndListsTheMessage() throws Exception {
    var event = new AtomicReference<Event>();
    var delivered = new CountDownLatch(1);
    bus.subscribe(
        BusTesting.latching(
            new EventSubscriber() {
              @Override
              public String name() {
                return "capture";
              }

              @Override
              public Predicate<Event> filter() {
                return ignored -> true;
              }

              @Override
              public void onEvent(Event posted) {
                event.set(posted);
              }
            },
            delivered));

    var posted =
        operations
            .postSpecMessage(
                "room",
                new SpecMessageRequest("Progress\n  update", null, false),
                member("ada"),
                "ada")
            .orThrow();

    assertEquals("ada", posted.message().author());
    BusTesting.awaitDelivery(delivered);
    assertEquals(Event.WellKnownTypes.SPEC_MESSAGE_POSTED, event.get().type());
    assertEquals("acme", event.get().project());
    assertEquals("room", event.get().spec());
    assertEquals(posted.message().id(), event.get().data().get("message_id"));
    assertEquals("Progress update", event.get().data().get("preview"));

    var listed = operations.specMessages("room", null, null, 50).orThrow();
    assertEquals(1, listed.messages().size());
    assertEquals(posted.message().id(), listed.messages().getFirst().id());
  }

  @Test
  void validatesUnknownSpecsBodiesCursorsAndLongPreviews() {
    assertEquals(
        ErrorCode.SPEC_NOT_FOUND,
        operations
            .postSpecMessage(
                "missing", new SpecMessageRequest("body", null, false), member("ada"), "ada")
            .asFailure()
            .errorCode());
    assertEquals(
        ErrorCode.BAD_REQUEST,
        operations
            .postSpecMessage("room", new SpecMessageRequest(" ", null, false), member("ada"), "ada")
            .asFailure()
            .errorCode());
    assertEquals(
        ErrorCode.BAD_REQUEST,
        operations.specMessages("room", "not-a-uuid", null, 50).asFailure().errorCode());
    assertEquals(
        ErrorCode.SPEC_NOT_FOUND,
        operations.specMessages("missing", null, null, 50).asFailure().errorCode());

    var longMessage = "x".repeat(200);
    operations
        .postSpecMessage(
            "room", new SpecMessageRequest(longMessage, null, false), member("ada"), "ada")
        .orThrow();
    assertEquals(1, operations.specMessages("room", null, null, 50).orThrow().messages().size());
  }

  @Test
  void onlyTheSpecOwnerOrAnAdminCanPost() {
    var refused =
        operations
            .postSpecMessage(
                "room",
                new SpecMessageRequest("foreign instruction", null, false),
                member("mallory"),
                "mallory")
            .asFailure();

    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, refused.errorCode());
    assertTrue(operations.specMessages("room", null, null, 50).orThrow().messages().isEmpty());

    var agent = Actor.agentPrincipal("codex/run-1", "ada");
    var posted =
        operations
            .postSpecMessage(
                "room", new SpecMessageRequest("owner update", null, false), agent, agent.handle())
            .orThrow();
    assertEquals(agent.handle(), posted.message().author());
  }

  @Test
  void specListCarriesLastActivityFromMessagesOrUpdatedAt() throws Exception {
    db.execute(
        """
        INSERT INTO specs (id, title, project, assignee, created_by, created_at, updated_at)
        VALUES ('quiet', 'Quiet', 'acme', 'ada', 'ada', '2026-07-01T00:00:00Z',
            '2026-07-02T00:00:00Z')""");
    db.execute("UPDATE specs SET updated_at = '2026-07-01T00:00:00Z' WHERE id = 'room'");
    var posted =
        operations
            .postSpecMessage(
                "room", new SpecMessageRequest("activity", null, false), member("ada"), "ada")
            .orThrow();

    var listed = operations.globalSpecs(new SpecStore.SpecFilter(null, null, null, null, null));
    @SuppressWarnings("unchecked")
    var maps = (List<Map<String, Object>>) listed.orThrow().toMap().get("specs");

    var room = maps.stream().filter(m -> "room".equals(m.get("id"))).findFirst().orElseThrow();
    assertEquals(posted.message().createdAt(), room.get("last_activity_at"));
    var quiet = maps.stream().filter(m -> "quiet".equals(m.get("id"))).findFirst().orElseThrow();
    assertEquals(quiet.get("updated_at"), quiet.get("last_activity_at"));
  }

  @Test
  void messageModelsMapOptionalFieldsAndDefaults() {
    var emptyRequest = SpecMessageRequest.fromMap(Map.of());
    assertEquals(null, emptyRequest.body());
    assertEquals(null, emptyRequest.replyTo());
    assertFalse(emptyRequest.question());
    var request = SpecMessageRequest.fromMap(Map.of("body", 42, "reply_to", 7));
    assertEquals("42", request.body());
    assertEquals("7", request.replyTo());
    assertTrue(SpecMessageRequest.fromMap(Map.of("question", true)).question());
    assertTrue(SpecMessageRequest.fromMap(Map.of("question", "true")).question());
    assertFalse(SpecMessageRequest.fromMap(Map.of("question", "nonsense")).question());

    var view =
        new SpecMessageView("id", "room", "ada", "body", "parent", "2026-07-28T00:00:00Z", false);
    assertEquals("parent", view.toMap().get("reply_to"));
    assertFalse(view.toMap().containsKey("question"));
    var withoutReply =
        new SpecMessageView("id", "room", "ada", "body", null, "2026-07-28T00:00:00Z", false);
    assertFalse(withoutReply.toMap().containsKey("reply_to"));
    var asQuestion =
        new SpecMessageView(
            "id", "room", "claude/r1", "stuck?", null, "2026-07-28T00:00:00Z", true);
    assertEquals(Boolean.TRUE, asQuestion.toMap().get("question"));
    assertTrue(new SpecMessageResponse(view).toMap().containsKey("message"));
    assertEquals(1, new SpecMessagesResponse("room", List.of(view)).toMap().get("total"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void aQuestionPagesListShowAndBoardUntilAHumanReplies() throws Exception {
    var event = new AtomicReference<Event>();
    var delivered = new CountDownLatch(1);
    bus.subscribe(
        BusTesting.latching(
            new EventSubscriber() {
              @Override
              public String name() {
                return "question-capture";
              }

              @Override
              public Predicate<Event> filter() {
                return posted -> Event.WellKnownTypes.SPEC_MESSAGE_POSTED.equals(posted.type());
              }

              @Override
              public void onEvent(Event posted) {
                event.set(posted);
              }
            },
            delivered));
    var agent = Actor.agentPrincipal("claude/run-1", "ada");
    var posted =
        operations
            .postSpecMessage(
                "room", new SpecMessageRequest("Which flow?", null, true), agent, agent.handle())
            .orThrow();

    assertTrue(posted.message().question());
    BusTesting.awaitDelivery(delivered);
    assertEquals(Boolean.TRUE, event.get().data().get("question"));

    var listed =
        operations.globalSpecs(new SpecStore.SpecFilter(null, null, null, null, null)).orThrow();
    var maps = (List<Map<String, Object>>) listed.toMap().get("specs");
    var room = maps.stream().filter(m -> "room".equals(m.get("id"))).findFirst().orElseThrow();
    assertEquals(Boolean.TRUE, room.get("needs_reply"));
    assertEquals(posted.message().id(), room.get("question_message_id"));

    var shown = (Map<String, Object>) operations.globalSpec("room").orThrow().toMap().get("spec");
    assertEquals(Boolean.TRUE, shown.get("needs_reply"));
    assertEquals(posted.message().id(), shown.get("question_message_id"));
    assertEquals(1, operations.globalBoard("acme").orThrow().toMap().get("needs_reply"));

    operations
        .postSpecMessage(
            "room", new SpecMessageRequest("use PKCE", null, false), member("ada"), "ada")
        .orThrow();

    var answeredList =
        operations.globalSpecs(new SpecStore.SpecFilter(null, null, null, null, null)).orThrow();
    var answeredMaps = (List<Map<String, Object>>) answeredList.toMap().get("specs");
    var answeredRoom =
        answeredMaps.stream().filter(m -> "room".equals(m.get("id"))).findFirst().orElseThrow();
    assertFalse(answeredRoom.containsKey("needs_reply"));
    var answeredShown =
        (Map<String, Object>) operations.globalSpec("room").orThrow().toMap().get("spec");
    assertFalse(answeredShown.containsKey("needs_reply"));
    assertEquals(0, operations.globalBoard("acme").orThrow().toMap().get("needs_reply"));
  }

  private static Actor member(String handle) {
    return new Actor(handle, Role.MEMBER, Actor.Lane.API);
  }
}
