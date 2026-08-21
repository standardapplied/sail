/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.sync.StoreReplica;
import ai.singlr.sail.sync.SyncDatabase;
import ai.singlr.sail.sync.SyncEngine;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every sync entry point opens its database through {@link SyncDatabase#converge}, which migrates
 * before any data is touched — the lesson of the in-sync self-update incident, where a
 * freshly-replaced binary kept syncing against the previous release's schema. Under the v1 baseline
 * that convergence is the floor on-ramp: a 0.14-floor database is stamped forward and syncs in the
 * same round, and a below-floor database is refused with the remedy before any sync data moves.
 * Lives in the store package to reach the package-private {@link FloorSchema} fixture.
 */
class SyncSchemaConvergenceTest {

  @TempDir Path tempDir;

  private Path stagedAtFloor(String name) {
    var path = tempDir.resolve(name + ".db");
    try (var db = Sqlite.open(path)) {
      FloorSchema.stage(db);
      markMigrationCompleted(db);
    }
    return path;
  }

  private Path currentDatabase(String name) {
    var path = tempDir.resolve(name + ".db");
    try (var db = Sqlite.open(path)) {
      new SchemaManager(db).migrate();
      markMigrationCompleted(db);
    }
    return path;
  }

  private static void markMigrationCompleted(Sqlite db) {
    db.execute(
        "INSERT INTO data_migrations (name, applied_at) VALUES (?, 'test')",
        LegacyDataMigration.NAME);
  }

  private static StoreReplica replica(String id, Sqlite db) {
    return new StoreReplica(
        id, new SpecStore(db), new ChangeLog(db), new SyncConflicts(db), new SyncState(db));
  }

  private static SpecStore.SpecRow spec(String id, String title, String status) {
    return new SpecStore.SpecRow(
        id,
        "proj",
        title,
        SpecStatus.fromWire(status),
        null,
        null,
        null,
        null,
        null,
        0,
        "uday",
        "",
        "",
        "uday",
        List.of(),
        List.of());
  }

  @Test
  void convergeOnRampsAFloorDatabaseToTheBaseline() {
    var path = stagedAtFloor("floor");

    try (var converged = SyncDatabase.converge(path, "box")) {
      assertEquals(
          SchemaManager.CURRENT_VERSION, new SchemaManager(converged.db()).currentVersion());
    }
  }

  @Test
  void aPulledRevisionAppliesBecauseTheReplicaOpenOnRampedFirst() {
    var nodePath = stagedAtFloor("node");
    try (var main = SyncDatabase.converge(currentDatabase("main"), "main");
        var node = SyncDatabase.converge(nodePath, "node")) {
      new SpecStore(main.db()).create(spec("auth", "Auth", "awaiting_merge"));

      var report =
          new SyncEngine().reconcile(replica("node", node.db()), replica("main", main.db()));

      assertEquals(1, report.pulled());
      assertEquals(
          "awaiting_merge",
          new SpecStore(node.db()).findById("auth").orElseThrow().status().wire());
    }
  }

  @Test
  void mainAcceptsAPushedCommitBecauseTheServingOpenOnRampedFirst() {
    var mainPath = stagedAtFloor("main");
    try (var main = SyncDatabase.converge(mainPath, "main");
        var node = SyncDatabase.converge(currentDatabase("node"), "node")) {
      new SpecStore(node.db()).create(spec("auth", "Auth", "awaiting_merge"));

      var report =
          new SyncEngine().reconcile(replica("node", node.db()), replica("main", main.db()));

      assertEquals(1, report.pushed());
      assertEquals(
          "awaiting_merge",
          new SpecStore(main.db()).findById("auth").orElseThrow().status().wire());
    }
  }

  @Test
  void aConvergenceFailureNamesTheBoxThatNeedsUpgradeAndAborts() {
    var path = tempDir.resolve("broken.db");
    try (var db = Sqlite.open(path)) {
      db.execute("CREATE TABLE specs (id TEXT PRIMARY KEY, project TEXT)");
    }

    var failure =
        assertThrows(IllegalStateException.class, () -> SyncDatabase.converge(path, "devbox-7"));

    assertTrue(failure.getMessage().contains("devbox-7"));
    assertTrue(failure.getMessage().contains("sail upgrade"));
  }

  @Test
  void convergenceRefusesABelowFloorDatabaseWithTheRemedy() {
    var path = tempDir.resolve("pre-floor.db");
    try (var db = Sqlite.open(path)) {
      db.execute(
          "CREATE TABLE schema_version (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
      db.execute("INSERT INTO schema_version (version, applied_at) VALUES (100, 'staged')");
    }

    var failure =
        assertThrows(
            SchemaManager.PreFloorException.class, () -> SyncDatabase.converge(path, "box"));

    assertTrue(failure.getMessage().contains("schema v100"));
    assertTrue(failure.getMessage().contains("0.14"));
    assertTrue(failure.getMessage().contains("sail migrate"));
    try (var db = Sqlite.open(path)) {
      assertEquals(100, new SchemaManager(db).currentVersion());
    }
  }

  @Test
  void aBornCleanDatabaseWithoutTheMarkerIsStampedNotRefused() {
    var path = tempDir.resolve("aux-created.db");
    try (var db = Sqlite.open(path)) {
      new SchemaManager(db).migrate();
    }

    try (var converged = SyncDatabase.converge(path, "aux-box")) {
      assertEquals(
          1L,
          converged
              .db()
              .queryOne(
                  "SELECT COUNT(*) FROM data_migrations WHERE name = ?",
                  row -> row.integer(0),
                  LegacyDataMigration.NAME)
              .orElseThrow());
    }
  }

  @Test
  void convergeOnABrandNewDatabaseStampsTheFloorAndSyncs() {
    var path = tempDir.resolve("fresh.db");

    try (var fresh = SyncDatabase.converge(path, "new-box")) {
      assertEquals(
          1L,
          fresh
              .db()
              .queryOne(
                  "SELECT COUNT(*) FROM data_migrations WHERE name = ?",
                  row -> row.integer(0),
                  LegacyDataMigration.NAME)
              .orElseThrow());
    }
    try (var again = SyncDatabase.converge(path, "new-box")) {
      assertEquals(
          0L,
          again.db().queryOne("SELECT COUNT(*) FROM specs", row -> row.integer(0)).orElseThrow());
    }
  }

  @Test
  void convergeIsIdempotentOnACurrentDatabase() {
    var path = currentDatabase("current");
    int versionAfterFirst;
    try (var first = SyncDatabase.converge(path, "box")) {
      versionAfterFirst = new SchemaManager(first.db()).currentVersion();
    }
    try (var second = SyncDatabase.converge(path, "box")) {
      assertEquals(versionAfterFirst, new SchemaManager(second.db()).currentVersion());
    }
  }
}
