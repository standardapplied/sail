/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

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
import java.util.ArrayList;
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

  private List<SyncSession.TypeReport> sync(SyncBox box, Actor as) throws IOException {
    var reports = new ArrayList<SyncSession.TypeReport>();
    try (var link = SyncBox.connect(main.server(as), box)) {
      Actor.run(Actor.main(), () -> NodeRound.begin(link.session(), box.db, box.id));
      var replicas = SyncedEntities.replicas(box.db, box.id, box.id);
      for (var entity : SyncedEntities.all()) {
        reports.add(link.reconcile(entity.type(), replicas.get(entity.type())));
      }
    }
    return reports;
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

  private void seedArchivedSpecWithRoom() throws IOException {
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
            erasure.erase(erasure.closure(List.of(new Erasure.Target(Erasure.SPEC, id))), "local"));
  }

  private void assertNoSpecLivesInAnErasedRoom(SyncBox box) {
    var inOld = box.specs.findById("child").filter(c -> c.roomIdOrIdentity().equals("old"));
    var roomErased = new ChangeLog(box.db).isErased(Erasure.ROOM, "old");
    assertFalse(
        inOld.isPresent() && roomErased,
        box.id + ": spec 'child' lives in room 'old', which is erased");
  }

  @Test
  void aRoomIsNotErasedUnderASpecANodeBornInItBeforeThePrune() throws IOException {
    seedArchivedSpecWithRoom();
    sync(ada, ADA_ADMIN);
    Acting.as(
        "ada", () -> ada.specs.create(spec("child", SpecStatus.PENDING, "ada").withRoomId("old")));

    pruneOnMain("old");
    sync(ada, ADA_ADMIN);
    sync(ada, ADA_ADMIN);

    assertNoSpecLivesInAnErasedRoom(main);
    assertNoSpecLivesInAnErasedRoom(ada);
  }

  @Test
  void aMembersSpecBornInTheRoomBeforeThePruneIsNotLost() throws IOException {
    seedArchivedSpecWithRoom();
    sync(ada, ADA);
    Acting.as(
        "ada", () -> ada.specs.create(spec("child", SpecStatus.PENDING, "ada").withRoomId("old")));

    pruneOnMain("old");
    var reports = sync(ada, ADA);

    var denials =
        reports.stream()
            .flatMap(report -> report.denials().stream())
            .map(Object::toString)
            .toList();
    assertTrue(
        main.specs.findById("child").isPresent() || ada.specs.findById("child").isPresent(),
        "ada's new spec was removed from every box; denials: " + denials);
  }

  @Test
  void aPostInTheBornInSpecsConversationIsPossibleAfterTheMinterIsPruned() throws IOException {
    seedArchivedSpecWithRoom();
    sync(ada, ADA_ADMIN);
    Acting.as(
        "ada", () -> ada.specs.create(spec("child", SpecStatus.PENDING, "ada").withRoomId("old")));
    pruneOnMain("old");
    sync(ada, ADA_ADMIN);

    if (main.specs.findById("child").isPresent()) {
      Acting.as("ada", () -> new MessageStore(main.db).append("old", "ada", "still here", null));
    }
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
  }
}
