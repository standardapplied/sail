/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one-time erasure of what an older release orphaned: counted, erased with an erasure row each,
 * on main only, one entity per transaction, resumable by a second process and idempotent.
 */
class OrphanErasureTest {

  @TempDir Path dir;

  @Test
  void orphansAreErasedAndCountedAndEverythingWithAParentIsKept() {
    try (var db = Sqlite.open(dir.resolve("orphans.db"))) {
      new SchemaManager(db).migrate();
      var specs = new SpecStore(db);
      specs.create(spec("live"));
      specs.create(spec("gone"));
      var livesRun = run(db, "live");
      var deletedsRun = run(db, "gone");
      specs.delete("gone");
      new RoomStore(db)
          .create(
              new RoomStore.RoomRow(
                  "lobby", "proj", "Lobby", null, null, null, "uday", null, null, "uday"));
      var inLobby = message(db, "lobby");
      var inSpecRoom = message(db, "live");
      var orphanRun = run(db, "never-was");
      var orphanReview = review(db, "never-was");
      var orphanMessage = message(db, "nowhere");

      var report = new OrphanErasure(() -> true).apply(db, null, null);

      assertEquals(3, report.applied());
      assertEquals(
          List.of("Erased 3 orphaned rows: 1 runs, 1 reviews, 1 messages"), report.notes());
      for (var kept : List.of(livesRun, deletedsRun)) {
        assertEquals(1, count(db, "SELECT count(*) FROM runs WHERE id = ?", kept));
      }
      for (var kept : List.of(inLobby, inSpecRoom)) {
        assertEquals(1, count(db, "SELECT count(*) FROM room_messages WHERE id = ?", kept));
      }
      for (var erased : List.of(orphanRun, orphanReview, orphanMessage)) {
        assertEquals(
            1,
            count(
                db,
                "SELECT count(*) FROM change_log WHERE entity_id = ? AND kind = 'erasure'"
                    + " AND actor = 'sail' AND origin = 'migration'",
                erased));
        assertEquals(
            0,
            count(
                db,
                "SELECT count(*) FROM change_log WHERE entity_id = ? AND kind <> 'erasure'",
                erased));
      }
      assertEquals(0, count(db, "SELECT count(*) FROM reviews"));
    }
  }

  @Test
  void aNodeErasesNothingItsMainHasNotErased() {
    try (var db = Sqlite.open(dir.resolve("node.db"))) {
      new SchemaManager(db).migrate();
      var orphan = run(db, "not-pulled-yet");

      var report = new OrphanErasure(() -> false).apply(db, null, null);

      assertEquals(0, report.applied());
      assertTrue(report.notes().getFirst().contains("erased by main"));
      assertEquals(1, count(db, "SELECT count(*) FROM runs WHERE id = ?", orphan));
    }
  }

  @Test
  void aMigrationKilledAfterTenOrphansIsFinishedByASecondProcessErasingNothingTwice()
      throws Exception {
    var path = dir.resolve("killed.db");
    try (var db = Sqlite.open(path)) {
      new SchemaManager(db).migrate();
      for (var i = 0; i < 50; i++) {
        run(db, "never-was");
      }
      db.execute(
          """
          CREATE TRIGGER killed AFTER INSERT ON change_log
          WHEN NEW.kind = 'erasure'
              AND (SELECT count(*) FROM change_log WHERE kind = 'erasure') > 10
          BEGIN SELECT RAISE(ABORT, 'killed'); END""");

      assertThrows(
          SqliteException.class, () -> new OrphanErasure(() -> true).apply(db, null, null));
      assertEquals(10, erasures(db), "the ten entities before the kill stay erased");
      assertEquals(40, count(db, "SELECT count(*) FROM runs"));
      db.execute("DROP TRIGGER killed");
    }

    var log = dir.resolve("second.log");
    var second =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                SecondProcess.class.getName(),
                path.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    assertTrue(second.waitFor(60, TimeUnit.SECONDS));
    assertEquals(0, second.exitValue(), () -> read(log));

    try (var db = Sqlite.open(path)) {
      assertEquals(50, erasures(db));
      assertEquals(0, count(db, "SELECT count(*) FROM runs"));
      assertEquals(
          50,
          count(db, "SELECT count(DISTINCT entity_id) FROM change_log WHERE kind = 'erasure'"),
          "no orphan was erased twice");
      assertEquals(
          1, count(db, "SELECT count(*) FROM data_migrations WHERE name = ?", OrphanErasure.NAME));
      assertEquals(0, new OrphanErasure(() -> true).apply(db, null, null).applied());
    }
  }

  /** The second process: the migration runner, resuming what the first left unfinished. */
  public static final class SecondProcess {
    public static void main(String[] args) {
      try (var db = Sqlite.open(Path.of(args[0]))) {
        MigrationRunner.applyAll(
            db, List.of(new OrphanErasure(() -> true)), DataMigration.Prompter.NON_INTERACTIVE);
      }
    }
  }

  private static String run(Sqlite db, String specId) {
    var id = DateTimeUtils.newId().toString();
    db.execute(
        """
        INSERT INTO runs (id, project, spec_id, agent, status, started_at, node, rev, base_rev)
        VALUES (?, 'proj', ?, 'claude', 'completed', 'then', 'node', '1-a', '1-a')""",
        id,
        specId);
    db.execute(
        """
        INSERT INTO change_log (entity_type, entity_id, rev, recorded_at, origin, snapshot)
        VALUES ('run', ?, '1-a', 'then', 'sync', '{}')""",
        id);
    return id;
  }

  private static String review(Sqlite db, String specId) {
    var id = DateTimeUtils.newId().toString();
    db.execute(
        "INSERT INTO reviews (id, spec_id, created_at, rev, base_rev) VALUES (?, ?, 'then', '1-a',"
            + " '1-a')",
        id,
        specId);
    return id;
  }

  private static String message(Sqlite db, String roomId) {
    var id = DateTimeUtils.newId().toString();
    db.execute(
        """
        INSERT INTO room_messages (id, room_id, author, body, created_at, rev, base_rev)
        VALUES (?, ?, 'uday', 'hello', 'then', '1-a', '1-a')""",
        id,
        roomId);
    return id;
  }

  private static SpecStore.SpecRow spec(String id) {
    return new SpecStore.SpecRow(
        id,
        "proj",
        id,
        SpecStatus.PENDING,
        null,
        null,
        null,
        null,
        null,
        0,
        "uday",
        "",
        "",
        "uday",
        List.of(),
        List.of());
  }

  private static long erasures(Sqlite db) {
    return count(db, "SELECT count(*) FROM change_log WHERE kind = 'erasure'");
  }

  private static long count(Sqlite db, String sql, Object... args) {
    return db.queryOne(sql, row -> row.integer(0), args).orElseThrow();
  }

  private static String read(Path log) {
    try {
      return Files.readString(log);
    } catch (IOException e) {
      return e.toString();
    }
  }
}
