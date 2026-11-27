/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A box's chats and their logs: numbered in order, read from where a reader left off, the person's
 * messages not yet handed found by the watermark, and erased with their person, their workspace,
 * their project, or their retention.
 */
@ActingAs(value = Actor.Lane.CLI, handle = "uday")
class ChatStoreTest {

  private static final Instant AT = Instant.parse("2026-11-27T09:00:00Z");

  private Sqlite db;
  private ChatStore chats;
  private String sam;
  private String tom;

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "acme", "beta");
    chats = new ChatStore(db);
    var fdes = new FdeStore(db);
    Acting.system(() -> fdes.add("uday", "Uday", null, "admin"));
    sam = Acting.system(() -> fdes.add("sam", "Sam", null, "member")).id();
    tom = Acting.system(() -> fdes.add("tom", "Tom", null, "member")).id();
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private void open(String id, String opener, String project, String workspace) {
    chats.open(
        new ChatStore.Chat(
            id,
            opener,
            project,
            workspace,
            "claude-code",
            "default",
            false,
            null,
            false,
            false,
            0,
            AT,
            null));
  }

  @Test
  void itemsAreNumberedInTheOrderTheyAreLoggedAndReadAfterASeq() {
    open("c1", sam, "acme", "try");
    open("c2", sam, "acme", "try");

    var one = chats.append("c1", "message", "{\"text\":\"hi\"}", AT);
    var other = chats.append("c2", "message", "{}", AT);
    var two = chats.append("c1", "session", "{}", AT.plusSeconds(1));
    var three = chats.append("c1", "text", "{}", AT.plusSeconds(2));

    assertEquals(List.of(1L, 1L, 2L, 3L), List.of(one, other, two, three));
    assertEquals(
        List.of(
            new ChatStore.Item(2, "session", "{}", AT.plusSeconds(1)),
            new ChatStore.Item(3, "text", "{}", AT.plusSeconds(2))),
        chats.after("c1", 1, 10));
    assertEquals(1, chats.after("c1", 0, 1).size(), "at most a page");
  }

  @Test
  void anItemOfAChatThatIsGoneIsNotLogged() {
    assertEquals(0, chats.append("gone", "text", "{}", AT));
    assertEquals(List.of(), chats.after("gone", 0, 10));
  }

  @Test
  void theMessagesNotYetHandedAreThoseAfterTheWatermark() {
    open("c1", sam, "acme", "try");
    chats.append("c1", "message", "{\"text\":\"one\"}", AT);
    chats.append("c1", "turn_ended", "{}", AT);
    chats.append("c1", "message", "{\"text\":\"two\"}", AT);

    chats.handed("c1", 1);

    assertEquals(
        List.of(new ChatStore.Item(3, "message", "{\"text\":\"two\"}", AT)),
        chats.unhanded("c1", "message"));
  }

  @Test
  void whatTheHarnessSaidIsKeptAndWhatItLeftUnsaidIsNotLost() {
    open("c1", sam, "acme", "try");

    chats.said("c1", "conv-1", "plan");
    chats.said("c1", null, null);
    chats.turn("c1", true);

    var chat = chats.find("c1").orElseThrow();
    assertEquals(
        List.of("conv-1", "plan", true),
        List.of(chat.conversation(), chat.mode(), chat.turnOpen()));
  }

  /**
   * An end is kept once, and leaves the turn it finds open for the cut of its process to close,
   * which says why: an end alone says nothing of the turn.
   */
  @Test
  void anEndIsKeptOnceAndLeavesItsTurnToItsProcesssCut() {
    open("c1", sam, "acme", "try");
    chats.turn("c1", true);

    chats.ended("c1", AT);
    chats.ended("c1", AT.plusSeconds(5));

    var chat = chats.find("c1").orElseThrow();
    assertTrue(chat.ended());
    assertEquals(AT, chat.endedAt());
    assertTrue(chat.turnOpen());
  }

  /**
   * I2, I8: a chat a process was started for is said to be until its process is known gone, so a
   * later sail finds what one before it left; an ended chat's is found as well.
   */
  @Test
  void aChatWhoseProcessIsNotKnownGoneIsFoundByALaterSail() {
    open("c1", sam, "acme", "try");
    open("c2", sam, "acme", "try");
    open("c3", sam, "acme", "try");
    chats.launched("c1", true);
    chats.launched("c2", true);
    chats.launched("c2", false);
    chats.launched("c3", true);
    chats.ended("c3", AT);

    assertEquals(List.of("c1", "c3"), chats.launched());
    assertTrue(chats.find("c1").orElseThrow().launched());
    assertFalse(chats.find("c2").orElseThrow().launched());
  }

  /**
   * I5: the chats whose messages wait to be handed, which a later sail delivers; never an ended
   * one.
   */
  @Test
  void theChatsWithMessagesWaitingAreFoundAndNoEndedOne() {
    open("waits", sam, "acme", "try");
    open("handed", sam, "acme", "try");
    open("ended", sam, "acme", "try");
    for (var id : List.of("waits", "handed", "ended")) {
      chats.append(id, "message", "{}", AT);
      chats.append(id, "turn_ended", "{}", AT);
    }
    chats.handed("handed", 1);
    chats.ended("ended", AT);

    assertEquals(List.of("waits"), chats.queued("message"));
  }

  /**
   * I3: an item is small by the protocol's own bounds, so the store refuses one past its bound,
   * which every reader of an item parses within: what is logged can always be read back.
   */
  @Test
  void anItemPastItsBoundIsRefusedSoEveryItemLoggedCanBeReadBack() {
    open("c1", sam, "acme", "try");
    var largest = "\"" + "a".repeat(ChatStore.MAX_PAYLOAD_CHARS - 2) + "\"";

    var refused =
        assertThrows(
            IllegalArgumentException.class, () -> chats.append("c1", "text", largest + " ", AT));
    chats.append("c1", "text", largest, AT);

    assertEquals(
        "An item of chat c1 is "
            + (ChatStore.MAX_PAYLOAD_CHARS + 1)
            + " characters, past the "
            + ChatStore.MAX_PAYLOAD_CHARS
            + " any item can be.",
        refused.getMessage());
    assertEquals(1, chats.after("c1", 0, 10).size());
  }

  /** I3: what is logged together is logged whole or not at all. */
  @Test
  void entriesLoggedTogetherAreAllLoggedOrNone() {
    open("c1", sam, "acme", "try");
    chats.turn("c1", true);

    assertThrows(
        IllegalStateException.class,
        () ->
            chats.together(
                () -> {
                  chats.append("c1", "told", "{}", AT);
                  chats.turn("c1", false);
                  throw new IllegalStateException("crashed mid-cut");
                }));

    assertEquals(List.of(), chats.after("c1", 0, 10));
    assertTrue(chats.find("c1").orElseThrow().turnOpen());
  }

  /** I11: a person's chats go with them. */
  @Test
  void removingAPersonErasesTheirChatsAndTheirLogs() {
    open("c1", sam, "acme", "try");
    open("c2", tom, "acme", "tom");
    chats.append("c1", "message", "{}", AT);

    Acting.system(() -> new FdeStore(db).remove(sam));

    assertTrue(chats.find("c1").isEmpty());
    assertEquals(List.of(), chats.after("c1", 0, 10));
    assertTrue(chats.find("c2").isPresent());
  }

  /** I11: a workspace's chats go with it, and a project's with it. */
  @Test
  void deletingAWorkspaceOrErasingAProjectErasesItsChats() {
    open("c1", sam, "acme", "try");
    open("c2", sam, "acme", "pier");
    open("c3", sam, "beta", "try");
    open("c4", tom, "acme", "tom");
    chats.append("c1", "message", "{}", AT);

    chats.eraseWorkspace("acme", "try");
    new Erasure(db).erase(List.of(new Erasure.Target(Erasure.PROJECT, "beta")), "local");

    assertEquals(
        List.of(false, true, false, true),
        List.of("c1", "c2", "c3", "c4").stream().map(id -> chats.find(id).isPresent()).toList());
    assertEquals(List.of(), chats.after("c1", 0, 10));
    chats.eraseProject("acme");
    assertTrue(chats.find("c2").isEmpty());
  }

  @Test
  void aChatThatEndedIsErasedOnceItsRetentionPassedAndAnOpenOneNever() {
    open("old", sam, "acme", "try");
    open("recent", sam, "acme", "try");
    open("open", sam, "acme", "try");
    chats.ended("old", AT);
    chats.ended("recent", AT.plus(Duration.ofDays(2)));

    var erased = chats.expire(AT.plus(Duration.ofDays(1)));

    assertEquals(1, erased);
    assertEquals(
        List.of(false, true, true),
        List.of("old", "recent", "open").stream().map(id -> chats.find(id).isPresent()).toList());
  }
}
