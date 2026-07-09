/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaManagerTest {

  @TempDir Path tempDir;
  private Sqlite db;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  @Test
  void migrateCreatesAllTables() {
    new SchemaManager(db).migrate();

    var tables =
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
            row -> row.text(0));
    assertTrue(tables.contains("specs"));
    assertTrue(tables.contains("spec_dependencies"));
    assertTrue(tables.contains("spec_repos"));
    assertTrue(tables.contains("spec_content"));
    assertTrue(tables.contains("spec_attachments"));
    assertTrue(tables.contains("events"));
    assertTrue(tables.contains("api_tokens"));
    assertTrue(tables.contains("schema_version"));
  }

  @Test
  void migrateIsIdempotent() {
    var schema = new SchemaManager(db);
    schema.migrate();
    var v1 = schema.currentVersion();

    schema.migrate();
    var v2 = schema.currentVersion();

    assertEquals(v1, v2);
  }

  @Test
  void currentVersionIsZeroBeforeMigration() {
    assertEquals(0, new SchemaManager(db).currentVersion());
  }

  @Test
  void currentVersionMatchesMigrationCount() {
    var schema = new SchemaManager(db);
    schema.migrate();
    assertTrue(schema.currentVersion() > 0);
  }

  @Test
  void statusCheckAcceptsAwaitingMergeAfterMigration() {
    new SchemaManager(db).migrate();

    db.execute(
        "INSERT INTO specs (id, title, status, created_at, updated_at)"
            + " VALUES ('m', 'M', 'awaiting_merge', 't', 't')");

    assertEquals(
        "awaiting_merge",
        db.queryOne("SELECT status FROM specs WHERE id = 'm'", row -> row.text(0)).orElseThrow());
  }

  @Test
  void statusCheckStillRejectsGarbageAfterMigration() {
    new SchemaManager(db).migrate();

    assertThrows(
        SqliteException.class,
        () ->
            db.execute(
                "INSERT INTO specs (id, title, status, created_at, updated_at)"
                    + " VALUES ('m', 'M', 'bogus', 't', 't')"));
  }

  @Test
  void specsRebuildPreservesRowsAndChildren() {
    var schema = new SchemaManager(db);
    schema.migrateTo(SchemaManager.LAST_VERSION_WITH_NARROW_STATUS_CHECK);
    db.execute(
        "INSERT INTO specs (id, title, status, priority, created_at, updated_at, project,"
            + " updated_by, rev, base_rev)"
            + " VALUES ('auth', 'OAuth', 'review', 7, 'c', 'u', 'acme', 'uday', 'r1', 'r0')");
    db.execute(
        "INSERT INTO spec_content (spec_id, body, plan, updated_at)"
            + " VALUES ('auth', 'body', 'plan', 't')");
    db.execute("INSERT INTO spec_repos (spec_id, repo) VALUES ('auth', 'api')");
    db.execute("INSERT INTO spec_dependencies (spec_id, depends_on) VALUES ('auth', 'base')");
    db.execute(
        "INSERT INTO reviews (id, spec_id, iteration, status, created_at)"
            + " VALUES ('rev-1', 'auth', 1, 'passed', 't')");

    schema.migrate();

    var row =
        db.queryOne(
                "SELECT title, status, priority, project, updated_by, rev, base_rev"
                    + " FROM specs WHERE id = 'auth'",
                r ->
                    List.of(
                        r.text(0),
                        r.text(1),
                        String.valueOf(r.integer(2)),
                        r.text(3),
                        r.text(4),
                        r.text(5),
                        r.text(6)))
            .orElseThrow();
    assertEquals(List.of("OAuth", "review", "7", "acme", "uday", "r1", "r0"), row);
    assertEquals(
        "body",
        db.queryOne("SELECT body FROM spec_content WHERE spec_id = 'auth'", r -> r.text(0))
            .orElseThrow());
    assertEquals(
        "api",
        db.queryOne("SELECT repo FROM spec_repos WHERE spec_id = 'auth'", r -> r.text(0))
            .orElseThrow());
    assertTrue(db.query("PRAGMA foreign_key_check", r -> r.text(0)).isEmpty());
  }

  @Test
  void specsRebuildKeepsChildCascadesWired() {
    var schema = new SchemaManager(db);
    schema.migrateTo(SchemaManager.LAST_VERSION_WITH_NARROW_STATUS_CHECK);
    db.execute(
        "INSERT INTO specs (id, title, status, created_at, updated_at)"
            + " VALUES ('auth', 'OAuth', 'pending', 't', 't')");
    db.execute(
        "INSERT INTO spec_content (spec_id, body, plan, updated_at) VALUES ('auth', 'b', 'p', 't')");
    schema.migrate();

    db.execute("DELETE FROM specs WHERE id = 'auth'");

    assertTrue(
        db.queryOne("SELECT body FROM spec_content WHERE spec_id = 'auth'", r -> r.text(0))
            .isEmpty());
  }

  @Test
  void migrateOnFreshDatabaseCreatesIndexes() {
    new SchemaManager(db).migrate();

    var indexes =
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'idx_%' ORDER BY name",
            row -> row.text(0));
    assertTrue(indexes.contains("idx_events_type"));
    assertTrue(indexes.contains("idx_events_project"));
    assertTrue(indexes.contains("idx_events_spec"));
    assertTrue(indexes.contains("idx_events_timestamp"));
  }

  @Test
  void migrateRenamesAgentSessionsToRunsAndDropsStaleIndexes() {
    new SchemaManager(db).migrate();

    var tables =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'", row -> row.text(0));
    assertTrue(tables.contains("runs"));
    assertFalse(tables.contains("agent_sessions"));

    var indexes =
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'idx_%'",
            row -> row.text(0));
    assertTrue(indexes.contains("idx_runs_project"));
    assertTrue(indexes.contains("idx_runs_spec"));
    assertFalse(indexes.contains("idx_agent_sessions_project"));
    assertFalse(indexes.contains("idx_agent_sessions_spec"));
  }
}
