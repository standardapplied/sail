/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChangeLogTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private ChangeLog log;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    log = new ChangeLog(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  @Test
  void aLocalMutationRecordsNoPeer() {
    log.append("spec", "a", "1-x", "uday", "local", false, "{}");

    assertNull(log.history("spec", "a").getFirst().peer());
  }

  @Test
  void aRevisionAppendedUnderASyncPeerRecordsThatPeerAsItsProvenance() {
    SyncPeer.with("sumesh", () -> log.append("spec", "a", "1-x", "uday", "sync", false, "{}"));

    var entry = log.history("spec", "a").getFirst();
    assertEquals("sumesh", entry.peer());
    assertEquals("sync", entry.origin());
    assertEquals(
        "uday", entry.actor(), "actor stays the inherited author; peer names the box that pushed");
  }

  @Test
  void thePeerBindingIsScopedAndDoesNotLeakToLaterAppends() {
    SyncPeer.with("sumesh", () -> log.append("spec", "a", "1-x", "uday", "sync", false, "{}"));
    log.append("spec", "b", "1-y", "uday", "local", false, "{}");

    assertEquals("sumesh", log.history("spec", "a").getFirst().peer());
    assertNull(log.history("spec", "b").getFirst().peer());
  }

  @Test
  void historyIsOrderedBySequenceAndScopedToTheEntity() {
    log.append("spec", "a", "1-x", "uday", "local", false, "{}");
    log.append("spec", "b", "1-y", "ada", "local", false, "{}");
    log.append("spec", "a", "2-z", "uday", "local", false, "{}");

    var historyA = log.history("spec", "a");
    assertEquals(2, historyA.size());
    assertTrue(historyA.get(0).seq() < historyA.get(1).seq());
    assertEquals("1-x", historyA.get(0).rev());
    assertEquals("2-z", historyA.get(1).rev());
    assertEquals(1, log.history("spec", "b").size());
  }

  @Test
  void atFindsAnExactRevisionAndIsEmptyOtherwise() {
    log.append("spec", "a", "1-x", "uday", "local", false, "{\"k\":1}");

    var found = log.at("spec", "a", "1-x").orElseThrow();
    assertEquals("uday", found.actor());
    assertEquals("{\"k\":1}", found.snapshot());
    assertFalse(found.deleted());
    assertTrue(log.at("spec", "a", "9-z").isEmpty());
  }

  @Test
  void deletedFlagRoundTrips() {
    log.append("spec", "a", "3-x", null, "local", true, "{}");
    assertTrue(log.history("spec", "a").getFirst().deleted());
  }

  @Test
  void historyIsEmptyForAnUnknownEntity() {
    assertTrue(log.history("spec", "ghost").isEmpty());
  }
}
