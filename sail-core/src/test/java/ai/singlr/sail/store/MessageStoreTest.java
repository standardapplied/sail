/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        INSERT INTO specs (id, title, project, created_at, updated_at)
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
    assertEquals(Map.of(), messages.latestBySpec());

    messages.append("room", "uday", "first", null);
    var second = messages.append("room", "uday", "second", null);
    db.execute(
        """
        INSERT INTO specs (id, title, project, created_at, updated_at)
        VALUES ('other', 'Other', 'acme', 'now', 'now')""");
    var other = messages.append("other", "uday", "solo", null);

    assertEquals(
        Map.of("room", second.createdAt(), "other", other.createdAt()), messages.latestBySpec());
  }

  @Test
  void acceptsTheExactByteCapAndSurvivesSpecLifecycleChanges() {
    var row = messages.append("room", "ada", "x".repeat(MessageStore.MAX_BODY_BYTES), null);
    db.execute("UPDATE specs SET status = 'archived' WHERE id = 'room'");
    assertEquals(row.id(), messages.list("room", null, 10).getFirst().id());

    db.execute("DELETE FROM specs WHERE id = 'room'");
    assertEquals(row.id(), messages.findById(row.id()).orElseThrow().id());
    db.execute(
        """
        INSERT INTO specs (id, title, project, created_at, updated_at)
        VALUES ('room', 'Room restored', 'acme', 'later', 'later')""");
    assertEquals(row.id(), messages.list("room", null, 10).getFirst().id());
  }

  private static List<String> ids(List<MessageStore.MessageRow> rows) {
    return rows.stream().map(MessageStore.MessageRow::id).toList();
  }
}
