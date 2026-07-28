/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncPeer;
import ai.singlr.sail.store.SyncState;
import java.nio.file.Path;
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
    final MessageReplica replica;

    Box(String id) {
      db = Sqlite.open(tempDir.resolve(id + ".db"));
      new SchemaManager(db).migrate();
      db.execute(
          """
          INSERT INTO specs (id, title, project, created_at, updated_at)
          VALUES ('room', 'Room', 'acme', 'now', 'now')""");
      messages = new MessageStore(db);
      replica =
          new MessageReplica(
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
  void syncedMessagesCannotBeChangedOrDeleted() {
    var row = node.messages.append("room", "node", "original", null);
    SyncPeer.with("node", () -> new SyncEngine().reconcile(node.replica, main.replica));
    var changed = new java.util.LinkedHashMap<>(main.messages.comparableSnapshot(row.id()));
    changed.put("body", "changed");

    assertThrows(
        IllegalArgumentException.class,
        () -> main.messages.applyRevision(row.id(), changed, row.rev()));
    assertThrows(
        IllegalArgumentException.class,
        () -> main.messages.commitRevision(row.id(), null, main.messages.latestRev(row.id())));
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
  void authenticatedPeerMayPostAsItsRunPrincipalOnlyOnThatRunsSpec() {
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
        INSERT INTO specs (id, title, project, created_at, updated_at)
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
