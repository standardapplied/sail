/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteTest {

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
  void openCreatesFile() {
    assertTrue(Files.exists(tempDir.resolve("test.db")));
  }

  @Test
  void createTableAndInsert() {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
    db.execute("INSERT INTO t (id, name) VALUES (?, ?)", 1, "alice");

    var results = db.query("SELECT id, name FROM t", row -> row.text(1));
    assertEquals(1, results.size());
    assertEquals("alice", results.getFirst());
  }

  @Test
  void parameterizedQuery() {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)");
    db.execute("INSERT INTO t VALUES (?, ?, ?)", 1, "alice", 30);
    db.execute("INSERT INTO t VALUES (?, ?, ?)", 2, "bob", 25);
    db.execute("INSERT INTO t VALUES (?, ?, ?)", 3, "charlie", 35);

    var results =
        db.query("SELECT name FROM t WHERE age > ? ORDER BY name", row -> row.text(0), 28);
    assertEquals(2, results.size());
    assertEquals("alice", results.getFirst());
    assertEquals("charlie", results.get(1));
  }

  @Test
  void nullHandling() {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
    db.execute("INSERT INTO t VALUES (?, ?)", 1, null);

    var result = db.queryOne("SELECT name FROM t WHERE id = ?", row -> row.isNull(0), 1);
    assertTrue(result.isPresent());
    assertTrue(result.get());
  }

  @Test
  void queryOneReturnsEmptyForNoRows() {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
    var result = db.queryOne("SELECT id FROM t WHERE id = ?", row -> row.integer(0), 999);
    assertTrue(result.isEmpty());
  }

  @Test
  void integerColumn() {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, count INTEGER)");
    db.execute("INSERT INTO t VALUES (?, ?)", 1, 42L);

    var result = db.queryOne("SELECT count FROM t WHERE id = ?", row -> row.integer(0), 1);
    assertTrue(result.isPresent());
    assertEquals(42L, result.get());
  }

  @Test
  void transactionCommit() {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");

    db.transaction(
        () -> {
          db.execute("INSERT INTO t VALUES (?, ?)", 1, "alice");
          db.execute("INSERT INTO t VALUES (?, ?)", 2, "bob");
        });

    var count = db.query("SELECT COUNT(*) FROM t", row -> row.integer(0));
    assertEquals(2L, count.getFirst());
  }

  @Test
  void transactionRollbackOnException() {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");

    assertThrows(
        RuntimeException.class,
        () ->
            db.transaction(
                () -> {
                  db.execute("INSERT INTO t VALUES (?, ?)", 1, "alice");
                  throw new RuntimeException("boom");
                }));

    var count = db.query("SELECT COUNT(*) FROM t", row -> row.integer(0));
    assertEquals(0L, count.getFirst());
  }

  @Test
  void changesReturnsAffectedRows() {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
    db.execute("INSERT INTO t VALUES (?, ?)", 1, "alice");
    db.execute("INSERT INTO t VALUES (?, ?)", 2, "bob");
    db.execute("INSERT INTO t VALUES (?, ?)", 3, "charlie");

    db.execute("DELETE FROM t WHERE id > ?", 1);
    assertEquals(2, db.changes());
  }

  @Test
  void foreignKeysEnforced() {
    db.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY)");
    db.execute(
        "CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES parent(id))");

    assertThrows(SqliteException.class, () -> db.execute("INSERT INTO child VALUES (?, ?)", 1, 99));
  }

  @Test
  void walModeEnabled() {
    var mode = db.queryOne("PRAGMA journal_mode", row -> row.text(0));
    assertTrue(mode.isPresent());
    assertEquals("wal", mode.get());
  }

  @Test
  void closeIsIdempotent() {
    db.close();
    db.close();
    db = null;
  }

  @Test
  void operationsAfterCloseThrow() {
    db.close();
    assertThrows(IllegalStateException.class, () -> db.execute("SELECT 1"));
    db = null;
  }

  @Test
  void invalidSqlThrows() {
    assertThrows(SqliteException.class, () -> db.execute("NOT VALID SQL"));
  }

  @Test
  void constraintViolationThrows() {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
    db.execute("INSERT INTO t VALUES (?)", 1);
    assertThrows(SqliteException.class, () -> db.execute("INSERT INTO t VALUES (?)", 1));
  }

  @Test
  void largeTextRoundtrip() {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, body TEXT)");
    var largeText = "x".repeat(100_000);
    db.execute("INSERT INTO t VALUES (?, ?)", 1, largeText);

    var result = db.queryOne("SELECT body FROM t WHERE id = ?", row -> row.text(0), 1);
    assertTrue(result.isPresent());
    assertEquals(100_000, result.get().length());
  }

  @Test
  void multipleColumnsInRow() {
    db.execute("CREATE TABLE t (a TEXT, b INTEGER, c TEXT)");
    db.execute("INSERT INTO t VALUES (?, ?, ?)", "hello", 42, "world");

    record Triple(String a, long b, String c) {}

    var result =
        db.queryOne(
            "SELECT a, b, c FROM t", row -> new Triple(row.text(0), row.integer(1), row.text(2)));
    assertTrue(result.isPresent());
    assertEquals("hello", result.get().a());
    assertEquals(42L, result.get().b());
    assertEquals("world", result.get().c());
  }

  @Test
  void columnCount() {
    db.execute("CREATE TABLE t (a TEXT, b TEXT, c TEXT)");
    db.execute("INSERT INTO t VALUES (?, ?, ?)", "x", "y", "z");

    var count = db.queryOne("SELECT a, b, c FROM t", Sqlite.Row::columnCount);
    assertTrue(count.isPresent());
    assertEquals(3, count.get());
  }

  @Test
  void reopenExistingDatabase() throws IOException {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
    db.execute("INSERT INTO t VALUES (?, ?)", 1, "persistent");
    db.close();

    db = Sqlite.open(tempDir.resolve("test.db"));
    var result = db.queryOne("SELECT name FROM t WHERE id = ?", row -> row.text(0), 1);
    assertTrue(result.isPresent());
    assertEquals("persistent", result.get());
  }
}
