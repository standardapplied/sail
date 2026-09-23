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

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
  void fileVersionIndexMigratesExistingContentAndPermissionHistory() {
    stageAtBaseline();
    var prior = migrationIndex("CREATE INDEX idx_change_log_file_version");
    db.execute("PRAGMA foreign_keys = OFF");
    SchemaManager.MIGRATIONS.subList(0, prior).forEach(db::execute);
    db.execute("PRAGMA foreign_keys = ON");
    db.execute(
        "INSERT INTO schema_version (version, applied_at) VALUES (?, 'staged')",
        SchemaManager.V1_VERSION + prior);
    new ContentMigration().apply(db, null, DataMigration.Prompter.NON_INTERACTIVE);
    var hash = new BlobStore(db).putText("content");
    for (var mode : List.of(0644, 0600)) {
      db.execute(
          """
          INSERT INTO change_log (entity_type, entity_id, rev, recorded_at, origin, deleted,
              snapshot)
          VALUES ('file', 'acme/known', ?, 'staged', 'local', 0, ?)""",
          "1-" + mode,
          YamlUtil.dumpJson(
              Map.of("project", "acme", "path", "known", "content_hash", hash, "mode", mode)));
    }

    new SchemaManager(db).migrate();
    var files = new FileStore(db);

    assertTrue(files.isKnownVersion("acme/known", hash, 0644));
    assertTrue(files.isKnownVersion("acme/known", hash, 0600));
    assertFalse(files.isKnownVersion("acme/known", hash, 0755));
    var plan =
        db.query(
            """
            EXPLAIN QUERY PLAN SELECT 1 FROM change_log WHERE entity_type = 'file' AND entity_id = ?
                AND json_extract(snapshot, '$.content_hash') = ?
                AND json_extract(snapshot, '$.mode') = ? LIMIT 1
            """,
            row -> row.text(3),
            "acme/known",
            hash,
            0644);
    assertTrue(
        plan.stream().anyMatch(step -> step.contains("idx_change_log_file_version")),
        plan.toString());
  }

  @Test
  void fromThePriorReleaseTheLogLearnsKindsAndArchivedSpecsTheirStatusTimes() {
    stageAtBaseline();
    var prior = migrationIndex("ALTER TABLE change_log ADD COLUMN kind");
    db.execute("PRAGMA foreign_keys = OFF");
    SchemaManager.MIGRATIONS.subList(0, prior).forEach(db::execute);
    db.execute("PRAGMA foreign_keys = ON");
    db.execute(
        "INSERT INTO schema_version (version, applied_at) VALUES (?, 'staged')",
        SchemaManager.V1_VERSION + prior);
    for (var entry :
        List.of(List.of("kept", "1-a", 0), List.of("gone", "1-b", 0), List.of("gone", "2-c", 1))) {
      db.execute(
          """
          INSERT INTO change_log (entity_type, entity_id, rev, recorded_at, origin, deleted,
              snapshot)
          VALUES ('spec', ?, ?, 'staged', 'local', ?, '{}')""",
          entry.get(0),
          entry.get(1),
          entry.get(2));
    }
    for (var spec :
        List.of(List.of("a", "archived"), List.of("c", "cancelled"), List.of("p", "pending"))) {
      db.execute(
          "INSERT INTO specs (id, title, status, created_at, updated_at) VALUES (?, ?, ?, 'then',"
              + " '2026-06-01T00:00:00Z')",
          spec.get(0),
          spec.get(0),
          spec.get(1));
    }

    new SchemaManager(db).migrate();

    assertEquals(
        List.of("revision", "revision", "tombstone"),
        db.query("SELECT kind FROM change_log ORDER BY seq", row -> row.text(0)));
    assertEquals(
        List.of("a|2026-06-01T00:00:00Z|", "c||2026-06-01T00:00:00Z", "p||"),
        db.query(
            "SELECT id || '|' || COALESCE(archived_at, '') || '|' || COALESCE(cancelled_at, '')"
                + " FROM specs ORDER BY id",
            row -> row.text(0)));
    assertThrows(
        SqliteException.class,
        () ->
            db.execute(
                "INSERT INTO change_log (entity_type, entity_id, rev, recorded_at, origin,"
                    + " snapshot, kind) VALUES ('spec', 'x', '1', 'now', 'local', '{}', 'bogus')"));
    assertEquals(
        0, db.queryOne("SELECT count(*) FROM erase_requests", row -> row.integer(0)).orElseThrow());
  }

  @Test
  void aDeletionTheOldServerWritesDuringTheUpgradeIsStillATombstone() {
    new SchemaManager(db).migrate();

    db.execute(
        """
        INSERT INTO change_log (entity_type, entity_id, rev, recorded_at, origin, deleted, snapshot)
        VALUES ('spec', 'gone', '2-old', 'now', 'local', 1, '{}')""");
    db.execute(
        """
        INSERT INTO change_log (entity_type, entity_id, rev, recorded_at, origin, deleted, snapshot)
        VALUES ('spec', 'kept', '1-old', 'now', 'local', 0, '{}')""");

    assertEquals(
        List.of("gone|tombstone", "kept|revision"),
        db.query(
            "SELECT entity_id || '|' || kind FROM change_log ORDER BY entity_id",
            row -> row.text(0)));
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
  void theStatusTimesFollowEveryStatusWriteAndLeavingTheStatusClearsIt() {
    new SchemaManager(db).migrate();
    db.execute(
        "INSERT INTO specs (id, title, status, created_at, updated_at) VALUES ('s', 's',"
            + " 'archived', 'now', 'now')");
    var born = since("archived_at");
    assertTrue(born != null && born.endsWith("Z"), "an archived birth is stamped: " + born);

    db.execute("UPDATE specs SET title = 'renamed' WHERE id = 's'");
    assertEquals(born, since("archived_at"), "an edit that keeps the status keeps its time");

    db.execute("UPDATE specs SET status = 'cancelled' WHERE id = 's'");
    assertNull(since("archived_at"));
    assertTrue(since("cancelled_at") != null);

    db.execute("UPDATE specs SET status = 'pending' WHERE id = 's'");
    assertNull(since("archived_at"));
    assertNull(since("cancelled_at"));
  }

  private String since(String column) {
    var value =
        db.queryOne(
                "SELECT COALESCE(" + column + ", '') FROM specs WHERE id = 's'", row -> row.text(0))
            .orElseThrow();
    return value.isEmpty() ? null : value;
  }

  @Test
  void syncHealthMigratesFromThePriorReleaseWithoutChangingTheReplica() {
    stageAtBaseline();
    var prior = migrationIndex("CREATE TABLE sync_health");
    db.execute("PRAGMA foreign_keys = OFF");
    SchemaManager.MIGRATIONS.subList(0, prior).forEach(db::execute);
    db.execute("PRAGMA foreign_keys = ON");
    db.execute(
        "INSERT INTO schema_version (version, applied_at) VALUES (?, 'staged')",
        SchemaManager.V1_VERSION + prior);
    db.execute(
        "INSERT INTO specs (id, title, status, created_at, updated_at) VALUES ('keep', 'Keep', 'draft', 't0', 't0')");
    new SchemaManager(db).migrate();
    var health = new SyncHealth(db);
    assertTrue(health.find("main").isEmpty());
    assertTrue(new SpecStore(db).findById("keep").isPresent());
    health.begin("main", java.time.Instant.EPOCH);
    new SchemaManager(db).migrate();
    assertEquals("syncing", health.find("main").orElseThrow().state());
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
    assertTrue(indexes.contains("idx_room_messages_page"));
  }

  @Test
  void theMessageRekeyCarriesRowsAndTheDeliveryLedgerAcrossTheRename() {
    var staged = SchemaManager.V1_VERSION + migrationIndex("CREATE TABLE room_messages");
    stageAtBaseline();
    for (var v = SchemaManager.V1_VERSION + 1; v <= staged; v++) {
      db.execute(SchemaManager.MIGRATIONS.get(v - SchemaManager.V1_VERSION - 1));
      db.execute("INSERT INTO schema_version (version, applied_at) VALUES (?, 'staged')", v);
    }
    db.execute(
        "INSERT INTO spec_messages (id, spec_id, author, body, created_at, rev, question)"
            + " VALUES ('0195a2f0-0000-7000-8000-00000000000a', 'auth', 'uday', 'hi', 't0',"
            + " '1-a', 1)");
    db.execute(
        "INSERT INTO runs (id, project, agent, status, started_at)"
            + " VALUES ('r-keep', 'acme', 'claude-code', 'completed', 't0')");
    db.execute(
        "INSERT INTO run_delivered_messages (run_id, message_id)"
            + " VALUES ('r-keep', '0195a2f0-0000-7000-8000-00000000000a')");

    new SchemaManager(db).migrate();

    assertEquals(
        "auth",
        db.queryOne(
                "SELECT room_id FROM room_messages"
                    + " WHERE id = '0195a2f0-0000-7000-8000-00000000000a'",
                r -> r.text(0))
            .orElseThrow(),
        "the re-key is a pure rename: rows carry over with the same room id");
    assertEquals(
        1,
        db.queryOne(
                "SELECT question FROM room_messages"
                    + " WHERE id = '0195a2f0-0000-7000-8000-00000000000a'",
                r -> r.integer(0))
            .orElseThrow());
    db.execute("PRAGMA foreign_keys = ON");
    db.execute("DELETE FROM room_messages WHERE id = '0195a2f0-0000-7000-8000-00000000000a'");
    assertEquals(
        0,
        db.query("SELECT run_id FROM run_delivered_messages", r -> r.text(0)).size(),
        "the delivery ledger's FK followed the rename and still cascades");
  }

  @Test
  void specsGainRoomIdBackfilledToTheirOwnIdOnUpgrade() {
    var staged = SchemaManager.V1_VERSION + migrationIndex("ALTER TABLE specs ADD COLUMN room_id");
    stageAtBaseline();
    for (var v = SchemaManager.V1_VERSION + 1; v <= staged; v++) {
      db.execute(SchemaManager.MIGRATIONS.get(v - SchemaManager.V1_VERSION - 1));
      db.execute("INSERT INTO schema_version (version, applied_at) VALUES (?, 'staged')", v);
    }
    db.execute(
        "INSERT INTO specs (id, title, status, priority, created_at, updated_at, project,"
            + " updated_by) VALUES ('auth', 'OAuth', 'done', 0, 'c', 'u', 'acme', 'uday')");

    new SchemaManager(db).migrate();

    assertEquals(
        "auth",
        db.queryOne("SELECT room_id FROM specs WHERE id = 'auth'", r -> r.text(0)).orElseThrow(),
        "a pre-decouple spec backfills its identity room id");
    assertTrue(
        db.query("PRAGMA table_info(runs)", r -> r.text(1)).contains("room_id"),
        "chat runs can carry their room");
  }

  private void stageAtVersion(int version) {
    db.execute(
        "CREATE TABLE schema_version (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
    for (var v = 1; v <= version; v++) {
      db.execute("INSERT INTO schema_version (version, applied_at) VALUES (?, 'staged')", v);
    }
  }

  private static List<String> canonicalSchema(Sqlite database) {
    new ContentMigration().apply(database, null, DataMigration.Prompter.NON_INTERACTIVE);
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
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'room_messages'",
                r -> r.text(0))
            .contains("room_messages"));
    assertTrue(
        db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'rooms'",
                r -> r.text(0))
            .contains("rooms"));
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
                    "SELECT question FROM room_messages"
                        + " WHERE id = '0195a2f0-0000-7000-8000-000000000002'",
                    r -> r.integer(0))
                .orElseThrow(),
        "a pre-upgrade message was never a question");
    assertTrue(new MessageStore(db).append("auth", "claude/r1", "stuck?", null, true).question());
  }

  @Test
  void aV0_27_0ShapedDatabaseConvergesAndShedsTheLegacyColumns() {
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
        db.query(
                "SELECT name FROM pragma_table_info('specs') WHERE name IN ('wake', 'engagement')",
                r -> r.text(0))
            .isEmpty(),
        "the conversation-side columns retire with the rebuild");
    assertEquals(
        "T", db.queryOne("SELECT title FROM specs WHERE id = 'pre'", r -> r.text(0)).orElseThrow());
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
  }

  @Test
  void theSpecsRebuildShedsConversationColumnsAndKeepsRowsChildrenAndConstraints() {
    var staged = SchemaManager.V1_VERSION + migrationIndex("CREATE TABLE specs_v2");
    stageAtBaseline();
    db.execute("PRAGMA foreign_keys = OFF");
    for (var v = SchemaManager.V1_VERSION + 1; v <= staged; v++) {
      db.execute(SchemaManager.MIGRATIONS.get(v - SchemaManager.V1_VERSION - 1));
      db.execute("INSERT INTO schema_version (version, applied_at) VALUES (?, 'staged')", v);
    }
    db.execute("PRAGMA foreign_keys = ON");
    db.execute(
        "INSERT INTO specs (id, title, status, created_at, updated_at, project, wake, engagement,"
            + " room_id) VALUES ('auth', 'OAuth', 'pending', 't0', 't1', 'acme', 'on',"
            + " '{\"agent\":\"claude-code\"}', 'auth')");
    db.execute(
        "INSERT INTO specs (id, title, status, created_at, updated_at) VALUES"
            + " ('base', 'Base', 'done', 't0', 't0')");
    db.execute("INSERT INTO spec_dependencies (spec_id, depends_on) VALUES ('auth', 'base')");
    db.execute("INSERT INTO spec_repos (spec_id, repo) VALUES ('auth', 'api')");
    db.execute(
        "INSERT INTO spec_content (spec_id, body, plan, updated_at) VALUES ('auth', 'B', 'P', 't0')");

    new SchemaManager(db).migrate();

    assertEquals(SchemaManager.CURRENT_VERSION, new SchemaManager(db).currentVersion());
    assertTrue(
        db.query(
                "SELECT name FROM pragma_table_info('specs') WHERE name IN ('wake', 'engagement')",
                r -> r.text(0))
            .isEmpty());
    assertEquals(
        "OAuth",
        db.queryOne("SELECT title FROM specs WHERE id = 'auth'", r -> r.text(0)).orElseThrow());
    assertEquals(
        "auth",
        db.queryOne("SELECT room_id FROM specs WHERE id = 'auth'", r -> r.text(0)).orElseThrow());
    assertEquals(
        "base",
        db.queryOne(
                "SELECT depends_on FROM spec_dependencies WHERE spec_id = 'auth'", r -> r.text(0))
            .orElseThrow());
    assertEquals(
        "B",
        db.queryOne("SELECT body FROM spec_content WHERE spec_id = 'auth'", r -> r.text(0))
            .orElseThrow());
    assertTrue(
        db.query("SELECT * FROM pragma_foreign_key_check", r -> r.text(0)).isEmpty(),
        "the rebuild leaves no dangling child references");
    assertTrue(
        db.query(
                    "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'idx_specs_project'",
                    r -> r.text(0))
                .size()
            == 1);
    assertThrows(
        SqliteException.class,
        () ->
            db.execute(
                "INSERT INTO specs (id, title, status, created_at, updated_at) VALUES"
                    + " ('bad', 'T', 'shouting', 't0', 't0')"));
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
