/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncPeer;
import ai.singlr.sail.store.SyncState;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessageSyncTest {

  @TempDir Path tempDir;
  private Box main;
  private Box node;
  private Box other;

  private final class Box implements AutoCloseable {
    final Sqlite db;
    final MessageStore messages;
    final StoreReplica replica;

    Box(String id) {
      db = Sqlite.open(tempDir.resolve(id + ".db"));
      new SchemaManager(db).migrate();
      db.execute(
          """
          INSERT INTO rooms (id, title, project, created_at, updated_at)
          VALUES ('room', 'Room', 'acme', 'now', 'now')""");
      messages = new MessageStore(db);
      replica =
          new StoreReplica(
              id, messages, new ChangeLog(db), new SyncConflicts(db), new SyncState(db));
    }

    @Override
    public void close() {
      db.close();
    }
  }

  @BeforeEach
  void setUp() {
    main = new Box("main");
    node = new Box("node");
    other = new Box("other");
  }

  @AfterEach
  void tearDown() {
    other.close();
    node.close();
    main.close();
  }

  @Test
  void messagesFromDifferentBoxesConvergeWithoutConflict() {
    new FdeStore(main.db).add("node", null, null, "admin");
    new FdeStore(main.db).add("other", null, null, "admin");
    var fromNode = node.messages.append("room", "node", "node message", null);
    var fromOther = other.messages.append("room", "other", "other message", null);
    var engine = new SyncEngine();

    SyncPeer.with("node", () -> engine.reconcile(node.replica, main.replica));
    SyncPeer.with("other", () -> engine.reconcile(other.replica, main.replica));
    SyncPeer.with("node", () -> engine.reconcile(node.replica, main.replica));
    SyncPeer.with("other", () -> engine.reconcile(other.replica, main.replica));

    assertEquals(2, main.messages.list("room", null, 10).size());
    assertTrue(node.messages.findById(fromOther.id()).isPresent());
    assertTrue(other.messages.findById(fromNode.id()).isPresent());
  }

  @Test
  void theQuestionFlagSurvivesSyncAndDerivesTheSameAnswerEverywhere() {
    var runId = "019fee00-0000-7000-8000-0000000000bb";
    main.db.execute("UPDATE rooms SET assignee = 'node' WHERE id = 'room'");
    var runs = new ai.singlr.sail.store.RunStore(main.db);
    runs.createReview(runId, "acme", "room", "node", "node", "codex", "b", "t", "/log", "unit");
    var principal = runs.findById(runId).orElseThrow().principal();
    var question = node.messages.append("room", principal, "Which flow?", null, true);
    var engine = new SyncEngine();

    SyncPeer.with("node", () -> engine.reconcile(node.replica, main.replica));

    var synced = main.messages.findById(question.id()).orElseThrow();
    assertTrue(synced.question(), "the flag is message data and rides the snapshot");
    assertEquals(Map.of("room", question.id()), main.messages.openQuestions());

    main.messages.append("room", "node", "answered", null);
    SyncPeer.with("node", () -> engine.reconcile(node.replica, main.replica));
    assertEquals(Map.of(), main.messages.openQuestions());
    assertEquals(Map.of(), node.messages.openQuestions());
  }

  @Test
  void syncedMessagesCannotBeChangedOrDeleted() {
    main.db.execute("UPDATE rooms SET assignee = 'node' WHERE id = 'room'");
    var row = node.messages.append("room", "node", "original", null);
    SyncPeer.with("node", () -> new SyncEngine().reconcile(node.replica, main.replica));
    var changed = new LinkedHashMap<>(main.messages.comparableSnapshot(row.id()));
    changed.put("body", "changed");

    assertThrows(
        IllegalArgumentException.class,
        () -> main.messages.applyRevision(row.id(), changed, row.rev()));
    assertThrows(
        IllegalArgumentException.class,
        () -> main.messages.commitRevision(row.id(), null, main.messages.latestRev(row.id())));
  }

  @Test
  void aMessageAuthoredBeforePrincipalRotationStillSyncs() {
    var reviewId = "019fee00-0000-7000-8000-0000000000aa";
    main.db.execute("UPDATE rooms SET assignee = 'node' WHERE id = 'room'");
    var runs = new ai.singlr.sail.store.RunStore(main.db);
    runs.createReview(reviewId, "acme", "room", "node", "node", "codex", "b", "t", "/log", "unit");
    var reviewerPrincipal = runs.findById(reviewId).orElseThrow().principal();
    runs.rotateCredential(reviewId, "claude-code", "fix");

    var accepted =
        SyncPeer.with(
            "node",
            () ->
                main.messages.commitRevision(
                    "019fee00-0000-7000-8000-0000000000ab",
                    snapshot(reviewerPrincipal, "room"),
                    null));

    assertTrue(
        accepted instanceof ai.singlr.sail.store.PushOutcome.Accepted,
        "a reviewer-authored message that synchronizes after the fix lane rotated the run's"
            + " principal authenticates against the replicated history, never wedging sync");
  }

  @Test
  void aSpecRowGrantsPostingAuthorityBeforeItsRoomRowArrives() {
    main.db.execute(
        """
        INSERT INTO specs (id, project, title, status, created_at, updated_at, assignee, room_id)
        VALUES ('orphan', 'acme', 'Orphan', 'pending', 'now', 'now', 'node', 'orphan')""");

    var accepted =
        SyncPeer.with(
            "node",
            () ->
                main.messages.commitRevision(
                    "019fee00-0000-7000-8000-0000000000ac", snapshot("node", "orphan"), null));

    assertTrue(
        accepted instanceof ai.singlr.sail.store.PushOutcome.Accepted,
        "a spec's ownership fields are authoritative for policy before its room row exists");
  }

  @Test
  void authenticatedPeerCannotForgeAnotherFdeAuthor() {
    var messageId = "00000000-0000-7000-8000-000000000001";

    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                SyncPeer.with(
                    "node",
                    () ->
                        main.messages.commitRevision(messageId, snapshot("admin", "room"), null)));

    assertTrue(error.getMessage().contains("may not post as 'admin'"));
    assertTrue(main.messages.findById(messageId).isEmpty());
  }

  @Test
  void authenticatedPeerCannotPostToForeignOrMissingSpec() {
    main.db.execute("UPDATE rooms SET assignee = 'ada', created_by = 'ada' WHERE id = 'room'");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            SyncPeer.with(
                "mallory",
                () ->
                    main.messages.commitRevision(
                        "00000000-0000-7000-8000-000000000002",
                        snapshot("mallory", "room"),
                        null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SyncPeer.with(
                "mallory",
                () ->
                    main.messages.commitRevision(
                        "00000000-0000-7000-8000-000000000003",
                        snapshot("mallory", "missing"),
                        null)));

    assertTrue(main.messages.list("room", null, 10).isEmpty());
  }

  @Test
  void authenticatedPeerMayPostAsItsRunPrincipalOnlyOnThatRunsSpec() {
    main.db.execute("UPDATE rooms SET assignee = 'node' WHERE id = 'room'");
    main.db.execute(
        """
        INSERT INTO runs
            (id, project, spec_id, node, role, agent, branch, task, status, started_at,
             principal, owner)
        VALUES
            ('00000000-0000-7000-8000-000000000010', 'acme', 'room', 'node', 'build',
             'codex', 'agent/messages', 'task', 'running', 'now', 'codex/run-1', 'node')""");
    var acceptedId = "00000000-0000-7000-8000-000000000011";

    SyncPeer.with(
        "node",
        () -> main.messages.commitRevision(acceptedId, snapshot("codex/run-1", "room"), null));

    assertEquals("codex/run-1", main.messages.findById(acceptedId).orElseThrow().author());

    main.db.execute(
        """
        INSERT INTO rooms (id, title, project, created_at, updated_at)
        VALUES ('other-room', 'Other room', 'acme', 'now', 'now')""");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SyncPeer.with(
                "node",
                () ->
                    main.messages.commitRevision(
                        "00000000-0000-7000-8000-000000000012",
                        snapshot("codex/run-1", "other-room"),
                        null)));
  }

  private static Map<String, Object> snapshot(String author, String specId) {
    return Map.of(
        "spec_id",
        specId,
        "author",
        author,
        "body",
        "sync message",
        "created_at",
        "2026-07-28T00:00:00Z");
  }
}
