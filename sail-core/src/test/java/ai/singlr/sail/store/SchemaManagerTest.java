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
    assertTrue(tables.contains("runs"));
    assertFalse(tables.contains("agent_sessions"));
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
  void freshInstallStampsTheV1BaselineVersion() {
    var schema = new SchemaManager(db);
    schema.migrate();
    assertEquals(SchemaManager.V1_VERSION, schema.currentVersion());
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
  void baselineWiresChildCascades() {
    new SchemaManager(db).migrate();
    db.execute(
        "INSERT INTO specs (id, title, status, created_at, updated_at)"
            + " VALUES ('auth', 'OAuth', 'pending', 't', 't')");
    db.execute(
        "INSERT INTO spec_content (spec_id, body, plan, updated_at) VALUES ('auth', 'b', 'p', 't')");

    db.execute("DELETE FROM specs WHERE id = 'auth'");

    assertTrue(
        db.queryOne("SELECT body FROM spec_content WHERE spec_id = 'auth'", r -> r.text(0))
            .isEmpty());
  }

  @Test
  void freshBaselineEqualsFloorPlusOnRamp() {
    new SchemaManager(db).migrate();
    try (var floor = Sqlite.open(tempDir.resolve("floor.db"))) {
      FloorSchema.stage(floor);
      new SchemaManager(floor).migrate();

      assertEquals(canonicalSchema(db), canonicalSchema(floor));
    }
  }

  @Test
  void migrateOnRampsAFloorDatabaseInOneStepPreservingRows() {
    try (var floor = Sqlite.open(tempDir.resolve("floor.db"))) {
      FloorSchema.stage(floor);
      floor.execute(
          "INSERT INTO specs (id, title, status, priority, created_at, updated_at, project,"
              + " updated_by, rev, base_rev)"
              + " VALUES ('auth', 'OAuth', 'review', 7, 'c', 'u', 'acme', 'uday', 'r1', 'r0')");
      floor.execute(
          "INSERT INTO spec_content (spec_id, body, plan, updated_at)"
              + " VALUES ('auth', 'body', 'plan', 't')");
      var schema = new SchemaManager(floor);
      assertEquals(SchemaManager.FLOOR_VERSION, schema.currentVersion());

      schema.migrate();

      assertEquals(SchemaManager.V1_VERSION, schema.currentVersion());
      var row =
          floor
              .queryOne(
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
          floor
              .queryOne("SELECT body FROM spec_content WHERE spec_id = 'auth'", r -> r.text(0))
              .orElseThrow());

      schema.migrate();
      assertEquals(SchemaManager.V1_VERSION, schema.currentVersion());
    }
  }

  @Test
  void migrateRefusesABelowFloorDatabaseWithTheRemedy() {
    stageBelowFloor(37);
    var schema = new SchemaManager(db);

    var refusal = assertThrows(SchemaManager.PreFloorException.class, schema::migrate);

    assertTrue(refusal.getMessage().contains("schema v37"));
    assertTrue(refusal.getMessage().contains("schema v" + SchemaManager.FLOOR_VERSION));
    assertTrue(refusal.getMessage().contains("0.14"));
    assertTrue(refusal.getMessage().contains("sail upgrade"));
    assertEquals(37, schema.currentVersion());
    assertEquals(
        List.of("schema_version"),
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
            row -> row.text(0)));
  }

  @Test
  void migrateRefusesTheVersionJustBelowTheFloor() {
    stageBelowFloor(SchemaManager.FLOOR_VERSION - 1);

    assertThrows(SchemaManager.PreFloorException.class, () -> new SchemaManager(db).migrate());
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
    assertTrue(indexes.contains("idx_runs_project"));
    assertTrue(indexes.contains("idx_runs_spec"));
  }

  private void stageBelowFloor(int version) {
    db.execute(
        "CREATE TABLE schema_version (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
    for (var v = 1; v <= version; v++) {
      db.execute("INSERT INTO schema_version (version, applied_at) VALUES (?, 'staged')", v);
    }
  }

  private static List<String> canonicalSchema(Sqlite database) {
    return database.query(
        "SELECT type, name, tbl_name, sql FROM sqlite_master"
            + " WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'"
            + " ORDER BY type, name",
        row -> row.text(0) + "|" + row.text(1) + "|" + row.text(2) + "|" + canonical(row.text(3)));
  }

  private static String canonical(String sql) {
    return sql.replace("\"", "").replaceAll("\\s+", " ").replaceAll("\\s*([(),])\\s*", "$1").trim();
  }
}
