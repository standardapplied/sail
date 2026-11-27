/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedied;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
  void aRehearsalSeesItsOwnWritesAndLeavesNone() {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
    db.execute("INSERT INTO t (id, name) VALUES (1, 'kept')");

    var seen =
        db.rehearse(
            () -> {
              db.execute("DELETE FROM t");
              db.transaction(() -> db.execute("INSERT INTO t (id, name) VALUES (2, 'nested')"));
              return db.query("SELECT name FROM t", row -> row.text(0));
            });

    assertEquals(List.of("nested"), seen);
    assertEquals(List.of("kept"), db.query("SELECT name FROM t", row -> row.text(0)));
    db.execute("INSERT INTO t (id, name) VALUES (3, 'after')");
    assertEquals(2, db.query("SELECT id FROM t", row -> row.integer(0)).size());
  }

  @Test
  void aFailingRehearsalRollsBackAndAnInnerOneIsRefused() {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");

    assertThrows(
        IllegalStateException.class,
        () ->
            db.rehearse(
                () -> {
                  db.execute("INSERT INTO t (id) VALUES (1)");
                  throw new IllegalStateException("boom");
                }));
    var nested =
        assertThrows(IllegalStateException.class, () -> db.transaction(() -> db.rehearse(() -> 1)));

    assertTrue(nested.getMessage().contains("outermost"));
    assertEquals(0, db.query("SELECT id FROM t", row -> row.integer(0)).size());
  }

  /**
   * A REAL reads back as the double it stored, to the last bit: SQLite's own text form keeps 15
   * digits and turns 14.762978600000006 into 14.7629786, which a run's dollars must never become.
   */
  @Test
  void aRealReadsBackAsTheExactDoubleItStored() {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, usd REAL)");
    var stored = List.of(14.762978600000006, 0.1 + 0.2, 1e-7, 4.9e-324, 1.0e21);
    for (var index = 0; index < stored.size(); index++) {
      db.execute("INSERT INTO t (id, usd) VALUES (?, ?)", index, stored.get(index));
    }
    db.execute("INSERT INTO t (id, usd) VALUES (?, NULL)", stored.size());

    var read = db.query("SELECT usd FROM t WHERE usd IS NOT NULL ORDER BY id", row -> row.real(0));

    assertEquals(
        stored.stream().map(Double::doubleToLongBits).toList(),
        read.stream().map(Double::doubleToLongBits).toList());
    assertNull(
        db.queryOne(
                "SELECT usd FROM t WHERE id = ?",
                row -> Optional.ofNullable(row.real(0)),
                stored.size())
            .orElseThrow()
            .orElse(null),
        "a NULL is no dollars, never zero");
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
  void aTransactionHoldsTheWriteLockBeforeItsFirstRead(@TempDir Path dir) {
    // A second process committing between a transaction's read and its write must wait for it,
    // never invalidate it: with the lock taken up front the other writer is the one refused.
    try (var db = Sqlite.open(dir.resolve("shared.db"));
        var other = Sqlite.open(dir.resolve("shared.db"))) {
      db.execute("CREATE TABLE counter (n INTEGER NOT NULL)");
      db.execute("INSERT INTO counter (n) VALUES (0)");
      other.execute("PRAGMA busy_timeout = 0");
      var refused = new java.util.concurrent.atomic.AtomicBoolean();
      db.transaction(
          () -> {
            var seen = db.queryOne("SELECT n FROM counter", row -> row.integer(0)).orElseThrow();
            try {
              other.execute("UPDATE counter SET n = n + 10");
            } catch (SqliteException busy) {
              refused.set(true);
            }
            db.execute("UPDATE counter SET n = ?", seen + 1);
            return null;
          });
      assertTrue(refused.get(), "the other writer found the lock already taken");
      assertEquals(1L, db.queryOne("SELECT n FROM counter", row -> row.integer(0)).orElseThrow());
      other.execute("UPDATE counter SET n = n + 10");
      assertEquals(11L, db.queryOne("SELECT n FROM counter", row -> row.integer(0)).orElseThrow());
    }
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

  /**
   * An {@link Error} thrown inside a transaction rolls it back as an exception does and comes out
   * unchanged: its write is not there afterwards, not even after the connection's next transaction
   * commits, which starts clean.
   */
  @Test
  void anErrorThrownInsideATransactionRollsItBackAndIsRethrownUnchanged() {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
    var thrown = new StackOverflowError("deep");

    var caught =
        assertThrows(
            StackOverflowError.class,
            () ->
                db.transaction(
                    () -> {
                      db.execute("INSERT INTO t VALUES (?)", 1);
                      throw thrown;
                    }));
    db.transaction(() -> db.execute("INSERT INTO t VALUES (?)", 2));

    assertSame(thrown, caught);
    assertEquals(List.of(2L), db.query("SELECT id FROM t ORDER BY id", row -> row.integer(0)));
    try (var other = Sqlite.open(tempDir.resolve("test.db"))) {
      assertEquals(List.of(2L), other.query("SELECT id FROM t ORDER BY id", row -> row.integer(0)));
    }
  }

  /**
   * Round 4, item 6: when the rollback itself fails, the error the work threw is the one rethrown
   * and the rollback's is attached to it; a connection SQLite says holds no transaction then starts
   * the next one clean.
   */
  @Test
  void aRollbackThatFailsLeavesTheWorksErrorRethrownWithTheRollbacksSuppressed() {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
    var thrown = new IllegalStateException("the work's own");

    var caught =
        assertThrows(
            IllegalStateException.class,
            () ->
                db.transaction(
                    () -> {
                      db.execute("ROLLBACK");
                      throw thrown;
                    }));
    db.transaction(() -> db.execute("INSERT INTO t VALUES (?)", 1));

    assertSame(thrown, caught);
    assertEquals(1, caught.getSuppressed().length);
    assertInstanceOf(SqliteException.class, caught.getSuppressed()[0]);
    assertEquals(List.of(1L), db.query("SELECT id FROM t", row -> row.integer(0)));
  }

  /**
   * H3: a connection SQLite says still holds a transaction that no scope opened, as a rollback that
   * failed leaves one, is rolled back once before a new scope, write or read, begins: what that
   * transaction held is dropped, the scope runs, and nothing waits for a restart.
   */
  @Test
  void aConnectionLeftHoldingATransactionIsRolledBackOnceAndTakesTheNextScope() {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
    db.execute("BEGIN");
    db.execute("INSERT INTO t VALUES (?)", 1);

    db.transaction(() -> db.execute("INSERT INTO t VALUES (?)", 2));
    db.execute("BEGIN");
    assertEquals(0, (int) db.read(() -> 0));

    assertEquals(List.of(2L), db.query("SELECT id FROM t", row -> row.integer(0)));
    try (var other = Sqlite.open(tempDir.resolve("test.db"))) {
      assertEquals(List.of(2L), other.query("SELECT id FROM t", row -> row.integer(0)));
    }
  }

  @Test
  void nestedTransactionJoinsTheOuterScope() {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");

    db.transaction(
        () -> {
          db.execute("INSERT INTO t VALUES (?)", 1);
          db.transaction(() -> db.execute("INSERT INTO t VALUES (?)", 2));
          db.transaction(
              () -> {
                db.execute("INSERT INTO t VALUES (?)", 3);
                return null;
              });
        });

    var count = db.query("SELECT COUNT(*) FROM t", row -> row.integer(0));
    assertEquals(3L, count.getFirst());
  }

  @Test
  void aFailureAfterANestedJoinRollsBackItsWritesToo() {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");

    assertThrows(
        RuntimeException.class,
        () ->
            db.transaction(
                () -> {
                  db.transaction(() -> db.execute("INSERT INTO t VALUES (?)", 1));
                  throw new RuntimeException("boom");
                }));

    var count = db.query("SELECT COUNT(*) FROM t", row -> row.integer(0));
    assertEquals(0L, count.getFirst());
  }

  @Test
  void aNewTransactionAfterANestedJoinBeginsItsOwnScope() {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
    db.transaction(() -> db.transaction(() -> db.execute("INSERT INTO t VALUES (?)", 1)));

    assertThrows(
        RuntimeException.class,
        () ->
            db.transaction(
                () -> {
                  db.execute("INSERT INTO t VALUES (?)", 2);
                  throw new RuntimeException("boom");
                }));

    var count = db.query("SELECT COUNT(*) FROM t", row -> row.integer(0));
    assertEquals(1L, count.getFirst());
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
  @Timeout(value = 1, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void aCloseWaitsForTheScopeInFlightOnAnotherThreadAndEveryLaterCallIsRefused() throws Exception {
    var shared = Sqlite.open(tempDir.resolve("shared.db"));
    var holding = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var read = new AtomicReference<Object>();
    var reader =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    read.set(
                        shared.read(
                            () -> {
                              holding.countDown();
                              await(release);
                              return shared
                                  .queryOne("SELECT 41 + 1", row -> row.integer(0))
                                  .orElseThrow();
                            }));
                  } catch (RuntimeException e) {
                    read.set(e);
                  }
                });
    holding.await();

    var closer = Thread.ofPlatform().start(shared::close);
    awaitLeavingRunnable(closer);
    var waited = closer.isAlive();
    release.countDown();
    reader.join();
    closer.join();

    assertTrue(waited, "the close went ahead under a scope in flight");
    assertEquals(42L, read.get(), "the scope's work finished whole");
    assertEquals(
        "Database is closed",
        assertThrows(IllegalStateException.class, () -> shared.query("SELECT 1", row -> 1))
            .getMessage());
    assertEquals(
        "Database is closed",
        assertThrows(IllegalStateException.class, () -> shared.read(() -> 1)).getMessage());
  }

  @Test
  @Timeout(value = 1, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void aCallWaitingForTheConnectionAsItClosesIsRefusedNeverRunOnTheFreedHandle() throws Exception {
    var shared = Sqlite.open(tempDir.resolve("shared.db"));
    var holding = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var ownScope = new AtomicReference<Throwable>();
    var holder =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    shared.read(
                        () -> {
                          holding.countDown();
                          await(release);
                          shared.close();
                          return null;
                        });
                  } catch (RuntimeException e) {
                    ownScope.set(e);
                  }
                });
    holding.await();
    var refused = new AtomicReference<Throwable>();
    var straggler =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    shared.query("SELECT 1", row -> row.integer(0));
                  } catch (RuntimeException e) {
                    refused.set(e);
                  }
                });

    awaitLeavingRunnable(straggler);
    release.countDown();
    holder.join();
    straggler.join();

    assertInstanceOf(IllegalStateException.class, refused.get());
    assertEquals("Database is closed", refused.get().getMessage());
    assertEquals(
        "Database is closed",
        ownScope.get().getMessage(),
        "the scope that closed its own connection cannot commit on it");
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static void awaitLeavingRunnable(Thread thread) {
    while (thread.getState() == Thread.State.RUNNABLE) {
      Thread.onSpinWait();
    }
  }

  @Test
  void aLibraryThatCannotLoadSaysWhyBeforeItsFix() {
    try (var arena = Arena.ofConfined()) {
      var refused =
          assertThrows(
              RuntimeException.class, () -> Sqlite.library("libsail-no-such-sqlite.so.0", arena));

      var remedied = assertInstanceOf(Remedied.class, refused);
      assertEquals(
          "The SQLite library libsail-no-such-sqlite.so.0 could not be loaded ("
              + refused.getCause().getMessage()
              + ").",
          remedied.reason());
      assertEquals("On Ubuntu: sudo apt-get install -y libsqlite3-0", remedied.remedy().plain());
    }
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

  @Test
  void concurrentTransactionsOnOneConnectionNeverInterleave() throws Exception {
    db.execute("CREATE TABLE counter (id INTEGER PRIMARY KEY, n INTEGER NOT NULL)");
    db.execute("INSERT INTO counter VALUES (1, 0)");
    var threads = 8;
    var iterations = 25;
    var start = new java.util.concurrent.CountDownLatch(1);

    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (var t = 0; t < threads; t++) {
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  for (var i = 0; i < iterations; i++) {
                    db.transaction(
                        () -> {
                          var n =
                              db.queryOne(
                                      "SELECT n FROM counter WHERE id = 1", row -> row.integer(0))
                                  .orElseThrow();
                          db.execute("UPDATE counter SET n = ? WHERE id = 1", n + 1);
                          return null;
                        });
                    db.query("SELECT n FROM counter", row -> row.integer(0));
                  }
                  return null;
                }));
      }
      start.countDown();
      for (var future : futures) {
        future.get(2, java.util.concurrent.TimeUnit.MINUTES);
      }
    }

    var total = db.queryOne("SELECT n FROM counter WHERE id = 1", row -> row.integer(0));
    assertEquals((long) threads * iterations, (long) total.orElseThrow());
  }

  @Test
  void aMissingSqliteLibraryNamesItselfAndTheRemedy() {
    try (var arena = Arena.ofConfined()) {
      var thrown =
          assertThrows(SqliteException.class, () -> Sqlite.library("libsail-absent.so.0", arena));

      assertTrue(thrown.getMessage().contains("libsail-absent.so.0"));
      assertTrue(thrown.getMessage().contains("apt-get install -y libsqlite3-0"));
    }
  }

  @Test
  void holdsWriteLockOnlyInsideAnImmediateTransaction() {
    assertFalse(db.holdsWriteLock());
    assertTrue(db.transaction(() -> db.holdsWriteLock()));
    assertFalse(db.read(() -> db.holdsWriteLock()));
    assertTrue(db.transaction(() -> db.read(() -> db.holdsWriteLock())));
    assertFalse(db.read(() -> db.transaction(() -> db.holdsWriteLock())));
    assertFalse(db.holdsWriteLock());
  }

  @Test
  void inScopeInsideEitherKindOfTransaction() {
    assertFalse(db.inScope());
    assertTrue(db.transaction(() -> db.inScope()));
    assertTrue(db.read(() -> db.inScope()));
    assertFalse(db.inScope());
  }

  @Test
  void aDoomedTransactionRollsBackAndThrowsEvenWhenAnInnerScopeSwallowedTheCause() {
    db.execute("CREATE TABLE doomed (id INTEGER)");
    var cause = new IllegalStateException("refused");

    var thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                db.transaction(
                    (Runnable)
                        () -> {
                          db.execute("INSERT INTO doomed VALUES (1)");
                          try {
                            db.transaction(
                                (Runnable)
                                    () -> {
                                      db.execute("INSERT INTO doomed VALUES (2)");
                                      throw db.doomed(cause);
                                    });
                          } catch (IllegalStateException swallowed) {
                            db.execute("INSERT INTO doomed VALUES (3)");
                          }
                        }));

    assertSame(cause, thrown);
    assertEquals(
        0L, db.queryOne("SELECT count(*) FROM doomed", row -> row.integer(0)).orElseThrow());
    db.transaction(() -> db.execute("INSERT INTO doomed VALUES (4)"));
    assertEquals(
        1L,
        db.queryOne("SELECT count(*) FROM doomed", row -> row.integer(0)).orElseThrow(),
        "the next transaction is its own");
    assertSame(cause, db.doomed(cause), "outside a transaction there is nothing to doom");
    db.transaction(() -> db.execute("INSERT INTO doomed VALUES (5)"));
  }
}
