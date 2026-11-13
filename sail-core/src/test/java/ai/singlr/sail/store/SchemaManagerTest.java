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
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
            "attention",
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
        "Database schema v244 was made by an earlier Sail release; this release opens only v1003"
            + " and converts none: the box is reinstalled.",
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

  /**
   * The database 0.67.0 made, before a run recorded its cost: refused whole, the reinstall named,
   * nothing created or changed beside it.
   */
  @Test
  void aDatabaseTheReleaseBeforeMadeIsRefusedNamingAReinstallAndLeftAsItWas() {
    stageAtVersion(1002);
    db.execute("CREATE TABLE runs (id TEXT PRIMARY KEY)");
    var schema = new SchemaManager(db);

    var refusal = assertThrows(Remedied.State.class, schema::migrate);

    assertEquals(
        "Database schema v1002 was made by an earlier Sail release; this release opens only v1003"
            + " and converts none: the box is reinstalled.",
        refusal.reason());
    assertEquals(
        "Move " + tempDir.resolve("test.db") + " aside and run 'sail init' to make a fresh one.",
        refusal.remedy().plain());
    assertEquals(1002, schema.currentVersion());
    assertEquals(
        List.of("id"),
        db.query("SELECT name FROM pragma_table_info('runs')", row -> row.text(0)),
        "no column is added to a refused database");
    assertEquals(
        List.of("runs", "schema_version"),
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
            row -> row.text(0)));
  }

  @Test
  void aDatabaseSail058MadeIsRefusedNamingTheMigrateThatConvertsIt() {
    stageAtVersion(LegacyConversion.VERSION);
    var schema = new SchemaManager(db);

    var refusal = assertThrows(Remedied.State.class, schema::migrate);

    assertEquals(
        "Database " + tempDir.resolve("test.db") + " was made by Sail 0.58.", refusal.reason());
    assertEquals(List.of(Remedy.sail("migrate")), refusal.remedy().commands());
    assertEquals("Run 'sail migrate' to convert it to this release.", refusal.remedy().plain());
    assertEquals(LegacyConversion.VERSION, schema.currentVersion());
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
    assertEquals("Run 'sail upgrade' before opening this database.", refusal.remedy().plain());
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
            + " 'archived', 1767225600000, 1767225600000)");
    assertEquals(Instant.parse("2026-01-01T00:00:00Z"), since("archived_at"));

    db.execute("UPDATE specs SET title = 'renamed', updated_at = 1767312000000 WHERE id = 's'");
    assertEquals(
        Instant.parse("2026-01-01T00:00:00Z"),
        since("archived_at"),
        "an edit that keeps the status keeps it");

    db.execute(
        "UPDATE specs SET status = 'cancelled', updated_at = 1767398400000" + " WHERE id = 's'");
    assertNull(since("archived_at"));
    assertEquals(Instant.parse("2026-01-03T00:00:00Z"), since("cancelled_at"));

    db.execute("UPDATE specs SET status = 'pending' WHERE id = 's'");
    assertNull(since("archived_at"));
    assertNull(since("cancelled_at"));
  }

  /**
   * M4 at its source: ready is the first move into pending, written once; awaiting merge and done
   * the latest moves into them, kept as the change moves on to done and archived and cleared on any
   * other move; each the moving revision's own {@code updated_at}.
   */
  @Test
  void aSpecsReadyAwaitingMergeAndDoneTimesAreItsMovesOwn() {
    new SchemaManager(db).migrate();
    db.execute(
        "INSERT INTO specs (id, title, status, created_at, updated_at) VALUES ('s', 's',"
            + " 'pending', 1000, 1000)");
    move("in_progress", 2000);
    move("pending", 3000);
    move("awaiting_merge", 4000);
    db.execute("UPDATE specs SET title = 'renamed', updated_at = 4500 WHERE id = 's'");
    move("done", 5000);
    assertEquals(List.of(1000L, 4000L, 5000L), times());
    move("archived", 6000);
    assertEquals(List.of(1000L, 4000L, 5000L), times(), "a shipped change archived keeps them");

    move("pending", 7000);
    assertEquals(Arrays.asList(1000L, null, null), times(), "reopened, it holds no done");
    move("done", 8000);
    assertEquals(Arrays.asList(1000L, null, 8000L), times(), "done again, not through merge");
    db.execute(
        "INSERT INTO specs (id, title, status, created_at, updated_at) VALUES ('d', 'd', 'draft',"
            + " 1, 1)");
    assertEquals(
        Arrays.asList(null, null, null),
        db.query(
                "SELECT ready_at, awaiting_merge_at, done_at FROM specs WHERE id = 'd'",
                row -> Arrays.asList(row.instant(0), row.instant(1), row.instant(2)))
            .getFirst());
  }

  private void move(String status, long at) {
    db.execute("UPDATE specs SET status = ?, updated_at = ? WHERE id = 's'", status, at);
  }

  private List<Long> times() {
    return db.query(
            "SELECT ready_at, awaiting_merge_at, done_at FROM specs WHERE id = 's'",
            row ->
                Arrays.asList(
                    row.isNull(0) ? null : row.integer(0),
                    row.isNull(1) ? null : row.integer(1),
                    row.isNull(2) ? null : row.integer(2)))
        .getFirst();
  }

  /**
   * M14 at its source: a run's row finishing here adds it to its spec's kept sums, one leaving a
   * finished status takes it back, and deleting a row, as retention does, takes nothing; a spec
   * that arrives after its runs starts from the finished ones held.
   */
  @Test
  void aSpecsKeptSumsAreItsFinishedRunsAndDeletingARowLowersNone() {
    new SchemaManager(db).migrate();
    db.execute("INSERT INTO specs (id, title, created_at, updated_at) VALUES ('s', 's', 1, 1)");
    run("b1", "s", null, "build", "completed", 10, 0.5, "result");
    run("x1", "s", null, "fix", "running", 20, 1.0, null);
    run("w1", null, "s", "room", "failed", null, null, "none");
    run("o1", "other", null, "build", "completed", 99, 9.0, "result");
    assertEquals(List.of(2L, 1L, 0L, 1L, 1L, 10L, 0.5), kept("s"));

    db.execute("UPDATE runs SET status = 'stopped' WHERE id = 'x1'");
    assertEquals(List.of(3L, 1L, 1L, 2L, 1L, 30L, 1.5), kept("s"));
    db.execute("UPDATE runs SET status = 'running' WHERE id = 'x1'");
    assertEquals(List.of(2L, 1L, 0L, 1L, 1L, 10L, 0.5), kept("s"), "taken back out");
    db.execute("UPDATE runs SET status = 'stopped' WHERE id = 'x1'");
    db.execute("UPDATE runs SET exit_code = 3 WHERE id = 'x1'");
    assertEquals(List.of(3L, 1L, 1L, 2L, 1L, 30L, 1.5), kept("s"), "a finished row's edit nets 0");

    db.execute("DELETE FROM runs WHERE spec_id = 's' OR room_id = 's'");
    assertEquals(List.of(3L, 1L, 1L, 2L, 1L, 30L, 1.5), kept("s"), "erasing a run lowers nothing");

    run("l1", "late", null, "build", "completed", 5, 0.25, "result");
    run("l2", "late", null, "fix", "running", 5, 0.25, "result");
    db.execute("INSERT INTO specs (id, title, created_at, updated_at) VALUES ('late', 'l', 1, 1)");
    assertEquals(List.of(1L, 1L, 0L, 0L, 0L, 5L, 0.25), kept("late"));
  }

  private void run(
      String id,
      String spec,
      String room,
      String role,
      String status,
      Integer tokens,
      Double usd,
      String source) {
    db.execute(
        "INSERT INTO runs (id, project, spec_id, room_id, agent, role, status, started_at,"
            + " tokens_in, tokens_out, tokens_cache_read, tokens_cache_write, cost_usd,"
            + " usage_source) VALUES (?, 'acme', ?, ?, 'claude-code', ?, ?, 1, ?, ?, ?, ?, ?, ?)",
        id,
        spec,
        room,
        role,
        status,
        tokens,
        tokens == null ? null : 0,
        tokens == null ? null : 0,
        tokens == null ? null : 0,
        usd,
        source);
  }

  private List<Object> kept(String spec) {
    return db.query(
            "SELECT runs_total, runs_build, runs_fix, runs_without_usage, runs_without_cost,"
                + " run_tokens_in, run_cost_usd FROM specs WHERE id = ?",
            row ->
                List.<Object>of(
                    row.integer(0),
                    row.integer(1),
                    row.integer(2),
                    row.integer(3),
                    row.integer(4),
                    row.integer(5),
                    row.real(6)),
            spec)
        .getFirst();
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
            + " VALUES ('auth', 'OAuth', 'pending', 0, 0)");
    db.execute("INSERT INTO spec_content (spec_id, body, plan) VALUES ('auth', 'b', 'p')");

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

  private Instant since(String column) {
    return db.queryOne(
            "SELECT " + column + " FROM specs WHERE id = 's'",
            row -> Optional.ofNullable(row.instant(0)))
        .orElseThrow()
        .orElse(null);
  }

  private void stageAtVersion(int version) {
    db.execute(
        "CREATE TABLE schema_version (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
    db.execute("INSERT INTO schema_version (version, applied_at) VALUES (?, 'staged')", version);
  }
}
