/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SpecStore;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** E1 / blob audit over a real session. */
class ErasureAuditTest {

  private static final Actor ADA_ADMIN = Actor.sync("ada", Role.ADMIN);
  private static final Actor ADA = Actor.sync("ada", Role.MEMBER);
  private static final Actor BOB = Actor.sync("bob", Role.MEMBER);

  private SyncBox main;
  private SyncBox ada;
  private SyncBox bob;

  @BeforeEach
  void setUp() {
    main = new SyncBox("main");
    ada = new SyncBox("ada");
    bob = new SyncBox("bob");
  }

  @AfterEach
  void tearDown() {
    bob.close();
    ada.close();
    main.close();
  }

  private List<SyncSession.TypeReport> sync(SyncBox box, Actor as) {
    return SyncBox.round(main, box.syncsAs(as));
  }

  private void assertReHomedAndLanded(SyncBox box, Actor as) {
    SyncBox.assertConvergedWithin(3, main, box.syncsAs(as));
    assertEquals("child", main.specs.findById("child").orElseThrow().roomIdOrIdentity());
    assertTrue(new ChangeLog(main.db).isErased(Erasure.ROOM, "old"), "the room stays erased");
    assertTrue(new ChangeLog(box.db).isErased(Erasure.ROOM, "old"));
  }

  private static SpecStore.SpecRow spec(String id, SpecStatus status, String owner) {
    return new SpecStore.SpecRow(
        id,
        "acme",
        "Spec " + id,
        status,
        owner,
        null,
        null,
        null,
        null,
        0,
        null,
        "",
        "",
        null,
        List.of(),
        List.of());
  }

  private void seedArchivedSpecWithRoom() {
    Acting.as(
        "ada",
        () -> {
          main.specs.create(spec("old", SpecStatus.ARCHIVED, "ada"));
          new RoomStore(main.db)
              .create(
                  new RoomStore.RoomRow(
                      "old", "acme", "Spec old", "ada", null, null, null, null, null, null));
        });
  }

  private void pruneOnMain(String id) {
    var erasure = new Erasure(main.db);
    Acting.as(
        "ada",
        () ->
            erasure.erase(
                erasure.closure(List.of(new Erasure.Target(Erasure.SPEC, id))),
                ChangeLog.Entry.LOCAL));
  }

  /** E1: an admin node's child of a pruned room is re-homed and lands; the room stays erased. */
  @Test
  void aRoomIsNotErasedUnderASpecANodeBornInItBeforeThePrune() {
    seedArchivedSpecWithRoom();
    sync(ada, ADA_ADMIN);
    Acting.as(
        "ada", () -> ada.specs.create(spec("child", SpecStatus.PENDING, "ada").withRoomId("old")));
    pruneOnMain("old");

    assertReHomedAndLanded(ada, ADA_ADMIN);
  }

  /** E1: a member node's child of a pruned room is re-homed and lands, never removed. */
  @Test
  void aMembersSpecBornInTheRoomBeforeThePruneIsNotLost() {
    seedArchivedSpecWithRoom();
    sync(ada, ADA);
    Acting.as(
        "ada", () -> ada.specs.create(spec("child", SpecStatus.PENDING, "ada").withRoomId("old")));
    pruneOnMain("old");

    var first = sync(ada, ADA);

    var denials = first.stream().flatMap(r -> r.denials().stream()).toList();
    assertEquals(1, denials.size(), "main says the room is gone: " + denials);
    assertTrue(ada.specs.findById("child").isPresent(), "the node keeps the child to re-home it");
    assertReHomedAndLanded(ada, ADA);
  }

  /** E1: once the child is re-homed, its conversation takes posts on every box. */
  @Test
  void aPostInTheBornInSpecsConversationIsPossibleAfterTheMinterIsPruned() {
    seedArchivedSpecWithRoom();
    sync(ada, ADA_ADMIN);
    Acting.as(
        "ada", () -> ada.specs.create(spec("child", SpecStatus.PENDING, "ada").withRoomId("old")));
    pruneOnMain("old");
    assertReHomedAndLanded(ada, ADA_ADMIN);

    var post =
        Acting.as(
            "ada", () -> new MessageStore(main.db).append("child", "ada", "still here", null));

    SyncBox.assertConvergedWithin(2, main, ada.syncsAs(ADA_ADMIN));
    assertTrue(
        new MessageStore(ada.db).findById(post.id()).isPresent(), "the post reaches the node");
  }

  @Test
  void aDeniedOffersContentIsNeverStoredOnMain() throws IOException {
    Acting.as("ada", () -> main.specs.create(spec("theirs", SpecStatus.PENDING, "ada")));
    sync(bob, BOB);
    Acting.as("bob", () -> bob.specs.setContent("theirs", "bob's secret body", "bob's plan"));
    var hash =
        bob.db
            .queryOne("SELECT body_hash FROM specs WHERE id = 'theirs'", r -> r.text(0))
            .orElseThrow();

    var reports = sync(bob, BOB);

    assertTrue(
        reports.stream().anyMatch(report -> !report.denials().isEmpty()), "the edit is denied");
    assertFalse(new BlobStore(main.db).has(hash), "main stored the denied offer's content");
    SyncBox.assertConvergedWithin(1, main, bob.syncsAs(BOB));
  }
}
