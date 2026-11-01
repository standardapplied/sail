/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.sync.SyncDatabase;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every sync entry point opens its database through {@link SyncDatabase#converge}, which creates
 * the schema of a fresh database before any data is touched and refuses one of another release with
 * that refusal's own remedy, never re-worded.
 */
class SyncSchemaConvergenceTest {

  @TempDir Path tempDir;

  @Test
  void aBrandNewDatabaseGetsTheSchemaBeforeItSyncsAndAgainChangesNothing() {
    var path = tempDir.resolve("fresh.db");

    try (var fresh = SyncDatabase.converge(path, "new-box")) {
      assertEquals(SchemaManager.CURRENT_VERSION, new SchemaManager(fresh.db()).currentVersion());
      assertEquals(
          0L,
          fresh.db().queryOne("SELECT COUNT(*) FROM specs", row -> row.integer(0)).orElseThrow());
    }
    try (var again = SyncDatabase.converge(path, "new-box")) {
      assertEquals(
          List.of(SchemaManager.CURRENT_VERSION),
          again.db().query("SELECT version FROM schema_version", row -> (int) row.integer(0)));
    }
  }

  @Test
  void aDatabaseOfAnotherReleaseIsRefusedWithItsOwnRemedyBeforeAnythingMoves() {
    var path = tempDir.resolve("pre-v1.db");
    try (var db = Sqlite.open(path)) {
      db.execute(
          "CREATE TABLE schema_version (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
      db.execute("INSERT INTO schema_version (version, applied_at) VALUES (100, 'staged')");
    }

    var failure = assertThrows(Remedied.State.class, () -> SyncDatabase.converge(path, "box"));

    assertEquals(
        "Database schema v100 was made by a Sail release before v1; this release opens only v"
            + SchemaManager.CURRENT_VERSION
            + ".",
        failure.reason());
    assertEquals(List.of(Remedy.sail("init")), failure.remedy().commands());
    try (var db = Sqlite.open(path)) {
      assertEquals(100, new SchemaManager(db).currentVersion());
    }
  }

  @Test
  void aDatabaseSailDidNotMakeIsRefusedNamingAFreshOne() {
    var path = tempDir.resolve("foreign.db");
    try (var db = Sqlite.open(path)) {
      db.execute("CREATE TABLE specs (id TEXT PRIMARY KEY, project TEXT)");
    }

    var failure = assertThrows(Remedied.State.class, () -> SyncDatabase.converge(path, "box"));

    assertEquals(
        "Database " + path + " holds tables but no schema version: Sail did not make it.",
        failure.reason());
    assertEquals(List.of(Remedy.sail("init")), failure.remedy().commands());
  }

  @Test
  void aSchemaThatCannotBeWrittenNamesTheBoxToUpgradeAndAborts() {
    var path = tempDir.resolve("broken.db");
    try (var db = Sqlite.open(path)) {
      db.execute(
          "CREATE TABLE schema_version (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
      db.execute(
          "CREATE TRIGGER no_stamp BEFORE INSERT ON schema_version"
              + " BEGIN SELECT RAISE(ABORT, 'stamp refused'); END");
    }

    var failure = assertThrows(Remedied.State.class, () -> SyncDatabase.converge(path, "devbox-7"));

    assertEquals(
        "Sync aborted before touching data: could not converge the database schema on"
            + " 'devbox-7': stamp refused.",
        failure.reason());
    assertEquals(List.of(Remedy.sail("upgrade")), failure.remedy().commands());
  }
}
