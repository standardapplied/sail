/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ActingAs
class SyncedEntitiesTest {
  @TempDir Path tempDir;

  @Test
  void everyEntityHasAStoreResolverAndReplicaInDependencyOrder() {
    try (var db = Sqlite.open(tempDir.resolve("test.db"))) {
      new SchemaManager(db).migrate();
      assertEquals(
          List.of("spec", "room", "file", "project", "run", "review", "message"),
          SyncedEntities.all().stream().map(SyncedEntities.Entity::type).toList());
      for (var entity : SyncedEntities.all()) {
        assertEquals(entity.type(), entity.store(db).entityType());
        assertNotNull(entity.resolver(db));
      }
      assertEquals(
          SyncedEntities.all().stream().map(SyncedEntities.Entity::type).toList(),
          List.copyOf(SyncedEntities.replicas(db, "node", "owner").keySet()));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"run", "review"})
  void opaqueLifecycleConflictsResolveEitherSideThroughTheRegistry(String type) {
    for (var mine : List.of(true, false)) {
      try (var box = new SyncBox("node")) {
        var id =
            type.equals("run")
                ? createRun(box.db)
                : new ReviewStore(box.db).createReview("spec", 1);
        var entity = SyncedEntities.require(type);
        var store = entity.store(box.db);
        var local = store.comparableSnapshot(id);
        var remote = new LinkedHashMap<>(local);
        remote.put("status", "failed");
        var replica = SyncedEntities.replicas(box.db, "node", "node").get(type);
        replica.recordConflict(id, local, local, remote, List.of("status"));
        var parked = box.conflicts.pendingFor(type, id).orElseThrow();
        var chosen = mine ? local : remote;
        var revision = entity.resolver(box.db).resolveConflict(id, chosen, remote);
        assertNotNull(revision);
        assertTrue(box.conflicts.resolve(parked.id(), revision));
        assertEquals(chosen.get("status"), store.comparableSnapshot(id).get("status"));
        assertTrue(box.conflicts.pending().isEmpty());
      }
    }
  }

  @Test
  void aParkedMessageConflictResolvesToMainsCopyAndRefusesMine() {
    try (var box = new SyncBox("node")) {
      new RoomStore(box.db)
          .create(
              new RoomStore.RoomRow(
                  "room", "proj", "Room", "node", "on", "[]", "node", null, null, "node"));
      var messages = new MessageStore(box.db);
      var id = messages.append("room", "node", "local", null).id();
      var local = messages.comparableSnapshot(id);
      var remote = new LinkedHashMap<>(local);
      remote.put("body", "remote");
      SyncedEntities.replicas(box.db, "node", "node")
          .get("message")
          .recordConflict(id, local, local, remote, List.of("body"));
      var parked = box.conflicts.pendingFor("message", id).orElseThrow();
      var resolver = SyncedEntities.require("message").resolver(box.db);

      var refused =
          assertThrows(
              IllegalArgumentException.class, () -> resolver.resolveConflict(id, local, remote));
      assertTrue(refused.getMessage().contains("append-only"), refused.getMessage());
      assertEquals("local", messages.comparableSnapshot(id).get("body"), "mine changed nothing");
      assertTrue(box.conflicts.pendingFor("message", id).isPresent(), "still parked");

      var revision = resolver.resolveConflict(id, remote, remote);
      assertNotNull(revision);
      assertTrue(box.conflicts.resolve(parked.id(), revision));
      assertEquals(remote, messages.comparableSnapshot(id));
      assertEquals(revision, messages.latestRev(id));
      assertEquals(revision, messages.baseRevOf(id), "rebased onto main's copy");
      var head = new ChangeLog(box.db).head("message", id).orElseThrow();
      assertEquals("node", head.actor(), "main's copy keeps its author, not the resolver's");
      assertEquals(Actor.MAIN_HANDLE, head.peer());
      assertTrue(box.conflicts.pending().isEmpty());
    }
  }

  private static String createRun(Sqlite db) {
    var id = DateTimeUtils.newId().toString();
    return new RunStore(db)
        .create(
            id,
            "proj",
            "spec",
            "node",
            "node",
            "build",
            "codex",
            "agent/spec",
            "task",
            123,
            null,
            "/tmp/log",
            "unit");
  }
}
