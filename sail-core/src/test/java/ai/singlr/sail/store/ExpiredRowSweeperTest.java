/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExpiredRowSweeperTest {

  @TempDir Path tempDir;
  private Path dbPath;
  private Sqlite db;

  @BeforeEach
  void setUp() {
    dbPath = tempDir.resolve("test.db");
    db = Sqlite.open(dbPath);
    new SchemaManager(db).migrate();
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private void session(String hash, Instant expiresAt) {
    var fde = new FdeStore(db).add("uday-" + hash, null, null);
    db.execute(
        "INSERT INTO sessions (token_hash, fde_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
        hash,
        fde.id(),
        Instant.now().toString(),
        expiresAt.toString());
  }

  @Test
  void deletesExpiredSessionsButKeepsLiveOnes() {
    session("live", Instant.now().plus(Duration.ofHours(1)));
    session("dead", Instant.now().minus(Duration.ofHours(1)));

    var removed = ExpiredRowSweeper.sweep(db);

    assertEquals(1, removed);
    assertEquals(
        1L,
        db.queryOne("SELECT COUNT(*) FROM sessions", row -> row.integer(0)).orElseThrow(),
        "the live session survives");
  }

  @Test
  void prunesExpiringTokensButLeavesNonExpiringOnes() {
    var store = new TokenStore(db);
    store.create("forever", "member");
    store.create("doomed", "member", null, Duration.ofSeconds(-1));

    var removed = ExpiredRowSweeper.sweep(db);

    assertEquals(1, removed);
    assertEquals(1, store.list().size());
    assertEquals("forever", store.list().getFirst().name());
  }

  @Test
  void anEmptyDatabaseSweepsNothing() {
    assertEquals(0, ExpiredRowSweeper.sweep(db));
  }

  @Test
  void sweepQuietlyOpensItsOwnConnectionAndRemovesExpiredRows() {
    new TokenStore(db).create("doomed", "member", null, Duration.ofSeconds(-1));

    try (var sweeper = new ExpiredRowSweeper(dbPath)) {
      sweeper.sweepQuietly();
    }

    assertTrue(new TokenStore(db).list().isEmpty());
  }

  @Test
  void sweepQuietlySwallowsAFailureFromAMissingDatabase() {
    try (var sweeper = new ExpiredRowSweeper(tempDir.resolve("nonexistent/missing.db"))) {
      assertDoesNotThrow(sweeper::sweepQuietly);
    }
  }

  @Test
  void startAndCloseAreClean() {
    try (var sweeper = new ExpiredRowSweeper(dbPath)) {
      assertDoesNotThrow(() -> sweeper.start());
    }
  }
}
