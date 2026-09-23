/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.sync.SyncBox;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentMigrationTest {
  @TempDir Path dir;

  @Test
  void migratesLiveRowsHistoryAndAllConflictSidesWithoutChangingRevisions() {
    try (var db = Sqlite.openMemory()) {
      legacy(db);
      var revision = new SpecStore(db).latestRev("a");
      var migration = new ContentMigration();
      var report = migration.apply(db, null, prompter());
      assertTrue(report.applied() >= 2);
      assertEquals(revision, new SpecStore(db).latestRev("a"));
      assertEquals("body", new SpecStore(db).getContent("a").orElseThrow().body());
      var row = new FileStore(db).find("proj", "file").orElseThrow();
      assertEquals(4, row.size());
      assertEquals("binary", row.kind());
      assertEquals(0644, row.mode());
      assertEquals(0755, new FileStore(db).find("proj", "hook.sh").orElseThrow().mode());
      for (var snapshot :
          db.query("SELECT snapshot FROM change_log WHERE entity_type = 'file'", r -> r.text(0))) {
        assertFalse(
            YamlUtil.parseMap(snapshot).containsKey("mode"),
            "a legacy revision recorded no mode: " + snapshot);
      }
      assertTrue(new FileStore(db).isKnownVersion("proj/file", row.contentHash(), 0664));
      assertEquals(
          List.of("body_hash"),
          new SyncConflicts(db).pending().getFirst().fields(),
          "the clashing field follows its content into the hash column");
      assertEquals(
          "body",
          new BlobStore(db)
              .text(new SpecStore(db).comparableSnapshot("a").get("body_hash").toString()));
      for (var snapshot :
          db.query("SELECT snapshot FROM change_log WHERE entity_type = 'spec'", r -> r.text(0))) {
        assertFalse(YamlUtil.parseMap(snapshot).containsKey("body"));
      }
      var blobs = new BlobStore(db);
      for (var conflict : new SyncConflicts(db).pending()) {
        for (var side :
            List.of(conflict.baseSnapshot(), conflict.localSnapshot(), conflict.remoteSnapshot())) {
          var hash = YamlUtil.parseMap(side).get("body_hash").toString();
          assertTrue(blobs.has(hash));
        }
      }
      blobs.gc(BlobStore.Compaction.NONE, true).freed();
      assertEquals(0, migration.apply(db, null, prompter()).applied());
      assertTrue(
          db.query(
                  "SELECT 1 FROM pragma_table_info('project_files') WHERE name = 'content'",
                  r -> r.integer(0))
              .isEmpty());
    }
  }

  @Test
  void anInterruptedEntityRollsBackButEarlierEntitiesStayMigratedAndResumeOnAnotherConnection() {
    var path = dir.resolve("migration.db");
    try (var first = Sqlite.open(path);
        var second = Sqlite.open(path)) {
      legacy(first);
      new SpecStore(first).create(SyncBox.spec("z", "Z", "pending"));
      first.execute("UPDATE specs SET body_hash = NULL, plan_hash = NULL WHERE id = 'z'");
      first.execute(
          "CREATE TRIGGER interrupt_migration BEFORE UPDATE OF body_hash ON specs WHEN NEW.id = 'z' BEGIN SELECT RAISE(ABORT, 'interrupted'); END");
      assertThrows(
          RuntimeException.class, () -> new ContentMigration().apply(first, null, prompter()));
      assertTrue(
          first
              .queryOne("SELECT body_hash FROM specs WHERE id = 'a'", r -> !r.isNull(0))
              .orElseThrow());
      assertTrue(
          first
              .queryOne("SELECT body_hash FROM specs WHERE id = 'z'", r -> r.isNull(0))
              .orElseThrow());
      first.execute("DROP TRIGGER interrupt_migration");
      new ContentMigration().apply(second, null, prompter());
      assertEquals("body", new SpecStore(second).getContent("a").orElseThrow().body());
      assertEquals("", new SpecStore(second).getContent("z").orElseThrow().body());
    }
  }

  @Test
  void theLegacyColumnIsDroppedOnlyOnceEveryRowHasItsHash() {
    try (var db = Sqlite.openMemory()) {
      legacy(db);
      db.execute(
          "INSERT INTO project_files (id, project, path, content, updated_at) VALUES ('proj/raced', 'proj', 'raced', 'AAECAw==', 'now')");

      var refused =
          assertThrows(IllegalStateException.class, () -> ContentMigration.dropLegacyContent(db));

      assertTrue(refused.getMessage().contains("sail migrate"), refused.getMessage());
      assertTrue(hasContentColumn(db), "an irreversible drop waits for the stragglers");
      new ContentMigration().apply(db, null, prompter());
      assertFalse(hasContentColumn(db));
      ContentMigration.dropLegacyContent(db);
    }
  }

  private static boolean hasContentColumn(Sqlite db) {
    return db.queryOne(
            "SELECT 1 FROM pragma_table_info('project_files') WHERE name = 'content'",
            r -> r.integer(0))
        .isPresent();
  }

  @Test
  void readersRefuseContentWhoseMigrationHasNotCompleted() {
    try (var db = Sqlite.openMemory()) {
      legacy(db);
      var spec = assertThrows(IllegalStateException.class, () -> new SpecStore(db).getContent("a"));
      assertTrue(spec.getMessage().contains("sail migrate"));
      var file = assertThrows(RuntimeException.class, () -> new FileStore(db).find("proj", "file"));
      assertTrue(file.getMessage().contains("sail migrate"));
    }
  }

  @Test
  void aSecondProcessResumesAfterTheFirstIsKilledMidMigration() throws Exception {
    var path = dir.resolve("processes.db");
    try (var db = Sqlite.open(path)) {
      new SchemaManager(db).migrate();
      db.execute(
          "INSERT INTO data_migrations (name, applied_at) VALUES (?, 'test')",
          LegacyDataMigration.NAME);
      db.transaction(
          () -> {
            for (var i = 0; i < 2000; i++) {
              var id = "entity-" + i;
              db.execute(
                  "INSERT INTO specs (id, title, project, status, created_at, updated_at) VALUES (?, ?, 'proj', 'pending', 'now', 'now')",
                  id,
                  id);
              db.execute(
                  "INSERT INTO spec_content (spec_id, body, plan, updated_at) VALUES (?, ?, '', 'now')",
                  id,
                  "body".repeat(8192) + i);
            }
          });
    }
    var firstLog = dir.resolve("first.log");
    var secondLog = dir.resolve("second.log");
    var first = migrationProcess(path, firstLog);
    Process second = null;
    try (var observer = Sqlite.open(path)) {
      var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
      long migrated;
      do {
        migrated =
            observer
                .queryOne(
                    "SELECT COUNT(*) FROM specs WHERE body_hash IS NOT NULL", row -> row.integer(0))
                .orElseThrow();
        if (migrated >= 5) break;
        Thread.sleep(1);
      } while (first.isAlive() && System.nanoTime() < deadline);
      assertTrue(migrated >= 5 && migrated < 2000, "must interrupt between entity commits");
      assertEquals(
          0, new ProcessBuilder("kill", "-STOP", Long.toString(first.pid())).start().waitFor());
      second = migrationProcess(path, secondLog);
      assertTrue(first.isAlive());
      first.destroyForcibly();
      assertTrue(first.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
      assertTrue(second.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(0, second.exitValue(), () -> readLog(secondLog));
      assertEquals(
          2000,
          observer
              .queryOne(
                  "SELECT COUNT(*) FROM specs WHERE body_hash IS NOT NULL AND plan_hash IS NOT NULL",
                  row -> row.integer(0))
              .orElseThrow());
      assertEquals(
          1,
          observer
              .queryOne(
                  "SELECT COUNT(*) FROM data_migrations WHERE name = ?",
                  row -> row.integer(0),
                  ContentMigration.NAME)
              .orElseThrow());
      assertEquals(
          "body".repeat(8192) + 1999,
          new SpecStore(observer).getContent("entity-1999").orElseThrow().body());
      assertEquals(0, new ContentMigration().apply(observer, null, prompter()).applied());
    } finally {
      first.destroyForcibly();
      if (second != null) second.destroyForcibly();
    }
  }

  private Process migrationProcess(Path path, Path log) throws java.io.IOException {
    return new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            MigrationProcess.class.getName(),
            path.toString())
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .start();
  }

  private static String readLog(Path path) {
    try {
      return java.nio.file.Files.readString(path);
    } catch (java.io.IOException e) {
      return e.toString();
    }
  }

  public static final class MigrationProcess {
    public static void main(String[] args) {
      try (var database =
          ai.singlr.sail.sync.SyncDatabase.converge(Path.of(args[0]), "migrating")) {
        if (database
                .db()
                .queryOne(
                    "SELECT COUNT(*) FROM specs WHERE body_hash IS NULL OR plan_hash IS NULL",
                    row -> row.integer(0))
                .orElseThrow()
            != 0) throw new AssertionError("sync database exposed unmigrated content");
      }
    }
  }

  private static DataMigration.Prompter prompter() {
    return DataMigration.Prompter.NON_INTERACTIVE;
  }

  private static void legacy(Sqlite db) {
    new SchemaManager(db).migrate();
    var specs = new SpecStore(db);
    specs.create(SyncBox.spec("a", "A", "pending"));
    specs.setContent("a", "body", "plan");
    db.execute("UPDATE specs SET body_hash = NULL, plan_hash = NULL");
    var snapshot =
        new java.util.LinkedHashMap<>(
            YamlUtil.parseMap(new ChangeLog(db).head("spec", "a").orElseThrow().snapshot()));
    snapshot.remove("body_hash");
    snapshot.remove("plan_hash");
    snapshot.put("body", "body");
    snapshot.put("plan", "plan");
    db.execute(
        "UPDATE change_log SET snapshot = ? WHERE entity_type = 'spec'",
        YamlUtil.dumpJson(snapshot));
    db.execute("ALTER TABLE project_files ADD COLUMN content TEXT");
    db.execute(
        "INSERT INTO project_files (id, project, path, content, updated_at) VALUES ('proj/file', 'proj', 'file', 'AAECAw==', 'now')");
    db.execute(
        "INSERT INTO project_files (id, project, path, content, updated_at) VALUES ('proj/hook.sh', 'proj', 'hook.sh', 'AAECAw==', 'now')");
    new ChangeLog(db)
        .append(
            "file",
            "proj/file",
            "1-file",
            null,
            "local",
            false,
            YamlUtil.dumpJson(Map.of("content", "AAECAw==")));
    new SyncConflicts(db)
        .record(
            "spec",
            "a",
            YamlUtil.dumpJson(Map.of("body", "base", "plan", "")),
            YamlUtil.dumpJson(snapshot),
            YamlUtil.dumpJson(Map.of("body", "remote", "plan", "")),
            List.of("body"));
  }
}
