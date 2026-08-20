/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
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
  void backfillClosesOnlyTheResidueOfADoneSpecsPassedReview() {
    new SchemaManager(db).migrate();
    var specs = new SpecStore(db);
    var reviews = new ReviewStore(db);

    var residue = seedFinding(specs, reviews, "done-passed", SpecStatus.DONE, "passed", false);
    var wip = seedFinding(specs, reviews, "wip-passed", SpecStatus.IN_PROGRESS, "passed", false);
    var failed = seedFinding(specs, reviews, "done-failed", SpecStatus.DONE, "failed", false);
    var gone = seedFinding(specs, reviews, "done-superseded", SpecStatus.DONE, "passed", true);

    db.execute(SchemaManager.BACKFILL_SHIPPED_RESIDUE);

    assertEquals(
        Finding.Resolution.SHIPPED, reviews.findFinding(residue).orElseThrow().resolution());
    assertEquals(Finding.Resolution.OPEN, reviews.findFinding(wip).orElseThrow().resolution());
    assertEquals(Finding.Resolution.OPEN, reviews.findFinding(failed).orElseThrow().resolution());
    assertEquals(Finding.Resolution.OPEN, reviews.findFinding(gone).orElseThrow().resolution());
  }

  private String seedFinding(
      SpecStore specs,
      ReviewStore reviews,
      String specId,
      SpecStatus status,
      String reviewStatus,
      boolean superseded) {
    specs.create(
        new SpecStore.SpecRow(
            specId, "proj", "T", status, null, null, null, null, null, 0, null, "", "", null,
            List.of(), List.of()));
    var reviewId = reviews.createReview(specId, 1);
    var stageId = reviews.createStage(reviewId, "security", "agent");
    var finding =
        Finding.create(
            Finding.Severity.HIGH,
            Finding.Category.SECURITY,
            "src/A.java",
            1,
            2,
            "title",
            "desc",
            "evidence",
            new Finding.Suggestion("a", "b", "c"),
            0.5);
    reviews.addFinding(stageId, finding);
    reviews.updateReviewStatus(reviewId, reviewStatus);
    if (superseded) {
      reviews.supersedeForSpec(specId);
    }
    return finding.id();
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
    assertTrue(tables.contains("container_leases"));
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

      assertEquals(SchemaManager.CURRENT_VERSION, schema.currentVersion());
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
      assertEquals(SchemaManager.CURRENT_VERSION, schema.currentVersion());
    }
  }

  @Test
  void aMidRampDevelopmentBoxResumesAndConvergesToTheSameSchema() {
    try (var baseline = Sqlite.open(tempDir.resolve("baseline.db"));
        var midRamp = Sqlite.open(tempDir.resolve("mid-ramp.db"))) {
      new SchemaManager(baseline).migrate();
      FloorSchema.stage(midRamp);
      midRamp.execute("PRAGMA foreign_keys = OFF");
      for (var i = 0; i < 3; i++) {
        midRamp.execute(SchemaManager.ON_RAMP.get(i));
        midRamp.execute(
            "INSERT INTO schema_version (version, applied_at) VALUES (?, 'staged')",
            SchemaManager.FLOOR_VERSION + i + 1);
      }
      midRamp.execute("PRAGMA foreign_keys = ON");

      new SchemaManager(midRamp).migrate();

      assertEquals(SchemaManager.CURRENT_VERSION, new SchemaManager(midRamp).currentVersion());
      assertEquals(canonicalSchema(baseline), canonicalSchema(midRamp));
    }
  }

  @Test
  void migrateRefusesABelowFloorDatabaseWithTheRemedy() {
    stageAtVersion(37);
    var schema = new SchemaManager(db);

    var refusal = assertThrows(SchemaManager.PreFloorException.class, schema::migrate);

    assertTrue(refusal.getMessage().contains("schema v37"));
    assertTrue(refusal.getMessage().contains("schema v" + SchemaManager.FLOOR_VERSION));
    assertTrue(refusal.getMessage().contains("0.14"));
    assertTrue(refusal.getMessage().contains("sail migrate"));
    assertEquals(37, schema.currentVersion());
    assertEquals(
        List.of("schema_version"),
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
            row -> row.text(0)));
  }

  @Test
  void migrateRefusesTheVersionJustBelowTheFloor() {
    stageAtVersion(SchemaManager.FLOOR_VERSION - 1);

    assertThrows(SchemaManager.PreFloorException.class, () -> new SchemaManager(db).migrate());
  }

  @Test
  void migrateRefusesADatabaseNewerThanThisBinary() {
    stageAtVersion(SchemaManager.CURRENT_VERSION + 1);
    var schema = new SchemaManager(db);

    var refusal = assertThrows(IllegalStateException.class, schema::migrate);

    assertTrue(refusal.getMessage().contains("schema v" + (SchemaManager.CURRENT_VERSION + 1)));
    assertTrue(refusal.getMessage().contains("newer than this Sail binary"));
    assertEquals(SchemaManager.CURRENT_VERSION + 1, schema.currentVersion());
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
    assertTrue(indexes.contains("idx_spec_messages_page"));
  }

  private void stageAtVersion(int version) {
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

  @Test
  void postBaselineMigrationsCarryASeededV1DatabaseForward() {
    stageAtBaseline();
    db.execute(
        "INSERT INTO runs (id, project, agent, status, started_at)"
            + " VALUES ('r1', 'acme', 'claude-code', 'running', 't0')");

    new SchemaManager(db).migrate();

    assertEquals(SchemaManager.CURRENT_VERSION, new SchemaManager(db).currentVersion());
    var row =
        db.queryOne(
                "SELECT project, status, principal, owner FROM runs WHERE id = 'r1'",
                r ->
                    List.of(
                        r.text(0), r.text(1), String.valueOf(r.text(2)), String.valueOf(r.text(3))))
            .orElseThrow();
    assertEquals(List.of("acme", "running", "null", "null"), row);
    assertTrue(
        db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'run_credentials'",
                r -> r.text(0))
            .contains("run_credentials"));
    assertTrue(
        db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
                    + " AND name = 'run_delivered_messages'",
                r -> r.text(0))
            .contains("run_delivered_messages"));
    assertTrue(
        db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'spec_messages'",
                r -> r.text(0))
            .contains("spec_messages"));
    assertTrue(
        db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'box_credential'",
                r -> r.text(0))
            .contains("box_credential"));
    assertEquals(
        "acme",
        db.queryOne("SELECT project FROM runs WHERE id = 'r1'", r -> r.text(0)).orElseThrow());
    assertTrue(
        db.queryOne("SELECT last_activity_at IS NULL FROM runs WHERE id = 'r1'", r -> r.integer(0))
                .orElseThrow()
            == 1,
        "a pre-upgrade run derives a null activity stamp — presence readers must not guess");
  }

  @Test
  void aMidChainPostBaselineDatabaseResumesAndConvergesToTheSameSchema() {
    try (var fresh = Sqlite.open(tempDir.resolve("fresh.db"))) {
      new SchemaManager(fresh).migrate();
      stageAtBaseline();
      db.execute(SchemaManager.MIGRATIONS.getFirst());
      db.execute(
          "INSERT INTO schema_version (version, applied_at) VALUES (?, 'staged')",
          SchemaManager.V1_VERSION + 1);

      new SchemaManager(db).migrate();

      assertEquals(SchemaManager.CURRENT_VERSION, new SchemaManager(db).currentVersion());
      assertEquals(canonicalSchema(fresh), canonicalSchema(db));
    }
  }

  private static int migrationIndex(String needle) {
    var migrations = SchemaManager.MIGRATIONS;
    for (var i = 0; i < migrations.size(); i++) {
      if (migrations.get(i).contains(needle)) {
        return i;
      }
    }
    throw new AssertionError("no migration contains: " + needle);
  }

  @Test
  void migrationsAddedAfterV0_20_0ComeAfterItsTail() {
    assertTrue(
        migrationIndex("CREATE TABLE run_delivered_messages")
            < migrationIndex("CREATE TABLE run_principals"),
        "run_delivered_messages was v0.20.0's last migration, so everything added after it —"
            + " run_principals included — must be appended, never inserted before it. Migrations"
            + " are addressed by list index; inserting before a shipped release's tail re-points"
            + " every later version at different SQL and breaks that release's upgrade.");
  }

  @Test
  void aV0_20_0ShapedDatabaseUpgradesWithoutRunningTheInsertBeforeItsCreate() {
    stageAtBaseline();
    var tail = migrationIndex("CREATE TABLE run_delivered_messages");
    db.execute("PRAGMA foreign_keys = OFF");
    SchemaManager.MIGRATIONS.subList(0, tail + 1).forEach(db::execute);
    db.execute("PRAGMA foreign_keys = ON");
    db.execute(
        "INSERT INTO schema_version (version, applied_at) VALUES (?, 'staged')",
        SchemaManager.V1_VERSION + tail + 1);
    db.execute(
        "INSERT INTO specs (id, title, status, created_at, updated_at)"
            + " VALUES ('auth', 'T', 'done', 't0', 't0')");
    db.execute(
        "INSERT INTO runs (id, project, spec_id, agent, status, started_at, role, principal, owner)"
            + " VALUES ('r9', 'acme', 'auth', 'codex', 'completed', 't0', 'build', 'codex/r9',"
            + " 'uday')");

    var schema = new SchemaManager(db);
    schema.migrate();

    assertEquals(
        SchemaManager.CURRENT_VERSION,
        schema.currentVersion(),
        "a v0.20.0 box must ride the whole 0.21 chain — run_principals, session columns, the runs"
            + " rebuild, and room_guard — not halt at the first entry added since it shipped");
    assertEquals(
        "codex/r9",
        db.queryOne("SELECT principal FROM run_principals WHERE run_id = 'r9'", r -> r.text(0))
            .orElseThrow(
                () ->
                    new AssertionError(
                        "run_principals must exist and be back-filled from runs.principal — the"
                            + " incident was INSERT running before CREATE on a v0.20.0 box")));
    assertEquals(
        "ok",
        db.queryOne("PRAGMA integrity_check", r -> r.text(0)).orElseThrow(),
        "the upgraded database is structurally sound");
  }

  @Test
  void aV0_24_0ShapedDatabaseGainsTheContainerLeasesTable() {
    stageAtBaseline();
    var tail = migrationIndex("CREATE TABLE container_leases");
    db.execute("PRAGMA foreign_keys = OFF");
    SchemaManager.MIGRATIONS.subList(0, tail).forEach(db::execute);
    db.execute("PRAGMA foreign_keys = ON");
    db.execute(
        "INSERT INTO schema_version (version, applied_at) VALUES (?, 'staged')",
        SchemaManager.V1_VERSION + tail);

    new SchemaManager(db).migrate();

    assertEquals(SchemaManager.CURRENT_VERSION, new SchemaManager(db).currentVersion());
    assertTrue(
        db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
                    + " AND name = 'container_leases'",
                r -> r.text(0))
            .contains("container_leases"),
        "a released 0.24.x box must gain the exclusive-container-lease table on upgrade");
  }

  private void stageAtBaseline() {
    FloorSchema.stage(db);
    db.execute("PRAGMA foreign_keys = OFF");
    SchemaManager.ON_RAMP.forEach(db::execute);
    db.execute("PRAGMA foreign_keys = ON");
    db.execute(
        "INSERT INTO schema_version (version, applied_at) VALUES (?, 'staged')",
        SchemaManager.V1_VERSION);
  }

  @Test
  void theRunsRebuildCarriesRowsAndChildLedgersForwardAndAdmitsRoomRuns() {
    stageAtBaseline();
    var priorEntries = migrationIndex("CREATE TABLE runs_v5") + 1;
    db.execute("PRAGMA foreign_keys = OFF");
    SchemaManager.MIGRATIONS.subList(0, priorEntries).forEach(db::execute);
    db.execute("PRAGMA foreign_keys = ON");
    db.execute(
        "INSERT INTO schema_version (version, applied_at) VALUES (?, 'staged')",
        SchemaManager.V1_VERSION + priorEntries);
    db.execute(
        "INSERT INTO specs (id, title, status, created_at, updated_at)"
            + " VALUES ('auth', 'T', 'done', 't0', 't0')");
    db.execute(
        "INSERT INTO runs (id, project, spec_id, agent, status, started_at, role, principal,"
            + " owner, session_id) VALUES ('r1', 'acme', 'auth', 'claude-code', 'completed',"
            + " 't0', 'build', 'claude/r1', 'uday', 'sess-1')");
    db.execute("INSERT INTO run_principals (run_id, principal) VALUES ('r1', 'claude/r1')");
    db.execute(
        "INSERT INTO spec_messages (id, spec_id, author, body, created_at, rev)"
            + " VALUES ('0195a2f0-0000-7000-8000-000000000001', 'auth', 'uday', 'hi', 't0', '1-a')");
    db.execute(
        "INSERT INTO run_delivered_messages (run_id, message_id)"
            + " VALUES ('r1', '0195a2f0-0000-7000-8000-000000000001')");

    new SchemaManager(db).migrate();

    var survived =
        db.queryOne(
                "SELECT project, role, principal, owner, session_id FROM runs WHERE id = 'r1'",
                r -> List.of(r.text(0), r.text(1), r.text(2), r.text(3), r.text(4)))
            .orElseThrow();
    assertEquals(List.of("acme", "build", "claude/r1", "uday", "sess-1"), survived);
    assertEquals(
        1,
        (int)
            db.queryOne(
                    "SELECT COUNT(*) FROM run_principals WHERE run_id = 'r1'",
                    r -> (int) r.integer(0))
                .orElseThrow(),
        "the rebuild must never cascade the principal history away");
    assertEquals(
        1,
        (int)
            db.queryOne(
                    "SELECT COUNT(*) FROM run_delivered_messages WHERE run_id = 'r1'",
                    r -> (int) r.integer(0))
                .orElseThrow(),
        "the rebuild must never cascade the delivery ledger away");
    db.execute(
        "INSERT INTO runs (id, project, agent, status, started_at, role)"
            + " VALUES ('r2', 'acme', 'claude-code', 'running', 't1', 'room')");
    assertEquals(
        "room", db.queryOne("SELECT role FROM runs WHERE id = 'r2'", r -> r.text(0)).orElseThrow());
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

  @Test
  void theQuestionColumnArrivesDefaultedOnPreUpgradeMessages() {
    stageAtBaseline();
    var priorEntries = migrationIndex("ADD COLUMN question");
    db.execute("PRAGMA foreign_keys = OFF");
    SchemaManager.MIGRATIONS.subList(0, priorEntries).forEach(db::execute);
    db.execute("PRAGMA foreign_keys = ON");
    db.execute(
        "INSERT INTO schema_version (version, applied_at) VALUES (?, 'staged')",
        SchemaManager.V1_VERSION + priorEntries);
    db.execute(
        "INSERT INTO specs (id, title, status, created_at, updated_at)"
            + " VALUES ('auth', 'T', 'pending', 't0', 't0')");
    db.execute(
        "INSERT INTO spec_messages (id, spec_id, author, body, created_at, rev)"
            + " VALUES ('0195a2f0-0000-7000-8000-000000000002', 'auth', 'uday', 'hi', 't0',"
            + " '1-a')");

    new SchemaManager(db).migrate();

    assertEquals(
        0L,
        (long)
            db.queryOne(
                    "SELECT question FROM spec_messages"
                        + " WHERE id = '0195a2f0-0000-7000-8000-000000000002'",
                    r -> r.integer(0))
                .orElseThrow(),
        "a pre-upgrade message was never a question");
    assertTrue(new MessageStore(db).append("auth", "claude/r1", "stuck?", null, true).question());
  }

  @Test
  void aV0_27_0ShapedDatabaseGainsTheEngagementColumn() {
    stageAtBaseline();
    var tail = migrationIndex("ALTER TABLE specs ADD COLUMN engagement");
    db.execute("PRAGMA foreign_keys = OFF");
    SchemaManager.MIGRATIONS.subList(0, tail).forEach(db::execute);
    db.execute("PRAGMA foreign_keys = ON");
    db.execute(
        "INSERT INTO schema_version (version, applied_at) VALUES (?, 'staged')",
        SchemaManager.V1_VERSION + tail);
    db.execute(
        "INSERT INTO specs (id, title, status, created_at, updated_at)"
            + " VALUES ('pre', 'T', 'draft', 't0', 't0')");
    db.execute(
        "INSERT INTO runs (id, project, agent, status, started_at, role) VALUES"
            + " ('r-pre', 'p', 'keep', 'completed', 't0', 'room')");

    new SchemaManager(db).migrate();

    assertEquals(SchemaManager.CURRENT_VERSION, new SchemaManager(db).currentVersion());
    assertTrue(
        db.queryOne("SELECT engagement FROM specs WHERE id = 'pre'", r -> r.isNull(0))
            .orElseThrow(),
        "a pre-upgrade spec reads as not engaged");
    assertEquals(
        "keep",
        db.queryOne("SELECT agent FROM runs WHERE id = 'r-pre'", r -> r.text(0)).orElseThrow(),
        "the runs_v7 rebuild carries prior rows forward");
    db.execute(
        "INSERT INTO runs (id, project, agent, status, started_at, role) VALUES"
            + " ('r-chat', 'p', 'claude-code', 'running', 't0', 'room-full')");
    assertThrows(
        SqliteException.class,
        () ->
            db.execute(
                "INSERT INTO runs (id, project, agent, status, started_at, role) VALUES"
                    + " ('r-bad', 'p', 'claude-code', 'running', 't0', 'shout')"));
    db.execute("UPDATE specs SET engagement = '{\"agent\":\"claude-code\"}' WHERE id = 'pre'");
    assertEquals(
        "{\"agent\":\"claude-code\"}",
        db.queryOne("SELECT engagement FROM specs WHERE id = 'pre'", r -> r.text(0)).orElseThrow());
  }

  @Test
  void theWakeColumnAdmitsItsThreeModesAndRejectsGarbage() {
    new SchemaManager(db).migrate();
    db.execute(
        "INSERT INTO specs (id, title, status, created_at, updated_at, wake)"
            + " VALUES ('auth', 'T', 'pending', 't0', 't0', 'mention')");
    assertEquals(
        "mention",
        db.queryOne("SELECT wake FROM specs WHERE id = 'auth'", r -> r.text(0)).orElseThrow());
    assertThrows(
        SqliteException.class,
        () -> db.execute("UPDATE specs SET wake = 'loud' WHERE id = 'auth'"));
  }

  @Test
  void theFindingsRebuildCarriesRowsAndSourceLinksForwardAndAdmitsDisputed() {
    stageAtBaseline();
    db.execute(
        "INSERT INTO specs (id, title, status, created_at, updated_at)"
            + " VALUES ('auth', 'T', 'done', 't0', 't0')");
    db.execute(
        "INSERT INTO reviews (id, spec_id, iteration, status, created_at)"
            + " VALUES ('r1', 'auth', 1, 'failed', 't0')");
    db.execute(
        "INSERT INTO review_stages (id, review_id, name, stage_type, status)"
            + " VALUES ('s1', 'r1', 'security', 'agent', 'failed')");
    db.execute(
        "INSERT INTO review_findings (id, stage_id, severity, category, title, description,"
            + " confidence, resolution) VALUES ('f1', 's1', 'HIGH', 'SECURITY', 'Leak', 'D',"
            + " 0.9, 'OPEN')");
    db.execute("INSERT INTO spec_source_findings (spec_id, finding_id) VALUES ('auth', 'f1')");

    new SchemaManager(db).migrate();

    var survived =
        db.queryOne(
                "SELECT title, resolution, carried_from, resolution_evidence, carry_evidence"
                    + " FROM review_findings WHERE id = 'f1'",
                r ->
                    List.of(
                        r.text(0),
                        r.text(1),
                        String.valueOf(r.text(2)),
                        String.valueOf(r.text(3)),
                        String.valueOf(r.text(4))))
            .orElseThrow();
    assertEquals(List.of("Leak", "OPEN", "null", "null", "null"), survived);
    assertEquals(
        1,
        (int)
            db.queryOne(
                    "SELECT COUNT(*) FROM spec_source_findings WHERE finding_id = 'f1'",
                    r -> (int) r.integer(0))
                .orElseThrow(),
        "the rebuild must never cascade the follow-up links away");
    db.execute("UPDATE review_findings SET resolution = 'DISPUTED' WHERE id = 'f1'");
    assertEquals(
        "DISPUTED",
        db.queryOne("SELECT resolution FROM review_findings WHERE id = 'f1'", r -> r.text(0))
            .orElseThrow());
  }
}
