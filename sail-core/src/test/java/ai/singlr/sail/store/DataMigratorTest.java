/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.config.ProjectRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataMigratorTest {

  @TempDir Path tempDir;
  private Path dbPath;
  private Sqlite db;
  private ProjectRegistry projects;

  @BeforeEach
  void setUp() {
    dbPath = tempDir.resolve("migrator.db");
    db = Sqlite.open(dbPath);
    new SchemaManager(db).migrate();
    db.execute("CREATE TABLE probe (id TEXT PRIMARY KEY)");
    projects = ProjectRegistry.loadFromDisk(tempDir.resolve("projects"));
  }

  @AfterEach
  void tearDown() {
    if (db != null) {
      db.close();
    }
  }

  @Test
  void failingMigrationRollsBackEveryEffectIncludingNestedTransactions() {
    var migrator = new DataMigrator(db, List.of(probeMigration("half-done", true)));

    assertThrows(
        IllegalStateException.class,
        () -> migrator.run(projects, DataMigration.Prompter.NON_INTERACTIVE));

    assertEquals(0, probeCount(db));
    assertEquals(List.of(), appliedNames(db));
  }

  @Test
  void concurrentFirstRunsFromTwoConnectionsApplyExactlyOnce() throws Exception {
    var start = new CountDownLatch(1);
    try (var other = Sqlite.open(dbPath);
        var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> racingRun(db, start));
      var second = pool.submit(() -> racingRun(other, start));
      start.countDown();
      var outcomes = List.of(first.get(), second.get());

      assertEquals(1, outcomes.stream().filter(run -> !run.alreadyApplied()).count());
      assertEquals(1, outcomes.stream().filter(DataMigrator.Run::alreadyApplied).count());
    }
    assertEquals(1, probeCount(db));
    assertEquals(List.of("racy"), appliedNames(db));
  }

  private DataMigrator.Run racingRun(Sqlite connection, CountDownLatch start) {
    try {
      start.await();
      return new DataMigrator(connection, List.of(probeMigration("racy", false)))
          .run(projects, DataMigration.Prompter.NON_INTERACTIVE)
          .getFirst();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while racing the migrator", e);
    }
  }

  private static DataMigration probeMigration(String name, boolean fail) {
    return new DataMigration() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Report apply(Sqlite target, ProjectRegistry registry, Prompter prompter) {
        target.transaction(
            () -> target.execute("INSERT INTO probe (id) VALUES (?)", "effect-" + name));
        if (fail) {
          throw new IllegalStateException("Migration failed after writing");
        }
        return new Report(1, 0, 0, List.of());
      }
    };
  }

  private static int probeCount(Sqlite connection) {
    return connection.query("SELECT id FROM probe", row -> row.text(0)).size();
  }

  private static List<String> appliedNames(Sqlite connection) {
    return connection.query("SELECT name FROM data_migrations ORDER BY name", row -> row.text(0));
  }
}
