/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ProjectRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyDataMigrationTest {

  @TempDir Path tempDir;
  private Path dbPath;
  private Sqlite db;

  @BeforeEach
  void setUp() {
    dbPath = tempDir.resolve("legacy.db");
    db = Sqlite.open(dbPath);
    stageLegacySchema();
  }

  @AfterEach
  void tearDown() {
    if (db != null) {
      db.close();
    }
  }

  @Test
  void migrationCarriesEveryLegacyShapeForwardOnce() throws Exception {
    seedLegacyRows();
    var projects = projectRegistry("acme");

    var first = migrate(projects);

    var build = new RunStore(db).findById("legacy-build").orElseThrow();
    assertEquals("build", build.role());
    assertEquals("stopped", build.status());
    assertNotNull(build.completedAt());
    assertEquals("node-a", build.node());
    assertTrue(new RunStore(db).runningForProjectOnNode("acme", "node-a").isEmpty());

    var review = new RunStore(db).findById("review-run").orElseThrow();
    assertEquals("review", review.role());
    assertEquals("running", review.status());
    assertEquals("", review.unit());

    assertEquals(
        "acme",
        db.queryOne("SELECT project FROM specs WHERE id = 'legacy-spec'", row -> row.text(0))
            .orElseThrow());
    assertEquals(0, first.getFirst().report().ambiguous());
    assertEquals(
        List.of("project", "review", "run", "spec"),
        db.query(
            "SELECT DISTINCT entity_type FROM change_log ORDER BY entity_type",
            row -> row.text(0)));

    assertRoleConstraint();
    assertProjectConstraint();
    db.execute("DELETE FROM fdes WHERE id = 'fde-1'");
    assertEquals(
        0L,
        db.queryOne(
                "SELECT COUNT(*) FROM api_tokens WHERE token_hash = 'token-1'",
                row -> row.integer(0))
            .orElseThrow());
    assertEquals(
        0L,
        db.queryOne(
                """
                SELECT COUNT(*) FROM api_tokens
                WHERE token_hash = 'orphan-token' AND fde_id IS NULL""",
                row -> row.integer(0))
            .orElseThrow());

    var revisions = changeLogCount();
    var second =
        new DataMigrator(db, List.of(new LegacyDataMigration(() -> "node-a")))
            .run(projects, DataMigration.Prompter.NON_INTERACTIVE);

    assertTrue(second.getFirst().alreadyApplied());
    assertEquals(revisions, changeLogCount());
  }

  @Test
  void currentForegroundBuildRetainsItsRepositoryReservation() throws Exception {
    db.execute(
        """
        INSERT INTO runs
            (id, project, agent, status, started_at, node, role, unit, repos)
        VALUES
            ('foreground', 'acme', 'codex', 'running', '2026-01-01',
                'node-a', 'build', '', '["repo"]')""");

    migrate(projectRegistry("acme"));

    var foreground = new RunStore(db).findById("foreground").orElseThrow();
    assertEquals("running", foreground.status());
    assertEquals(List.of("repo"), foreground.repos());
  }

  @Test
  void unresolvedProjectlessSpecsAbortBeforeJournalingAndCanBeRetried() throws Exception {
    db.execute(
        """
        INSERT INTO specs (id, title, status, created_at, updated_at, project)
        VALUES ('orphan', 'Orphan', 'pending', '2026-01-01', '2026-01-01', NULL)""");
    var projects = projectRegistry("acme", "beta");

    var failure = assertThrows(IllegalStateException.class, () -> migrate(projects));

    assertTrue(failure.getMessage().contains("orphan"));
    assertEquals(0L, changeLogCount());
    assertEquals(
        0L,
        db.queryOne(
                "SELECT COUNT(*) FROM data_migrations WHERE name = ?",
                row -> row.integer(0),
                LegacyDataMigration.NAME)
            .orElseThrow());
    assertEquals(
        1L,
        db.queryOne(
                """
                SELECT COUNT(*) FROM specs
                WHERE id = 'orphan' AND project = 'unassigned'""",
                row -> row.integer(0))
            .orElseThrow());

    db.execute("UPDATE specs SET project = 'acme' WHERE id = 'orphan'");

    var retry = migrate(projects).getFirst();

    assertEquals(1, retry.report().applied());
    assertEquals(List.of("orphan"), new SpecStore(db).syncEntityIds().stream().sorted().toList());
    assertEquals(
        1L,
        db.queryOne(
                "SELECT COUNT(*) FROM data_migrations WHERE name = ?",
                row -> row.integer(0),
                LegacyDataMigration.NAME)
            .orElseThrow());
  }

  @Test
  void attributingAnAlreadyJournaledSpecMintsARevisionForTheNewSnapshot() throws Exception {
    db.execute(
        """
        INSERT INTO specs (id, title, status, created_at, updated_at, project)
        VALUES ('shared', 'Shared', 'pending', '2026-01-01', '2026-01-01', NULL)""");
    var legacyStore = new SpecStore(db);
    assertEquals(1, legacyStore.backfillRevisions());
    var legacyRev = legacyStore.latestRev("shared");
    assertNull(legacyStore.comparableAtRev("shared", legacyRev).get("project"));

    migrate(projectRegistry("acme"));

    var migratedStore = new SpecStore(db);
    var migratedRev = migratedStore.latestRev("shared");
    assertNotEquals(legacyRev, migratedRev);
    assertEquals("acme", migratedStore.comparableAtRev("shared", migratedRev).get("project"));
    assertNull(migratedStore.comparableAtRev("shared", legacyRev).get("project"));
    assertEquals(
        List.of("local", "migration"),
        new ChangeLog(db).history("spec", "shared").stream().map(ChangeLog.Entry::origin).toList());
  }

  @Test
  void twoBoxesBackfillTheSameContentAddressedSpecRevision() throws Exception {
    db.execute(
        """
        INSERT INTO specs (id, title, status, created_at, updated_at, project)
        VALUES ('shared', 'Shared', 'pending', '2026-01-01', '2026-01-01', NULL)""");
    var projects = projectRegistry("acme");
    migrate(projects);
    var firstRev = new SpecStore(db).latestRev("shared");

    var otherPath = tempDir.resolve("other.db");
    stageLegacySchema(Sqlite.open(otherPath));
    try (var other = Sqlite.open(otherPath)) {
      other.execute(
          """
          INSERT INTO specs (id, title, status, created_at, updated_at, project)
          VALUES ('shared', 'Shared', 'pending', '2026-01-01', '2026-01-01', NULL)""");
      new SchemaManager(other).migrate();
      new DataMigrator(other, List.of(new LegacyDataMigration(() -> "node-b")))
          .run(projects, DataMigration.Prompter.NON_INTERACTIVE);

      assertEquals(firstRev, new SpecStore(other).latestRev("shared"));
    }
  }

  private List<DataMigrator.Run> migrate(ProjectRegistry projects) {
    new SchemaManager(db).migrate();
    return new DataMigrator(db, List.of(new LegacyDataMigration(() -> "node-a")))
        .run(projects, DataMigration.Prompter.NON_INTERACTIVE);
  }

  private void seedLegacyRows() {
    db.execute(
        """
        INSERT INTO specs (id, title, status, created_at, updated_at, project)
        VALUES ('legacy-spec', 'Legacy', 'in_progress', '2026-01-01', '2026-01-01', NULL)""");
    db.execute(
        """
        INSERT INTO projects (name, definition, created_at, updated_at)
        VALUES ('acme', 'name: acme', '2026-01-01', '2026-01-01')""");
    db.execute(
        """
        INSERT INTO reviews (id, spec_id, status, created_at)
        VALUES ('legacy-review', 'legacy-spec', 'running', '2026-01-01')""");
    db.execute(
        """
        INSERT INTO runs
            (id, project, spec_id, agent, status, started_at, node, role, unit)
        VALUES
            ('legacy-build', 'acme', 'legacy-spec', 'codex', 'running', '2026-01-01',
                NULL, NULL, ''),
            ('review-run', 'acme', 'legacy-spec', 'codex', 'running', '2026-01-01',
                NULL, 'review', '')""");
    db.execute(
        """
        INSERT INTO fdes (id, handle, status, created_at)
        VALUES ('fde-1', 'ada', 'active', '2026-01-01')""");
    db.execute(
        """
        INSERT INTO api_tokens (token_hash, name, role, fde_id, created_at)
        VALUES
            ('token-1', 'legacy', 'member', 'fde-1', '2026-01-01'),
            ('orphan-token', 'orphan', 'member', 'missing', '2026-01-01')""");
  }

  private void assertRoleConstraint() {
    var notNull =
        db.queryOne(
                "SELECT \"notnull\" FROM pragma_table_info('runs') WHERE name = 'role'",
                row -> row.integer(0))
            .orElseThrow();
    assertEquals(1L, notNull);
    assertThrows(
        SqliteException.class,
        () ->
            db.execute(
                """
                INSERT INTO runs (id, project, agent, status, started_at, role)
                VALUES ('bad-role', 'acme', 'codex', 'running', '2026-01-01', 'other')"""));
  }

  private void assertProjectConstraint() {
    var notNull =
        db.queryOne(
                "SELECT \"notnull\" FROM pragma_table_info('specs') WHERE name = 'project'",
                row -> row.integer(0))
            .orElseThrow();
    assertEquals(1L, notNull);
    assertThrows(
        SqliteException.class,
        () -> db.execute("UPDATE specs SET project = NULL WHERE id = 'legacy-spec'"));
  }

  private long changeLogCount() {
    return db.queryOne("SELECT COUNT(*) FROM change_log", row -> row.integer(0)).orElseThrow();
  }

  private ProjectRegistry projectRegistry(String... names) throws Exception {
    var projectsDir = tempDir.resolve("projects-" + String.join("-", names));
    for (var name : names) {
      var projectDir = projectsDir.resolve(name);
      Files.createDirectories(projectDir);
      Files.writeString(
          projectDir.resolve("sail.yaml"),
          "name: "
              + name
              + "\nimage: ubuntu/24.04\nresources: { cpu: 2, memory: 4GB, disk: 20GB }\n");
    }
    return ProjectRegistry.loadFromDisk(projectsDir);
  }

  private void stageLegacySchema() {
    stageLegacySchema(db);
    db = Sqlite.open(dbPath);
  }

  private static void stageLegacySchema(Sqlite legacy) {
    new SchemaManager(legacy).migrateTo(SchemaManager.LAST_VERSION_BEFORE_V1_FLOOR);
    legacy.execute("PRAGMA writable_schema = ON");
    legacy.execute(
        """
        UPDATE sqlite_schema
        SET sql = replace(sql,
            'role TEXT NOT NULL DEFAULT ''build''',
            'role TEXT DEFAULT ''build''')
        WHERE type = 'table' AND name = 'runs'""");
    legacy.execute(
        """
        UPDATE sqlite_schema
        SET sql = replace(sql,
            'project TEXT NOT NULL DEFAULT ''unassigned''',
            'project TEXT DEFAULT ''unassigned''')
        WHERE type = 'table' AND name = 'specs'""");
    legacy.execute("PRAGMA writable_schema = OFF");
    legacy.close();
  }
}
