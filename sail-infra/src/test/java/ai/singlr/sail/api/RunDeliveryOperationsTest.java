/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The run-delivery lane on real stores: the inbox is the spec room minus what the run was already
 * shown and what it said itself, tracked by exact message identity and scoped by the run's own spec
 * — so a message that synchronizes in late is still delivered, whatever its id.
 */
class RunDeliveryOperationsTest {

  private static final Actor ADA = new Actor("ada", Role.MEMBER, Actor.Lane.CLI);

  @TempDir Path tempDir;
  private Sqlite db;
  private EventBus bus;
  private RunStore runStore;
  private MessageStore messages;
  private SailOperations operations;
  private String runId;
  private String principal;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("run-delivery.db"));
    new SchemaManager(db).migrate();
    db.execute(
        """
        INSERT INTO specs
            (id, title, project, assignee, created_by, created_at, updated_at)
        VALUES ('room', 'Room', 'acme', 'ada', 'ada', 'now', 'now')""");
    db.execute(
        """
        INSERT INTO specs
            (id, title, project, assignee, created_by, created_at, updated_at)
        VALUES ('other', 'Other', 'acme', 'ada', 'ada', 'now', 'now')""");
    bus = new EventBus();
    runStore = new RunStore(db);
    messages = new MessageStore(db);
    operations =
        new SailOperations(
                new ShellExecutor(false),
                "sail.yaml",
                bus,
                null,
                new SpecStore(db),
                new ReviewStore(db),
                runStore,
                new ProjectStore(db),
                SyncScheduler.disabled(),
                null,
                SessionYield.NONE)
            .useMessages(messages);
    runId = newRun("room");
    principal = runStore.findById(runId).orElseThrow().principal();
  }

  @AfterEach
  void tearDown() {
    bus.close();
    db.close();
  }

  private String newRun(String specId) {
    var id = DateTimeUtils.newId().toString();
    return runStore.create(
        id,
        "acme",
        specId,
        "node-a",
        "ada",
        "build",
        "claude-code",
        "b",
        "t",
        null,
        null,
        "l",
        "u");
  }

  @Test
  void inboxListsUndeliveredForeignMessagesOnly() {
    var fromAda = messages.append("room", "ada", "please rename the flag", null);
    messages.append("room", principal, "on it", null);

    var inbox = operations.runInbox(runId).orThrow();

    assertEquals(runId, inbox.runId());
    assertEquals("room", inbox.specId());
    assertEquals(List.of(fromAda.id()), inbox.messages().stream().map(m -> m.id()).toList());
    assertFalse(inbox.hasMore());

    var map = inbox.toMap();
    assertEquals(runId, map.get("run_id"));
    assertEquals("room", map.get("spec_id"));
    assertEquals(false, map.get("has_more"));
  }

  @Test
  void anEmptyRoomYieldsAnEmptyInbox() {
    var inbox = operations.runInbox(runId).orThrow();

    assertTrue(inbox.messages().isEmpty());
    assertFalse(inbox.hasMore());
  }

  @Test
  void aCappedInboxReportsThatMoreRemainAndLaterPagesFollowAcknowledgement() {
    for (var i = 0; i < 21; i++) {
      messages.append("room", "ada", "message " + i, null);
    }

    var first = operations.runInbox(runId).orThrow();
    assertEquals(20, first.messages().size());
    assertTrue(first.hasMore(), "the 21st message must be visible as more-to-read, never lost");

    operations.ackRunMessages(runId, first.messages().stream().map(m -> m.id()).toList()).orThrow();

    var second = operations.runInbox(runId).orThrow();
    assertEquals(1, second.messages().size());
    assertFalse(second.hasMore());
    assertEquals("message 20", second.messages().getFirst().body());
  }

  @Test
  void aMessageSyncingInWithAnOlderIdIsStillDelivered() {
    var oldId = DateTimeUtils.newId().toString();
    var newer = messages.append("room", "ada", "delivered first", null);
    operations.ackRunMessages(runId, List.of(newer.id())).orThrow();
    assertTrue(operations.runInbox(runId).orThrow().messages().isEmpty());

    messages.applyRevision(
        oldId,
        java.util.Map.of(
            "spec_id", "room",
            "author", "ada",
            "body", "minted before, synced after",
            "created_at", "now"),
        "1-abc");

    var inbox = operations.runInbox(runId).orThrow();
    assertEquals(
        List.of(oldId),
        inbox.messages().stream().map(m -> m.id()).toList(),
        "delivery is identity, not id order: a late-synced older message is still owed a reading");
  }

  @Test
  void aRunWithoutASpecHasAnEmptyInbox() {
    var adhoc =
        runStore.create(
            DateTimeUtils.newId().toString(),
            "acme",
            "",
            "node-a",
            "ada",
            "adhoc",
            "claude-code",
            null,
            "t",
            null,
            null,
            "l",
            "u");

    var inbox = operations.runInbox(adhoc).orThrow();

    assertTrue(inbox.messages().isEmpty());
    assertNull(inbox.specId());
    assertFalse(inbox.toMap().containsKey("spec_id"));
  }

  @Test
  void anUnknownRunIsRefused() {
    assertEquals(
        ErrorCode.RUN_NOT_FOUND, operations.runInbox("missing-run").asFailure().errorCode());
    assertEquals(
        ErrorCode.RUN_NOT_FOUND,
        operations.ackRunMessages("missing-run", List.of("x")).asFailure().errorCode());
  }

  @Test
  void acknowledgingExactIdsEmptiesTheInboxAndReplaysAreNoOps() {
    var first = messages.append("room", "ada", "one", null);
    var second = messages.append("room", "ada", "two", null);

    var acked = operations.ackRunMessages(runId, List.of(first.id(), second.id())).orThrow();
    assertEquals(2, acked.acked());
    assertEquals(2, acked.toMap().get("acked"));

    assertTrue(operations.runInbox(runId).orThrow().messages().isEmpty());

    var replay = operations.ackRunMessages(runId, List.of(first.id())).orThrow();
    assertEquals(1, replay.acked(), "a replayed ack is an idempotent no-op, never an error");
    assertTrue(operations.runInbox(runId).orThrow().messages().isEmpty());
  }

  @Test
  void theAcknowledgementOnlyAcceptsMessagesOnTheRunsOwnSpec() {
    var foreign = messages.append("other", "ada", "wrong room", null);
    var own = messages.append("room", "ada", "right room", null);

    assertEquals(
        ErrorCode.BAD_REQUEST,
        operations.ackRunMessages(runId, List.of(foreign.id())).asFailure().errorCode());
    assertEquals(
        ErrorCode.BAD_REQUEST,
        operations.ackRunMessages(runId, List.of(own.id(), foreign.id())).asFailure().errorCode(),
        "one off-spec id refuses the whole batch");
    assertEquals(
        ErrorCode.BAD_REQUEST, operations.ackRunMessages(runId, List.of()).asFailure().errorCode());
    assertEquals(
        ErrorCode.BAD_REQUEST,
        operations.ackRunMessages(runId, List.of("not-a-message")).asFailure().errorCode());
    assertEquals(
        1,
        operations.runInbox(runId).orThrow().messages().size(),
        "a refused batch marks nothing delivered");
  }

  @Test
  void recordRunSessionPersistsTheReportedConversationIdentity() {
    var recorded =
        operations.recordRunSession(runId, "abc-123", "startup", "/t/abc.jsonl").orThrow();

    assertEquals(runId, recorded.runId());
    assertEquals("abc-123", recorded.sessionId());
    assertEquals("startup", recorded.sessionSource());
    var map = recorded.toMap();
    assertEquals(runId, map.get("run_id"));
    assertEquals("abc-123", map.get("session_id"));
    assertEquals("startup", map.get("session_source"));

    var run = runStore.findById(runId).orElseThrow();
    assertEquals("abc-123", run.sessionId());
    assertEquals("startup", run.sessionSource());
    assertEquals("/t/abc.jsonl", run.transcriptPath());
  }

  @Test
  void blankOptionalFieldsAreStoredAsNullNeverAsEmptyStrings() {
    var recorded = operations.recordRunSession(runId, "  abc-123  ", " ", "").orThrow();

    assertEquals("abc-123", recorded.sessionId());
    assertNull(recorded.sessionSource());
    assertFalse(recorded.toMap().containsKey("session_source"));
    var run = runStore.findById(runId).orElseThrow();
    assertNull(run.sessionSource());
    assertNull(run.transcriptPath());
  }

  @Test
  void aBlankSessionIdIsRefusedWithoutErasingAPriorReport() {
    operations.recordRunSession(runId, "abc-123", "startup", "/t/abc.jsonl").orThrow();

    assertEquals(
        ErrorCode.BAD_REQUEST,
        operations.recordRunSession(runId, " ", "clear", null).asFailure().errorCode());
    assertEquals(
        ErrorCode.BAD_REQUEST,
        operations.recordRunSession(runId, null, null, null).asFailure().errorCode());

    var run = runStore.findById(runId).orElseThrow();
    assertEquals("abc-123", run.sessionId(), "a refused report never erases the prior one");
    assertEquals("startup", run.sessionSource());
  }

  @Test
  void recordRoomConversationLandsARecordClassEventInTheRoom() throws Exception {
    var seen = new java.util.concurrent.atomic.AtomicReference<Event>();
    var latch = new java.util.concurrent.CountDownLatch(1);
    var subscription =
        bus.subscribe(
            BusTesting.latching(
                new EventSubscriber() {
                  @Override
                  public String name() {
                    return "capture";
                  }

                  @Override
                  public java.util.function.Predicate<Event> filter() {
                    return e -> true;
                  }

                  @Override
                  public void onEvent(Event event) {
                    seen.set(event);
                  }
                },
                latch));

    var recorded =
        operations
            .recordRoomConversation(
                "room", " claude-code ", " abc-123 ", "startup", "/t/abc.jsonl", ADA)
            .orThrow();

    assertEquals("room", recorded.roomId());
    assertEquals("abc-123", recorded.sessionId());
    assertEquals("claude-code", recorded.agent());
    assertEquals("claude-code", recorded.toMap().get("agent"));
    assertEquals("room", recorded.toMap().get("room_id"));
    BusTesting.awaitDelivery(latch);
    var event = seen.get();
    assertEquals(Event.WellKnownTypes.AGENT_CONVERSATION_STARTED, event.type());
    assertEquals(Event.RetentionClass.RECORD, Event.WellKnownTypes.retentionClass(event.type()));
    assertEquals("acme", event.project(), "the room's project scopes the event");
    assertEquals("room", event.spec(), "scoped by the room id, like a room message");
    assertEquals("ada", event.agent(), "authored by the FDE behind the box credential");
    assertEquals("abc-123", event.data().get("session_id"));
    assertEquals("claude-code", event.data().get("agent"));
    assertEquals("startup", event.data().get("session_source"));
    assertEquals("/t/abc.jsonl", event.data().get("transcript_path"));
    assertNull(runStore.findById(runId).orElseThrow().sessionId(), "no run row is touched");
    subscription.close();
  }

  @Test
  void recordRoomConversationOmitsBlankOptionalsAndRefusesBadInput() {
    var bare = operations.recordRoomConversation("room", " ", "abc", null, "", ADA).orThrow();
    assertNull(bare.agent());
    assertFalse(bare.toMap().containsKey("agent"));

    assertEquals(
        ErrorCode.BAD_REQUEST,
        operations
            .recordRoomConversation("room", "claude-code", " ", null, null, ADA)
            .asFailure()
            .errorCode());
    assertEquals(
        ErrorCode.ROOM_NOT_FOUND,
        operations
            .recordRoomConversation("nowhere", "claude-code", "abc", null, null, ADA)
            .asFailure()
            .errorCode());
  }

  @Test
  void recordRoomConversationTakesTheRoomsPostGateSoABoxCannotForgeIntoForeignRooms() {
    var mallory = new Actor("mallory", Role.MEMBER, Actor.Lane.CLI);
    assertEquals(
        ErrorCode.FORBIDDEN_NOT_ASSIGNEE,
        operations
            .recordRoomConversation("room", "claude-code", "abc", null, null, mallory)
            .asFailure()
            .errorCode(),
        "a member who does not own the room cannot author a conversation into it");
    var viewer = new Actor("ada", Role.VIEWER, Actor.Lane.CLI);
    assertEquals(
        ErrorCode.READ_ONLY_CREDENTIAL,
        operations
            .recordRoomConversation("room", "claude-code", "abc", null, null, viewer)
            .asFailure()
            .errorCode(),
        "a read-only credential writes nothing, even into its own room");
    var admin = new Actor("ops", Role.ADMIN, Actor.Lane.CLI);
    assertEquals(
        "room",
        operations
            .recordRoomConversation("room", "claude-code", "abc", null, null, admin)
            .orThrow()
            .roomId(),
        "an admin passes the same gate a room message takes");
  }

  @Test
  void recordRunSessionRefusesAnUnknownRun() {
    assertEquals(
        ErrorCode.RUN_NOT_FOUND,
        operations.recordRunSession("missing-run", "abc", "startup", null).asFailure().errorCode());
  }

  @Test
  void afterReadsForwardAndExcludesBothCursorsAtOnce() {
    var first = messages.append("room", "ada", "one", null);
    var second = messages.append("room", "ada", "two", null);

    var page = operations.roomMessages("room", null, first.id(), 50).orThrow();
    assertEquals(List.of(second.id()), page.messages().stream().map(m -> m.id()).toList());

    assertEquals(
        ErrorCode.BAD_REQUEST,
        operations.roomMessages("room", first.id(), second.id(), 50).asFailure().errorCode(),
        "before and after are exclusive");
    assertEquals(
        ErrorCode.BAD_REQUEST,
        operations.roomMessages("room", null, "not-a-uuid", 50).asFailure().errorCode());
  }
}
