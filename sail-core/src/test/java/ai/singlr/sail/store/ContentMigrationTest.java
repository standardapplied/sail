/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.*;

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
      var report = migration.apply(db, null, Prompter());
      assertTrue(report.applied() >= 2);
      assertEquals(revision, new SpecStore(db).latestRev("a"));
      assertEquals("body", new SpecStore(db).getContent("a").orElseThrow().body());
      var row = new FileStore(db).find("proj", "file").orElseThrow();
      assertEquals(4, row.size());
      assertEquals("binary", row.kind());
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
      blobs.gc(java.util.Set.of());
      assertEquals(0, migration.apply(db, null, Prompter()).applied());
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
          RuntimeException.class, () -> new ContentMigration().apply(first, null, Prompter()));
      assertTrue(
          first
              .queryOne("SELECT body_hash FROM specs WHERE id = 'a'", r -> !r.isNull(0))
              .orElseThrow());
      assertTrue(
          first
              .queryOne("SELECT body_hash FROM specs WHERE id = 'z'", r -> r.isNull(0))
              .orElseThrow());
      first.execute("DROP TRIGGER interrupt_migration");
      new ContentMigration().apply(second, null, Prompter());
      assertEquals("body", new SpecStore(second).getContent("a").orElseThrow().body());
      assertEquals("", new SpecStore(second).getContent("z").orElseThrow().body());
    }
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

  private static DataMigration.Prompter Prompter() {
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
