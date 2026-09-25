/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A revision keeps the author who wrote it on every replica, including entities with no author
 * column of their own (runs, reviews, files), whose author lives only in the journal. Carol writes
 * on Alice's node, Alice pushes, Bob pulls: all three journals name Carol, never the pushing FDE or
 * main.
 */
class AuthorshipSyncTest {

  private static final Actor CAROL = Actor.cliOperator("carol");

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
  void aRunKeepsItsAuthorOnEveryReplica() throws IOException {
    var runs = new RunStore(alice.db);
    var id = DateTimeUtils.newId().toString();
    Actor.run(
        CAROL,
        () ->
            runs.create(
                id,
                "backend",
                "auth",
                "alice",
                "alice",
                "review",
                "claude-code",
                "feat/auth",
                "review it",
                123,
                null,
                "/home/dev/.sail/runs/" + id + "/agent.log",
                "sail-agent-" + id));

    assertAuthoredEverywhere("run", id);
  }

  @Test
  void aReviewKeepsItsAuthorOnEveryReplica() throws IOException {
    var reviews = new ReviewStore(alice.db);
    var id = Actor.call(CAROL, () -> reviews.createReview("auth", 1));

    assertAuthoredEverywhere("review", id);
  }

  @Test
  void aFileKeepsItsAuthorOnEveryReplica() throws IOException {
    var files = new FileStore(alice.db);
    Actor.run(
        CAROL,
        () ->
            files.put(
                "acme",
                "scripts/deploy.sh",
                new ByteArrayInputStream("deploy".getBytes(StandardCharsets.UTF_8)),
                0644));
    var id = fileId(alice);

    assertAuthoredEverywhere("file", id);
  }

  private void assertAuthoredEverywhere(String type, String id) throws IOException {
    assertEquals("carol", authorOf(alice, type, id));

    round(alice, type);
    round(bob, type);

    assertEquals("carol", authorOf(main, type, id), "main");
    assertEquals("carol", authorOf(alice, type, id), "the originating node");
    assertEquals("carol", authorOf(bob, type, id), "a pulling node");
  }

  private void round(SyncBox box, String type) throws IOException {
    try (var link = SyncBox.connect(main.server(Actor.sync(box.id, Role.MEMBER)), box)) {
      link.reconcile(type, SyncedEntities.replicas(box.db, box.id, box.id).get(type));
    }
  }

  private static String authorOf(SyncBox box, String type, String id) {
    return new ChangeLog(box.db).head(type, id).orElseThrow().actor();
  }

  private static String fileId(SyncBox box) {
    return box.db
        .queryOne("SELECT entity_id FROM change_log WHERE entity_type = 'file'", row -> row.text(0))
        .orElseThrow();
  }
}
