/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Erasure;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wherever main hands a node a revision, it says who made it, so every replica records the same
 * author: an accepted offer, a pulled tombstone, a pulled erasure and a tombstone a denial answers
 * with. None of these carries a snapshot that could name its author itself.
 */
class WireAuthorSyncTest {

  private static final Actor CAROL = Actor.cliOperator("carol");
  private static final Actor MACHINE = new Actor(null, Role.MEMBER, Actor.Lane.API);

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
  void anAcceptedOfferThatNamesNoAuthorRecordsThePusherEverywhere() throws IOException {
    Acting.by(MACHINE, () -> alice.specs.create(SyncBox.spec("auth", "Auth", "pending")));

    round(alice, Role.MEMBER);
    round(bob, Role.MEMBER);

    assertEquals("alice", authorOf(main, "auth"), "main");
    assertEquals("alice", authorOf(alice, "auth"), "the pushing node");
    assertEquals("alice", authorOf(bob, "auth"), "a pulling node");

    assertConverged();
  }

  @Test
  void aPulledTombstoneRecordsMainsAuthor() throws IOException {
    Acting.by(CAROL, () -> main.specs.create(SyncBox.spec("auth", "Auth", "pending")));
    round(bob, Role.MEMBER);

    Acting.by(CAROL, () -> main.specs.delete("auth"));
    round(bob, Role.MEMBER);

    assertEquals(ChangeLog.Kind.TOMBSTONE, head(bob, "auth").kind());
    assertEquals("carol", authorOf(bob, "auth"));

    assertConverged();
  }

  @Test
  void aPulledErasureRecordsMainsAuthor() throws IOException {
    Acting.by(CAROL, () -> main.specs.create(SyncBox.spec("auth", "Auth", "archived")));
    round(bob, Role.MEMBER);

    var erasure = new Erasure(main.db);
    Acting.by(
        CAROL,
        () ->
            erasure.erase(
                erasure.closure(List.of(new Erasure.Target(Erasure.SPEC, "auth"))), "prune"));
    round(bob, Role.MEMBER);

    assertEquals(ChangeLog.Kind.ERASURE, head(bob, "auth").kind());
    assertEquals("carol", authorOf(bob, "auth"));

    assertConverged();
  }

  @Test
  void aTombstoneADenialAnswersWithRecordsMainsAuthor() throws IOException {
    Acting.by(CAROL, () -> main.specs.create(SyncBox.spec("auth", "Auth", "pending")));
    Acting.by(CAROL, () -> main.specs.delete("auth"));
    Acting.as("bob", () -> bob.specs.create(SyncBox.spec("auth", "Reborn", "pending")));

    var report = round(bob, Role.VIEWER);

    assertEquals(List.of("auth"), report.denials().stream().map(SyncSession.Denial::id).toList());
    assertTrue(bob.specs.findById("auth").isEmpty(), "the node holds main's tombstone");
    assertEquals(ChangeLog.Kind.TOMBSTONE, head(bob, "auth").kind());
    assertEquals("carol", authorOf(bob, "auth"));

    assertConverged();
  }

  private SyncSession.TypeReport round(SyncBox box, Role role) throws IOException {
    try (var link = SyncBox.connect(main.server(Actor.sync(box.id, role)), box)) {
      return link.reconcile("spec", SyncedEntities.replicas(box.db, box.id, box.id).get("spec"));
    }
  }

  private static ChangeLog.Entry head(SyncBox box, String id) {
    return new ChangeLog(box.db).head("spec", id).orElseThrow();
  }

  private static String authorOf(SyncBox box, String id) {
    return head(box, id).actor();
  }

  private void assertConverged() {
    SyncBox.assertConverged(main, alice, bob);
  }
}
