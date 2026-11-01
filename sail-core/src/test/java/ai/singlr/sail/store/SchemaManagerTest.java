/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
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
  void aFreshDatabaseGetsEveryTableAndNoMigrationBookkeeping() {
    new SchemaManager(db).migrate();

    var tables =
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
            row -> row.text(0));
    assertEquals(
        List.of(
            "api_tokens",
            "blobs",
            "box_credential",
            "catalog",
            "change_heads",
            "change_log",
            "chunks",
            "compacted_entries",
            "container_leases",
            "enrollment_tickets",
            "erase_requests",
            "events",
            "fde_boxes",
            "fde_pairings",
            "fde_ssh_keys",
            "fdes",
            "main_limits",
            "materialized_files",
            "project_files",
            "project_grants",
            "projects",
            "review_findings",
            "review_stages",
            "reviews",
            "room_guard",
            "room_messages",
            "rooms",
            "run_credentials",
            "run_delivered_messages",
            "run_principals",
            "runs",
            "schema_version",
            "sessions",
            "slack_threads",
            "spec_content",
            "spec_dependencies",
            "spec_repos",
            "specs",
            "sqlite_sequence",
            "sync_conflicts",
            "sync_health",
            "sync_offers",
            "sync_scope",
            "sync_state",
            "webauthn_challenges",
            "webauthn_credentials"),
        tables);
    assertEquals(
        List.of(),
        db.query(
            "SELECT name FROM pragma_table_info('review_stages') WHERE name = 'finding_counts'",
            row -> row.text(0)));
  }

  @Test
  void aFreshDatabaseIsMintedACatalogOfItsOwnAndNamesNoMain() {
    new SchemaManager(db).migrate();

    var catalog = db.query("SELECT id, main FROM catalog", row -> row.text(0) + "|" + row.text(1));

    assertEquals(1, catalog.size());
    assertTrue(catalog.getFirst().matches("[0-9a-f]{32}\\|null"), catalog.getFirst());
  }

  @Test
  void aDatabaseOfAReleaseBeforeV1IsRefusedNamingAFreshOne() {
    stageAtVersion(244);
    var schema = new SchemaManager(db);

    var refusal = assertThrows(Remedied.State.class, schema::migrate);

    assertEquals(
        "Database schema v244 was made by a Sail release before v1; this release opens only v"
            + SchemaManager.CURRENT_VERSION
            + ".",
        refusal.reason());
    assertEquals(List.of(Remedy.sail("init")), refusal.remedy().commands());
    assertEquals(
        "Move " + tempDir.resolve("test.db") + " aside and run 'sail init' to make a fresh one.",
        refusal.remedy().plain());
    assertEquals(244, schema.currentVersion());
    assertEquals(
        List.of("schema_version"),
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'", row -> row.text(0)),
        "nothing is created beside a refused database");
  }

  @Test
  void migrateRefusesADatabaseNewerThanThisBinary() {
    var newer = SchemaManager.CURRENT_VERSION + 1;
    stageAtVersion(newer);
    var schema = new SchemaManager(db);

    var refusal = assertThrows(Remedied.State.class, schema::migrate);

    assertEquals(
        "Database schema v"
            + newer
            + " is newer than this Sail binary supports (v"
            + SchemaManager.CURRENT_VERSION
            + ").",
        refusal.reason());
    assertEquals(List.of(Remedy.sail("upgrade")), refusal.remedy().commands());
    assertEquals(newer, schema.currentVersion());
  }

  @Test
  void theLinksAPruneWalksAreIndexed() {
    new SchemaManager(db).migrate();

    for (var probe :
        List.of(
            "SELECT id FROM room_messages WHERE reply_to IN ('x')",
            "SELECT id FROM runs WHERE room_id IN ('x')",
            "SELECT id FROM rooms WHERE project IN ('x')",
            "SELECT id FROM specs WHERE room_id = 'x'",
            "SELECT run_id FROM run_delivered_messages WHERE message_id = 'x'",
            "SELECT entity_id FROM change_log WHERE entity_type = 'spec' AND kind = 'tombstone'")) {
      var plan =
          String.join(
              " ",
              db.query("EXPLAIN QUERY PLAN " + probe, row -> Objects.toString(row.text(3), "")));
      assertTrue(plan.contains("USING") && plan.contains("INDEX"), probe + " → " + plan);
    }
  }

  @Test
  void readingTheDeletionsOfATypeNeverScansTheHeads() {
    new SchemaManager(db).migrate();

    var plan =
        String.join(
            " ",
            db.query(
                "EXPLAIN QUERY PLAN " + ChangeLog.TOMBSTONED_BY,
                row -> Objects.toString(row.text(3), ""),
                "$.room_id",
                "spec"));

    assertFalse(plan.contains("SCAN"), plan);
  }

  @Test
  void theStatusTimesAreTheRevisionsThatMadeTheTransitionAndLeavingTheStatusClearsIt() {
    new SchemaManager(db).migrate();
    db.execute(
        "INSERT INTO specs (id, title, status, created_at, updated_at) VALUES ('s', 's',"
            + " 'archived', 't0', '2026-01-01T00:00:00Z')");
    assertEquals("2026-01-01T00:00:00Z", since("archived_at"));

    db.execute(
        "UPDATE specs SET title = 'renamed', updated_at = '2026-01-02T00:00:00Z' WHERE id = 's'");
    assertEquals(
        "2026-01-01T00:00:00Z", since("archived_at"), "an edit that keeps the status keeps it");

    db.execute(
        "UPDATE specs SET status = 'cancelled', updated_at = '2026-01-03T00:00:00Z'"
            + " WHERE id = 's'");
    assertNull(since("archived_at"));
    assertEquals("2026-01-03T00:00:00Z", since("cancelled_at"));

    db.execute("UPDATE specs SET status = 'pending' WHERE id = 's'");
    assertNull(since("archived_at"));
    assertNull(since("cancelled_at"));
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
  void freshInstallStampsTheCurrentVersion() {
    var schema = new SchemaManager(db);
    schema.migrate();
    assertEquals(SchemaManager.CURRENT_VERSION, schema.currentVersion());
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
    assertTrue(indexes.contains("idx_room_messages_page"));
  }

  @Test
  void theRunsRoleCheckAdmitsBothInviteLanesAndRefusesUnknownRoles() {
    new SchemaManager(db).migrate();

    db.execute(
        "INSERT INTO runs (id, project, agent, status, started_at, role)"
            + " VALUES ('i1', 'acme', 'claude-code', 'running', 't1', 'invite')");
    db.execute(
        "INSERT INTO runs (id, project, agent, status, started_at, role)"
            + " VALUES ('i2', 'acme', 'codex', 'running', 't1', 'invite-full')");

    assertEquals(
        "invite",
        db.queryOne("SELECT role FROM runs WHERE id = 'i1'", r -> r.text(0)).orElseThrow());
    assertEquals(
        "invite-full",
        db.queryOne("SELECT role FROM runs WHERE id = 'i2'", r -> r.text(0)).orElseThrow());
    assertThrows(
        RuntimeException.class,
        () ->
            db.execute(
                "INSERT INTO runs (id, project, agent, status, started_at, role)"
                    + " VALUES ('i3', 'acme', 'claude-code', 'running', 't1', 'bogus')"));
  }

  @Test
  void theRoomGuardTableExistsAndCascadesWithItsRun() {
    new SchemaManager(db).migrate();
    db.execute(
        "INSERT INTO runs (id, project, agent, status, started_at, role)"
            + " VALUES ('r9', 'acme', 'claude-code', 'running', 't1', 'room')");
    db.execute("INSERT INTO room_guard (run_id, baseline) VALUES ('r9', '{}')");

    db.execute("DELETE FROM runs WHERE id = 'r9'");

    assertTrue(
        db.queryOne("SELECT baseline FROM room_guard WHERE run_id = 'r9'", r -> r.text(0))
            .isEmpty(),
        "a deleted run takes its guard baseline with it");
  }

  private String since(String column) {
    var value =
        db.queryOne(
                "SELECT COALESCE(" + column + ", '') FROM specs WHERE id = 's'", row -> row.text(0))
            .orElseThrow();
    return value.isEmpty() ? null : value;
  }

  private void stageAtVersion(int version) {
    db.execute(
        "CREATE TABLE schema_version (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
    db.execute("INSERT INTO schema_version (version, applied_at) VALUES (?, 'staged')", version);
  }
}
