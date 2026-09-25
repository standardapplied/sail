/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.sync.SyncEngine;
import ai.singlr.sail.sync.SyncSession;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncHealthTest {
  @TempDir Path tempDir;
  private Sqlite db;
  private SyncHealth health;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    health = new SyncHealth(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  @Test
  void aRoundMovesThroughSyncingToInSyncAndFailuresAgeIntoStale() {
    var t0 = Instant.parse("2026-09-17T00:00:00Z");
    health.begin("main", t0);
    assertEquals("syncing", health.find("main").orElseThrow().state());
    assertFalse(health.succeeded("main", t0.plusSeconds(1), new SyncEngine.Report(1, 0, 0, 0)));
    var ok = health.find("main").orElseThrow();
    assertEquals("in_sync", ok.state());
    assertEquals(1, ok.lastReport().pulled());

    assertEquals(1, health.failed("main", t0.plusSeconds(2), "unreachable", "ssh: exit 255"));
    assertEquals(2, health.failed("main", t0.plusSeconds(3), "unreachable", "ssh: exit 255"));
    var stale = health.find("main").orElseThrow();
    assertEquals("stale", stale.state());
    assertEquals(t0.plusSeconds(2), stale.staleSince(), "stale since the first failure");
    assertEquals(t0.plusSeconds(1), stale.lastSuccessAt());

    assertTrue(health.succeeded("main", t0.plusSeconds(4), new SyncEngine.Report(0, 0, 0, 0)));
    assertEquals(0, health.find("main").orElseThrow().consecutiveFailures());
  }

  @Test
  void theLastRoundsDenialsAreKeptUntilTheNextRoundReplacesThem() {
    var t0 = Instant.parse("2026-09-17T00:00:00Z");
    health.begin("main", t0);
    assertEquals(List.of(), health.find("main").orElseThrow().denials());
    var denial = new SyncSession.Denial("spec", "auth", "your role is read-only");

    health.succeeded("main", t0, SyncEngine.Report.NONE, 0, 0, 0, List.of(denial));

    assertEquals(List.of(denial), health.find("main").orElseThrow().denials());
    health.succeeded("main", t0.plusSeconds(1), SyncEngine.Report.NONE);
    assertEquals(List.of(), health.find("main").orElseThrow().denials());
  }

  @Test
  void recordingASuccessHoldsTheWriteLockBeforeItReads() {
    // A deferred transaction reads under a shared lock; a commit from another connection in
    // between leaves it unable to upgrade, and the round is recorded as still syncing. Taking
    // the write lock first makes the other writer the one that waits.
    var at = Instant.parse("2026-09-17T00:00:00Z");
    var refusedWhileLocked = new AtomicBoolean();
    var committedInBetween = new AtomicBoolean();
    try (var other = Sqlite.open(tempDir.resolve("test.db"))) {
      other.execute("PRAGMA busy_timeout = 0");
      var contended =
          new SyncHealth(
              db,
              () -> {
                try {
                  other.execute("UPDATE sync_health SET last_error = 'other' WHERE peer = 'main'");
                  committedInBetween.set(true);
                } catch (SqliteException busy) {
                  refusedWhileLocked.set(true);
                }
              });
      contended.begin("main", at);
      contended.succeeded("main", at, new SyncEngine.Report(0, 0, 0, 0));
      assertTrue(refusedWhileLocked.get(), "the other writer found the lock already taken");
      assertFalse(committedInBetween.get());
      assertEquals("in_sync", health.find("main").orElseThrow().state());
      other.execute("UPDATE sync_health SET last_error = 'other' WHERE peer = 'main'");
      assertEquals("other", health.find("main").orElseThrow().lastError(), "and proceeds after");
    }
  }
}
