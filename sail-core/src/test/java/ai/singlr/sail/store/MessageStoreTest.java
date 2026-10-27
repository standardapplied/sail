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
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@ActingAs
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

  @ParameterizedTest
  @MethodSource("ai.singlr.sail.store.FixedInstants#textSortsBackwards")
  void messagesMintedInOrderReadInOrderWhateverTheTextOfTheirInstants(
      String earlier, String later) {
    var first = FixedInstants.at(earlier, () -> messages.append("room", "ada", "one", null));
    var second = FixedInstants.at(later, () -> messages.append("room", "ada", "two", null));

    assertEquals(List.of(first.id(), second.id()), ids(messages.list("room", null, 2)));
    assertEquals(List.of(second.id()), ids(messages.listAfter("room", first.id(), 2)));
  }

  @Test
  void aConversationIsReadMessageByMessageByTheProjectEachWasFiledUnder() {
    var specs = new SpecStore(db);
    specs.create(spec("mover", "beta"));
    var asked =
        messages.append(
            "mover", "claude/a1b2c3", "Which, in beta?", null, MessageStore.Mark.QUESTION);
    var beta = messages.append("mover", "bea", "said in beta", null);
    specs.update(spec("mover", "alpha"));
    var first = messages.append("mover", "ann", "said in alpha", null);
    var second =
        messages.append(
            "mover", "claude/a1b2c3", "Which, in alpha?", null, MessageStore.Mark.QUESTION);
    specs.update(spec("mover", "beta"));
    var back = messages.append("mover", "bea", "said in beta again", null);
    var ann = reader("alpha");
    var bea = reader("beta");
    var nobody = new Actor("nan", Role.MEMBER, Actor.Lane.API).withScope(ProjectScope.none());
    var everything = List.of(asked.id(), beta.id(), first.id(), second.id(), back.id());

    assertEquals(everything, ids(messages.list("mover", null, 10)));
    assertEquals(
        List.of(first.id(), second.id()),
        ids(Acting.by(ann, () -> messages.list("mover", null, 2))),
        "the page is as long as its limit of what its reader sees");
    assertEquals(List.of(second.id()), ids(Acting.by(ann, () -> messages.list("mover", null, 1))));
    assertEquals(
        List.of(first.id()), ids(Acting.by(ann, () -> messages.list("mover", second.id(), 2))));
    assertEquals(List.of(), ids(Acting.by(ann, () -> messages.list("mover", first.id(), 2))));
    assertEquals(
        List.of(first.id(), second.id()),
        ids(Acting.by(ann, () -> messages.listAfter("mover", null, 10))));
    assertEquals(
        List.of(first.id(), second.id()),
        ids(Acting.by(ann, () -> messages.listAfter("mover", asked.id(), 10))));
    assertEquals(
        List.of(beta.id(), back.id()),
        ids(Acting.by(bea, () -> messages.listAfter("mover", asked.id(), 2))));
    assertEquals(
        List.of(first.id()),
        ids(Acting.by(ann, () -> messages.listUndelivered("mover", "run", "claude/a1b2c3", 1))));
    assertEquals(
        List.of(beta.id(), back.id()),
        ids(Acting.by(bea, () -> messages.listUndelivered("mover", "run", "claude/a1b2c3", 5))));
    assertEquals(List.of(), ids(Acting.by(nobody, () -> messages.list("mover", null, 10))));

    assertEquals(Map.of(), messages.openQuestions(), "every question was answered by a person");
    assertEquals(
        Map.of("mover", second.id()),
        Acting.by(ann, messages::openQuestions),
        "the answer filed under beta is one alpha's reader does not hold");
    assertEquals(Map.of(), Acting.by(bea, messages::openQuestions));
    assertEquals(Map.of(), Acting.by(nobody, messages::openQuestions));
    assertEquals(Map.of("mover", back.createdAt()), messages.latestByRoom());
    assertEquals(Map.of("mover", second.createdAt()), Acting.by(ann, messages::latestByRoom));
    assertEquals(Map.of("mover", back.createdAt()), Acting.by(bea, messages::latestByRoom));
    assertEquals(Map.of(), Acting.by(nobody, messages::latestByRoom));
    assertEquals(
        everything,
        Acting.by(ann, () -> messages.retiredBefore(DateTimeUtils.now().plusSeconds(60), 10)),
        "retention decides by every message, whoever is bound: no question still waits");
  }

  @Test
  void aReplyAnswersOnlyAMessageItsWriterMayReadAndIsFiledWithIt() {
    var specs = new SpecStore(db);
    specs.create(spec("mover", "beta"));
    var asked = messages.append("mover", "bea", "asked in beta", null);
    specs.update(spec("mover", "alpha"));
    var ann = reader("alpha");
    var nobodys = DateTimeUtils.newId().toString();

    var hidden =
        assertThrows(
            IllegalArgumentException.class,
            () -> Acting.by(ann, () -> messages.append("mover", "ann", "an answer", asked.id())));
    var missing =
        assertThrows(
            IllegalArgumentException.class,
            () -> Acting.by(ann, () -> messages.append("mover", "ann", "an answer", nobodys)));

    assertEquals("reply_to must reference a message in room 'mover'", missing.getMessage());
    assertEquals(missing.getMessage(), hidden.getMessage());
    var answered = messages.append("mover", "bea", "answered after the move", asked.id());
    var further = messages.append("mover", "bea", "and again", answered.id());
    assertEquals("beta", messages.projectOf(answered), "a reply is filed with what it answers");
    assertEquals("beta", messages.projectOf(further));
    assertEquals(List.of(), ids(Acting.by(ann, () -> messages.list("mover", null, 10))));
    var ada = new Actor("ada", Role.MEMBER, Actor.Lane.API).withScope(ann.scope());
    var said = Acting.by(ada, () -> messages.append("mover", "ada", "said in alpha", null));
    var reply = Acting.by(ada, () -> messages.append("mover", "ada", "answered", said.id()));
    assertEquals("alpha", messages.projectOf(reply));
    assertEquals(
        List.of(said.id(), reply.id()),
        ids(Acting.by(ann, () -> messages.list("mover", null, 10))));
  }

  private static Actor reader(String project) {
    return new Actor("ann", Role.MEMBER, Actor.Lane.API)
        .withScope(ProjectScope.of(List.of(project)));
  }

  private static SpecStore.SpecRow spec(String id, String project) {
    return new SpecStore.SpecRow(
        id,
        project,
        "a spec",
        SpecStatus.PENDING,
        "ada",
        null,
        null,
        null,
        null,
        0,
        "ada",
        "",
        "",
        "ada",
        List.of(),
        List.of());
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
  void aBodyThatFitsIsFittedAsItIs() {
    var atTheLimit = "x".repeat(MessageStore.MAX_BODY_BYTES);

    assertEquals("short", MessageStore.fitted("short"));
    assertEquals(atTheLimit, MessageStore.fitted(atTheLimit));
  }

  @Test
  void aBodyTooLongForARoomIsCutBetweenCharactersAndSaysSo() {
    var tooLong = "é".repeat(MessageStore.MAX_BODY_BYTES);

    var fitted = MessageStore.fitted(tooLong);

    assertTrue(fitted.endsWith(MessageStore.CUT), "a reader is told the message goes on");
    assertTrue(fitted.startsWith("ééé"));
    assertFalse(fitted.contains("\uFFFD"), "no character is cut in half");
    var bytes = fitted.getBytes(StandardCharsets.UTF_8).length;
    assertTrue(bytes <= MessageStore.MAX_BODY_BYTES, bytes + " bytes");
    assertTrue(bytes > MessageStore.MAX_BODY_BYTES - 2, "and it keeps all a room message can hold");
    assertEquals(fitted, messages.append("room", "ada", fitted, null).body());
  }

  @Test
  void aBodyWithHalfASurrogatePairInItIsCutAtTheLimitNotAtThatCharacter() {
    var tooLong = "error \uD83D " + "x".repeat(MessageStore.MAX_BODY_BYTES);

    var fitted = MessageStore.fitted(tooLong);

    assertTrue(fitted.endsWith(MessageStore.CUT));
    assertTrue(
        fitted.getBytes(StandardCharsets.UTF_8).length > MessageStore.MAX_BODY_BYTES - 2,
        "a character no encoding has does not end the message where it stands");
    assertTrue(fitted.getBytes(StandardCharsets.UTF_8).length <= MessageStore.MAX_BODY_BYTES);
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
    var runId = runs.create("r1", "acme", "room", "ada", "build", "a", "b", "t", 1, 1, "l", "u");
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
    var question =
        messages.append(
            "room", "claude/run-1", "Which auth flow?", null, MessageStore.Mark.QUESTION);
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

    var first =
        messages.append("room", "claude/run-1", "Which db?", null, MessageStore.Mark.QUESTION);
    assertEquals(Map.of("room", first.id()), messages.openQuestions());

    var second =
        messages.append(
            "room", "claude/run-1", "And which cache?", null, MessageStore.Mark.QUESTION);
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
    messages.append("room", "ada", "what did you ship?", null, MessageStore.Mark.QUESTION);
    assertEquals(Map.of(), messages.openQuestions());
  }

  @Test
  void aLateSyncedMessageWithAnOlderIdIsStillUndelivered() {
    var runs = new RunStore(db);
    var runId = runs.create("r1", "acme", "room", "ada", "build", "a", "b", "t", 1, 1, "l", "u");
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
