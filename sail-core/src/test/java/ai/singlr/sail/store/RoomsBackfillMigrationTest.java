/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ProjectRegistry;
import ai.singlr.sail.sync.StoreReplica;
import ai.singlr.sail.sync.SyncEngine;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoomsBackfillMigrationTest {

  @TempDir Path tempDir;
  private Sqlite db;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("main.db"));
    new SchemaManager(db).migrate();
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private static void seedSpec(Sqlite target, String id, String engagement) {
    target.execute(
        """
        INSERT INTO specs (id, project, title, status, assignee, wake, engagement, created_by,
            created_at, updated_at, updated_by)
        VALUES (?, 'acme', ?, 'done', 'uday', 'on', ?, 'uday',
            '2026-08-01T00:00:00Z', '2026-08-02T00:00:00Z', 'uday')""",
        id,
        "Spec " + id,
        engagement);
  }

  private DataMigration.Report backfill(Sqlite target) {
    return new RoomsBackfillMigration()
        .apply(target, noProjects(), DataMigration.Prompter.NON_INTERACTIVE);
  }

  private ProjectRegistry noProjects() {
    return ProjectRegistry.loadFromDisk(tempDir.resolve("no-projects"));
  }

  @Test
  void mintsOneRoomPerSpecWithIdentityIdCarryingConversationState() {
    seedSpec(db, "auth", "{\"agent\":\"claude-code\",\"mode\":\"full\",\"engaged_at\":\"t0\"}");
    seedSpec(db, "billing", null);

    var report = backfill(db);

    assertEquals(2, report.applied());
    var rooms = new RoomStore(db);
    var auth = rooms.findById("auth").orElseThrow();
    assertEquals("Spec auth", auth.title());
    assertEquals("acme", auth.project());
    assertEquals("uday", auth.assignee());
    assertEquals("on", auth.wake());
    assertEquals(
        "[{\"agent\":\"claude-code\",\"mode\":\"full\",\"engaged_at\":\"t0\"}]",
        auth.roster(),
        "the engagement object becomes the roster's one-element array");
    assertEquals("2026-08-01T00:00:00Z", auth.createdAt());
    assertEquals("uday", auth.updatedBy());
    assertNull(rooms.findById("billing").orElseThrow().roster(), "no engagement, empty roster");
    assertEquals(
        rooms.latestRev("auth"),
        rooms.baseRevOf("auth"),
        "a backfilled room is its own synced ancestor");
  }

  @Test
  void skipsSpecsThatAlreadyHaveARoomAndReportsOnlyFreshOnes() {
    seedSpec(db, "auth", null);
    backfill(db);

    seedSpec(db, "billing", null);
    var second = backfill(db);

    assertEquals(1, second.applied());
    assertEquals(2, new RoomStore(db).list("acme").size());
  }

  @Test
  void runsExactlyOncePerDatabaseThroughTheDataMigrator() {
    seedSpec(db, "auth", null);
    var migrator = new DataMigrator(db, List.of(new RoomsBackfillMigration()));

    var first = migrator.run(noProjects(), DataMigration.Prompter.NON_INTERACTIVE);
    var second = migrator.run(noProjects(), DataMigration.Prompter.NON_INTERACTIVE);

    assertEquals(1, first.getFirst().report().applied());
    assertTrue(second.getFirst().alreadyApplied());
  }

  @Test
  void twoBoxesBackfillIdenticalRevsSoTheFirstSyncIsANoop() {
    try (var node = Sqlite.open(tempDir.resolve("node.db"))) {
      new SchemaManager(node).migrate();
      seedSpec(db, "auth", "{\"agent\":\"claude-code\",\"mode\":\"full\",\"engaged_at\":\"t0\"}");
      seedSpec(node, "auth", "{\"agent\":\"claude-code\",\"mode\":\"full\",\"engaged_at\":\"t0\"}");

      backfill(db);
      backfill(node);

      var mainRooms = new RoomStore(db);
      var nodeRooms = new RoomStore(node);
      assertEquals(
          mainRooms.latestRev("auth"),
          nodeRooms.latestRev("auth"),
          "identical spec content mints an identical content-hash rev on every box");

      var report =
          new SyncEngine()
              .reconcile(
                  new StoreReplica(
                      "node",
                      nodeRooms,
                      new ChangeLog(node),
                      new SyncConflicts(node),
                      new SyncState(node)),
                  new StoreReplica(
                      "main",
                      mainRooms,
                      new ChangeLog(db),
                      new SyncConflicts(db),
                      new SyncState(db)));

      assertEquals(0, report.total(), "the first fleet sync after backfill moves nothing");
    }
  }

  @Test
  void divergedSpecsStillConvergeThroughTheOrdinarySyncPath() {
    try (var node = Sqlite.open(tempDir.resolve("node.db"))) {
      new SchemaManager(node).migrate();
      seedSpec(db, "auth", "{\"agent\":\"claude-code\",\"mode\":\"full\",\"engaged_at\":\"t0\"}");
      seedSpec(node, "auth", null);

      backfill(db);
      backfill(node);

      var mainRooms = new RoomStore(db);
      var nodeRooms = new RoomStore(node);
      var report =
          new SyncEngine()
              .reconcile(
                  new StoreReplica(
                      "node",
                      nodeRooms,
                      new ChangeLog(node),
                      new SyncConflicts(node),
                      new SyncState(node)),
                  new StoreReplica(
                      "main",
                      mainRooms,
                      new ChangeLog(db),
                      new SyncConflicts(db),
                      new SyncState(db)));

      assertTrue(report.total() > 0, "genuine divergence is reconciled, never silently equal");
      assertEquals(
          mainRooms.findById("auth").orElseThrow().roster(),
          nodeRooms.findById("auth").orElseThrow().roster(),
          "the boxes converge on main's authoritative roster");
    }
  }
}
