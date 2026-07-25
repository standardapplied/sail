/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.sync.SpecReplica;
import ai.singlr.sail.sync.SyncDatabase;
import ai.singlr.sail.sync.SyncEngine;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression for the in-sync self-update incident: a node's binary was replaced mid-sync and the
 * new code wrote {@code awaiting_merge} into a specs table still carrying the old narrow CHECK
 * constraint. Every sync entry point now opens its database through {@link SyncDatabase#converge},
 * which migrates before any data is touched. Lives in the store package to reach the
 * package-private {@link SchemaManager#migrateTo} staging seam.
 */
class SyncSchemaConvergenceTest {

  @TempDir Path tempDir;

  private Path stagedAtNarrowStatusCheck(String name) {
    var path = tempDir.resolve(name + ".db");
    try (var db = Sqlite.open(path)) {
      new SchemaManager(db).migrateTo(SchemaManager.LAST_VERSION_WITH_NARROW_STATUS_CHECK);
    }
    return path;
  }

  private static SpecReplica replica(String id, Sqlite db) {
    return new SpecReplica(
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
  void theStagedSchemaRejectsAwaitingMergeWithoutConvergence() {
    var path = stagedAtNarrowStatusCheck("raw");
    try (var db = Sqlite.open(path)) {
      assertThrows(
          SqliteException.class,
          () ->
              db.execute(
                  "INSERT INTO specs (id, title, status, created_at, updated_at)"
                      + " VALUES ('m', 'M', 'awaiting_merge', 't', 't')"));
    }
  }

  @Test
  void aPulledAwaitingMergeRevisionAppliesBecauseTheReplicaOpenConvergedFirst() {
    var nodePath = stagedAtNarrowStatusCheck("node");
    try (var main = SyncDatabase.converge(tempDir.resolve("main.db"), "main");
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
  void mainAcceptsAPushedAwaitingMergeCommitBecauseTheServingOpenConvergedFirst() {
    var mainPath = stagedAtNarrowStatusCheck("main");
    try (var main = SyncDatabase.converge(mainPath, "main");
        var node = SyncDatabase.converge(tempDir.resolve("node.db"), "node")) {
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
  void convergeIsIdempotentOnACurrentDatabase() {
    var path = tempDir.resolve("current.db");
    int versionAfterFirst;
    try (var first = SyncDatabase.converge(path, "box")) {
      versionAfterFirst = new SchemaManager(first.db()).currentVersion();
    }
    try (var second = SyncDatabase.converge(path, "box")) {
      assertEquals(versionAfterFirst, new SchemaManager(second.db()).currentVersion());
    }
  }

  @Test
  void aPreFloorLegacyBuildIsTerminalBeforeSyncCanUseTheHandle() {
    var path = tempDir.resolve("pre-floor.db");
    try (var legacy = Sqlite.open(path)) {
      new SchemaManager(legacy).migrateTo(SchemaManager.LAST_VERSION_BEFORE_V1_FLOOR);
      legacy.execute(
          """
          INSERT INTO runs
              (id, project, spec_id, node, role, agent, status, started_at, unit)
          VALUES
              ('legacy', 'acme', 'auth', 'node-a', 'build', 'codex', 'running',
                  '2026-01-01', '')""");
    }

    try (var converged = SyncDatabase.converge(path, "node-a")) {
      var run = new RunStore(converged.db()).findById("legacy").orElseThrow();
      assertEquals("stopped", run.status());
      assertTrue(run.completedAt() != null);
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
}
