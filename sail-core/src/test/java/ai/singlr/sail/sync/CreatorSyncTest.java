/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.SpecStore;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A spec's creator travels with it as {@code _created_by} and is written once. Carol creates an
 * unassigned spec on Alice's node; Alice pushes, Bob pulls, and all three name Carol, whatever a
 * later offer carries. A create that names no creator, as an older node's does, records the pusher.
 */
class CreatorSyncTest {

  @TempDir Path tempDir;
  private SyncBox main;
  private SyncBox alice;
  private SyncBox bob;

  @BeforeEach
  void setUp() {
    main = new SyncBox(tempDir, "main");
    alice = new SyncBox(tempDir, "alice");
    bob = new SyncBox(tempDir, "bob");
  }

  @AfterEach
  void tearDown() {
    bob.close();
    alice.close();
    main.close();
  }

  @Test
  void aNodeBornSpecsCreatorIsRecordedOnMainAndAdoptedByAThirdBox() throws IOException {
    Acting.as("carol", () -> alice.specs.create(unassigned("draft")));

    round(alice);
    round(bob);

    assertEquals("carol", creatorOf(main, "draft"), "main");
    assertEquals("carol", creatorOf(alice, "draft"), "the originating node");
    assertEquals("carol", creatorOf(bob, "draft"), "a pulling node");
  }

  @Test
  void aCreateThatNamesNoCreatorRecordsThePusher() throws IOException {
    Acting.as("carol", () -> alice.specs.create(unassigned("legacy")));
    alice.db.execute("UPDATE specs SET created_by = NULL WHERE id = 'legacy'");

    round(alice);
    round(bob);

    assertEquals("alice", creatorOf(main, "legacy"));
    assertEquals("alice", creatorOf(alice, "legacy"), "the pusher adopts the creator main records");
    assertEquals("alice", creatorOf(bob, "legacy"));
  }

  @Test
  void anOfferWithoutACreatorKeepsMainsCreator() throws IOException {
    Acting.as("carol", () -> alice.specs.create(unassigned("draft")));
    round(alice);
    alice.db.execute("UPDATE specs SET created_by = NULL WHERE id = 'draft'");

    retitle(alice, "draft", "Retitled");
    round(alice);
    round(bob);

    assertEquals("Retitled", main.specs.findById("draft").orElseThrow().title());
    assertEquals("carol", creatorOf(main, "draft"));
    assertEquals("carol", creatorOf(alice, "draft"), "the pusher adopts main's creator");
    assertEquals("carol", creatorOf(bob, "draft"));
  }

  @Test
  void aLaterOfferNamingAnotherCreatorDoesNotChangeIt() throws IOException {
    Acting.as("carol", () -> alice.specs.create(unassigned("draft")));
    round(alice);
    alice.db.execute("UPDATE specs SET created_by = 'mallory' WHERE id = 'draft'");

    retitle(alice, "draft", "Retitled");
    round(alice);
    round(bob);

    assertEquals("Retitled", bob.specs.findById("draft").orElseThrow().title());
    assertEquals("carol", creatorOf(main, "draft"));
    assertEquals("carol", creatorOf(alice, "draft"), "the pusher adopts main's creator");
    assertEquals("carol", creatorOf(bob, "draft"));
  }

  @Test
  void anExistingSpecGainsItsCreatorOnItsNextRevision() throws IOException {
    Acting.as("carol", () -> alice.specs.create(unassigned("draft")));
    round(alice);
    round(bob);
    bob.db.execute("UPDATE specs SET created_by = NULL WHERE id = 'draft'");

    retitle(main, "draft", "Retitled");
    round(bob);

    assertEquals("carol", creatorOf(bob, "draft"));
  }

  @Test
  void aNodeAdoptsTheNoCreatorMainHoldsOverItsOwn() throws IOException {
    Acting.as(null, () -> main.specs.create(unassigned("twin")));
    Acting.as("alice", () -> alice.specs.create(unassigned("twin")));

    round(alice);

    assertNull(creatorOf(alice, "twin"), "adopting main's version adopts its creator, none");
    assertNull(creatorOf(main, "twin"));
  }

  @Test
  void aCreatorMainNeverRecordedIsTakenOnlyFromThatCreatorsOwnPush() throws IOException {
    Acting.as(null, () -> main.specs.create(unassigned("legacy")));
    Acting.as(null, () -> main.specs.create(unassigned("other")));
    round(alice);
    round(bob);
    alice.db.execute("UPDATE specs SET created_by = 'alice' WHERE id = 'legacy'");
    bob.db.execute("UPDATE specs SET created_by = 'alice' WHERE id = 'other'");

    retitle(bob, "other", "By bob");
    retitle(alice, "legacy", "By alice");
    round(bob);
    round(alice);
    round(bob);

    assertNull(creatorOf(main, "other"), "a push may name only its own FDE as the creator");
    assertNull(creatorOf(bob, "other"));
    assertEquals("alice", creatorOf(main, "legacy"));
    assertEquals("alice", creatorOf(alice, "legacy"));
    assertEquals("alice", creatorOf(bob, "legacy"));
    assertEquals(main.replica.currentRev("legacy"), alice.replica.currentRev("legacy"));
  }

  @Test
  void theCreatorOfAnUnassignedNodeBornSpecPostsInItsRoomAndMainAcceptsIt() throws IOException {
    Acting.as("alice", () -> alice.specs.create(unassigned("draft")));
    Acting.as("alice", () -> new MessageStore(alice.db).append("draft", "alice", "mine", null));
    Acting.as("bob", () -> new MessageStore(bob.db).append("draft", "bob", "not bob's", null));

    round(alice);
    round(alice, "message");
    round(bob, "message");

    var posted = new MessageStore(main.db).list("draft", null, 10);
    assertEquals(1, posted.size(), "only the creator's post stands");
    assertEquals("alice", posted.getFirst().author());
  }

  private void round(SyncBox box) throws IOException {
    round(box, "spec");
  }

  private void round(SyncBox box, String type) throws IOException {
    try (var link = SyncBox.connect(main.server(Actor.sync(box.id, Role.MEMBER)), box)) {
      link.reconcile(type, SyncedEntities.replicas(box.db, box.id, box.id).get(type));
    }
  }

  private static void retitle(SyncBox box, String id, String title) {
    var row = box.specs.findById(id).orElseThrow();
    Acting.as(
        "carol",
        () ->
            box.specs.update(
                new SpecStore.SpecRow(
                    row.id(),
                    row.project(),
                    title,
                    row.status(),
                    row.assignee(),
                    row.agent(),
                    row.model(),
                    row.reasoningEffort(),
                    row.branch(),
                    row.priority(),
                    row.createdBy(),
                    row.createdAt(),
                    row.updatedAt(),
                    row.updatedBy(),
                    row.dependsOn(),
                    row.repos(),
                    row.roomId())));
  }

  private static SpecStore.SpecRow unassigned(String id) {
    return SyncBox.spec(id, "Draft", "pending");
  }

  private static String creatorOf(SyncBox box, String id) {
    return box.specs.findById(id).orElseThrow().createdBy();
  }
}
