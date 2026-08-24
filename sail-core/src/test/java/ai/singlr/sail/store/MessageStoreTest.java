/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.YamlUtil;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessageStoreTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private MessageStore messages;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("messages.db"));
    new SchemaManager(db).migrate();
    db.execute(
        """
        INSERT INTO rooms (id, title, project, created_at, updated_at)
        VALUES ('room', 'Room', 'acme', 'now', 'now')""");
    messages = new MessageStore(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void appendPersistsAnImmutableRevisionAndReply() {
    var first = messages.append("room", "ada", "First", null);
    var reply = messages.append("room", "codex/a1b2c3", "Reply", first.id());

    assertEquals(first.id(), reply.replyTo());
    assertEquals("codex/a1b2c3", messages.findById(reply.id()).orElseThrow().author());
    var history = new ChangeLog(db).history("message", reply.id());
    assertEquals(1, history.size());
    assertEquals(reply.rev(), history.getFirst().rev());
    assertEquals("Reply", YamlUtil.parseMap(history.getFirst().snapshot()).get("body"));
  }

  @Test
  void pagesNewestLastWithAnExclusiveBeforeCursor() {
    var first = messages.append("room", "ada", "one", null);
    var second = messages.append("room", "ada", "two", null);
    var third = messages.append("room", "ada", "three", null);

    assertEquals(List.of(second.id(), third.id()), ids(messages.list("room", null, 2)));
    assertEquals(List.of(first.id()), ids(messages.list("room", second.id(), 2)));
    assertEquals(List.of(first.id(), second.id(), third.id()), ids(messages.list("room", null, 3)));
    assertTrue(messages.list("missing", null, 10).isEmpty());
  }

  @Test
  void rejectsEmptyOversizedAndInvalidRepliesBeforeWriting() {
    assertThrows(IllegalArgumentException.class, () -> messages.append("room", "ada", " \n", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> messages.append("room", "ada", "x".repeat(MessageStore.MAX_BODY_BYTES + 1), null));
    assertThrows(IllegalArgumentException.class, () -> messages.append("room", "", "body", null));
    assertThrows(
        IllegalArgumentException.class, () -> messages.append("room", "ada", "body", "not-a-uuid"));
    assertThrows(
        IllegalArgumentException.class,
        () -> messages.append("room", "ada", "body", "00000000-0000-0000-0000-000000000000"));
    assertThrows(IllegalArgumentException.class, () -> messages.list("room", null, 0));
    assertThrows(IllegalArgumentException.class, () -> messages.list("room", "not-a-uuid", 1));
    assertTrue(messages.list("room", null, 10).isEmpty());
  }

  @Test
  void latestBySpecReportsEachRoomsNewestMessageTime() {
    assertEquals(Map.of(), messages.latestByRoom());

    messages.append("room", "uday", "first", null);
    var second = messages.append("room", "uday", "second", null);
    db.execute(
        """
        INSERT INTO rooms (id, title, project, created_at, updated_at)
        VALUES ('other', 'Other', 'acme', 'now', 'now')""");
    var other = messages.append("other", "uday", "solo", null);

    assertEquals(
        Map.of("room", second.createdAt(), "other", other.createdAt()), messages.latestByRoom());
  }

  @Test
  void acceptsTheExactByteCapAndSurvivesRoomRowChanges() {
    var row = messages.append("room", "ada", "x".repeat(MessageStore.MAX_BODY_BYTES), null);
    db.execute("UPDATE rooms SET title = 'Renamed', assignee = 'ada' WHERE id = 'room'");
    assertEquals(row.id(), messages.list("room", null, 10).getFirst().id());

    db.execute("DELETE FROM rooms WHERE id = 'room'");
    assertEquals(row.id(), messages.findById(row.id()).orElseThrow().id());
    db.execute(
        """
        INSERT INTO rooms (id, title, project, created_at, updated_at)
        VALUES ('room', 'Room restored', 'acme', 'later', 'later')""");
    assertEquals(row.id(), messages.list("room", null, 10).getFirst().id());
  }

  private static List<String> ids(List<MessageStore.MessageRow> rows) {
    return rows.stream().map(MessageStore.MessageRow::id).toList();
  }

  @Test
  void listAfterReadsForwardPastACursorInMintOrder() {
    var first = messages.append("room", "ada", "one", null);
    var second = messages.append("room", "ada", "two", null);
    var third = messages.append("room", "ada", "three", null);

    assertEquals(
        List.of(first.id(), second.id(), third.id()), ids(messages.listAfter("room", null, 10)));
    assertEquals(List.of(second.id(), third.id()), ids(messages.listAfter("room", first.id(), 10)));
    assertEquals(List.of(second.id()), ids(messages.listAfter("room", first.id(), 1)));
    assertTrue(messages.listAfter("room", third.id(), 10).isEmpty());
    assertTrue(messages.listAfter("missing", null, 10).isEmpty());
    assertThrows(
        IllegalArgumentException.class, () -> messages.listAfter("room", "not-a-uuid", 10));
    assertThrows(IllegalArgumentException.class, () -> messages.listAfter("room", null, 0));
  }

  @Test
  void newestIdNamesTheRoomsLatestMessage() {
    assertTrue(messages.newestId("room").isEmpty());
    messages.append("room", "ada", "one", null);
    var latest = messages.append("room", "ada", "two", null);
    assertEquals(latest.id(), messages.newestId("room").orElseThrow());
  }

  @Test
  void listUndeliveredExcludesLedgeredAndOwnMessagesByExactIdentity() {
    var runs = new RunStore(db);
    var runId =
        runs.create("r1", "acme", "room", "n", "ada", "build", "a", "b", "t", 1, 1, "l", "u");
    var seen = messages.append("room", "ada", "seen", null);
    var fresh = messages.append("room", "ada", "fresh", null);
    var own = messages.append("room", "claude/r1", "my own note", null);
    runs.markDelivered(runId, List.of(seen.id()));

    assertEquals(
        List.of(fresh.id()), ids(messages.listUndelivered("room", runId, "claude/r1", 10)));
    assertEquals(
        List.of(seen.id(), fresh.id()),
        ids(messages.listUndelivered("room", "other-run", "claude/r1", 10)),
        "the ledger is per run");
    assertTrue(
        ids(messages.listUndelivered("room", runId, "claude/r1", 10)).stream()
            .noneMatch(own.id()::equals),
        "a run is never told its own story");
    assertThrows(
        IllegalArgumentException.class, () -> messages.listUndelivered("room", runId, "x", 0));
  }

  @Test
  void questionFlagRoundTripsAndStaysOutOfPlainSnapshots() {
    var question = messages.append("room", "claude/run-1", "Which auth flow?", null, true);
    assertTrue(question.question());
    assertTrue(messages.findById(question.id()).orElseThrow().question());
    assertEquals(Boolean.TRUE, messages.comparableSnapshot(question.id()).get("question"));

    var plain = messages.append("room", "claude/run-1", "progress", null);
    assertFalse(plain.question());
    assertFalse(messages.comparableSnapshot(plain.id()).containsKey("question"));
  }

  @Test
  void openQuestionsFlagsTheLatestAgentQuestionUntilAHumanReplies() {
    assertEquals(Map.of(), messages.openQuestions());

    var first = messages.append("room", "claude/run-1", "Which db?", null, true);
    assertEquals(Map.of("room", first.id()), messages.openQuestions());

    var second = messages.append("room", "claude/run-1", "And which cache?", null, true);
    assertEquals(Map.of("room", second.id()), messages.openQuestions());

    messages.append("room", "codex/run-2", "agent chatter", null);
    messages.append("room", "sail", "Review passed.", null);
    assertEquals(
        Map.of("room", second.id()),
        messages.openQuestions(),
        "neither an agent nor the orchestrator answers a question");

    messages.append("room", "ada", "use redis and postgres", null);
    assertEquals(Map.of(), messages.openQuestions());
  }

  @Test
  void aHumanQuestionNeverNeedsAReply() {
    messages.append("room", "ada", "what did you ship?", null, true);
    assertEquals(Map.of(), messages.openQuestions());
  }

  @Test
  void aLateSyncedMessageWithAnOlderIdIsStillUndelivered() {
    var runs = new RunStore(db);
    var runId =
        runs.create("r1", "acme", "room", "n", "ada", "build", "a", "b", "t", 1, 1, "l", "u");
    var lateId = DateTimeUtils.newId().toString();
    var newer = messages.append("room", "ada", "delivered first", null);
    runs.markDelivered(runId, List.of(newer.id()));

    messages.applyRevision(
        lateId,
        Map.of("spec_id", "room", "author", "uday", "body", "late arrival", "created_at", "now"),
        "1-abc");

    assertEquals(
        List.of(lateId),
        ids(messages.listUndelivered("room", runId, "claude/r1", 10)),
        "delivery is identity, not id order: an older id syncing in late is still owed delivery");
  }
}
