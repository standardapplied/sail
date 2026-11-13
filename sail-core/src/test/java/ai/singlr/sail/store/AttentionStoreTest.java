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
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The attention table holds a person, a conversation, a minute and a channel, and nothing else
 * about anyone (M7); a minute is recorded once per tuple, only for a person on a person's own lane
 * (M5); and it goes with the conversation it is of.
 */
class AttentionStoreTest {

  private static final Instant NOON = Instant.parse("2026-11-13T12:00:00Z");

  @TempDir Path tempDir;
  private Sqlite db;
  private AttentionStore attention;
  private Clock clock;

  @BeforeEach
  void setUp() {
    clock = DateTimeUtils.clock;
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    attention = new AttentionStore(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
    DateTimeUtils.clock = clock;
  }

  /** M7: the columns are these four and the key is all of them. */
  @Test
  void theTableHoldsAPersonAConversationAMinuteAndAChannelAndNothingElse() {
    assertEquals(
        List.of("person|1", "conversation|2", "minute|3", "channel|4"),
        db.query(
            "SELECT name, pk FROM pragma_table_info('attention') ORDER BY cid",
            row -> row.text(0) + "|" + row.integer(1)));
  }

  @Test
  void aMinuteIsRecordedOncePerPersonConversationAndChannelAtItsStart() {
    assertTrue(
        attention.record(
            "ada", "auth", NOON.plusSeconds(59).plusMillis(999), AttentionStore.Channel.POST));
    assertFalse(attention.record("ada", "auth", NOON, AttentionStore.Channel.POST));
    assertTrue(attention.record("ada", "auth", NOON, AttentionStore.Channel.FOCUS));
    assertTrue(attention.record("sam", "auth", NOON, AttentionStore.Channel.POST));
    assertTrue(attention.record("ada", "auth", NOON.plusSeconds(60), AttentionStore.Channel.POST));
    assertTrue(attention.record("ada", "other", NOON, AttentionStore.Channel.POST));

    assertEquals(
        List.of(
            new AttentionStore.Minute("ada", "auth", NOON, AttentionStore.Channel.FOCUS),
            new AttentionStore.Minute("ada", "auth", NOON, AttentionStore.Channel.POST),
            new AttentionStore.Minute("sam", "auth", NOON, AttentionStore.Channel.POST),
            new AttentionStore.Minute(
                "ada", "auth", NOON.plusSeconds(60), AttentionStore.Channel.POST)),
        attention.of(List.of("auth")));
  }

  /**
   * M5: the host CLI and an HTTP credential are a person's; a run, a container and sync are not.
   */
  @Test
  void onlyAPersonOnAPersonsOwnLaneIsCounted() {
    DateTimeUtils.clock = Clock.fixed(NOON.plusSeconds(30), ZoneOffset.UTC);
    for (var lane : Actor.Lane.values()) {
      attention.attend(
          new Actor("ada-" + lane, Role.MEMBER, lane), "auth", AttentionStore.Channel.POST);
    }
    attention.attend(
        new Actor(" ", Role.MEMBER, Actor.Lane.API), "auth", AttentionStore.Channel.POST);

    assertEquals(
        List.of("ada-API", "ada-CLI"),
        attention.of(List.of("auth")).stream()
            .map(AttentionStore.Minute::person)
            .sorted()
            .toList());
    assertEquals(NOON, attention.of(List.of("auth")).getFirst().minute());
  }

  @Test
  void anUnknownChannelOnARowIsRefusedByTheTable() {
    assertThrows(
        SqliteException.class,
        () ->
            db.execute(
                "INSERT INTO attention (person, conversation, minute, channel) VALUES"
                    + " ('ada', 'auth', 0, 'reading')"));
  }

  /** A conversation's minutes go with it, when its spec or room is erased. */
  @Test
  void aSpecOrRoomErasedTakesItsMinutesWithIt() {
    attention.record("ada", "auth", NOON, AttentionStore.Channel.POST);
    attention.record("ada", "lobby", NOON, AttentionStore.Channel.POST);
    attention.record("ada", "kept", NOON, AttentionStore.Channel.POST);

    Acting.system(
        () ->
            new Erasure(db)
                .erase(
                    List.of(
                        new Erasure.Target(Erasure.SPEC, "auth"),
                        new Erasure.Target(Erasure.ROOM, "lobby")),
                    "local"));

    assertEquals(
        List.of("kept"),
        attention.of(List.of("auth", "lobby", "kept")).stream()
            .map(AttentionStore.Minute::conversation)
            .toList());
  }
}
