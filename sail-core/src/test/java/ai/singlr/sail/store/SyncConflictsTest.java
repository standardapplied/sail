/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncConflictsTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SyncConflicts conflicts;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    conflicts = new SyncConflicts(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  @Test
  void recordThenReadPending() {
    var id =
        conflicts.record(
            "spec",
            "auth",
            "{base}",
            "{local}",
            "{remote}",
            "4-main",
            "mady",
            List.of("title", "status"));

    var pending = conflicts.pending();
    assertEquals(1, pending.size());
    var c = pending.getFirst();
    assertEquals(id, c.id());
    assertEquals("auth", c.entityId());
    assertEquals("{local}", c.localSnapshot());
    assertEquals("4-main", c.remoteRev());
    assertEquals("mady", c.remoteAuthor());
    assertEquals(List.of("title", "status"), c.fields());
    assertEquals("pending", c.status());
  }

  @Test
  void theirsIsMainsSideAtItsRevisionAndAuthor() {
    conflicts.record(
        "spec", "auth", null, null, "{\"title\": \"theirs\"}", "4-main", "mady", List.of("title"));
    conflicts.record("spec", "gone", null, "{}", null, "5-main", "mady", List.of("<deleted>"));

    assertEquals(
        new MainVersion(Map.of("title", "theirs"), "4-main", "mady"),
        conflicts.pendingFor("spec", "auth").orElseThrow().theirs());
    assertEquals(
        new MainVersion(null, "5-main", "mady"),
        conflicts.pendingFor("spec", "gone").orElseThrow().theirs());
  }

  @Test
  void recordingAgainReplacesTheOpenConflictForThatEntity() {
    conflicts.record("spec", "auth", "b1", "l1", "r1", null, null, List.of("title"));
    conflicts.record("spec", "auth", "b2", "l2", "r2", null, null, List.of("status"));

    var pending = conflicts.pending();
    assertEquals(1, pending.size());
    assertEquals("l2", pending.getFirst().localSnapshot());
  }

  @Test
  void pendingForFindsByEntity() {
    conflicts.record("spec", "auth", "b", "l", "r", null, null, List.of("title"));
    assertTrue(conflicts.pendingFor("spec", "auth").isPresent());
    assertTrue(conflicts.pendingFor("spec", "other").isEmpty());
  }

  @Test
  void resolveMarksResolvedAndRemovesFromPending() {
    var id = conflicts.record("spec", "auth", "b", "l", "r", null, null, List.of("title"));

    assertTrue(conflicts.resolve(id, "5-merged"));
    assertTrue(conflicts.pending().isEmpty());
    assertFalse(conflicts.resolve(id, "5-merged"), "already resolved");
  }

  @Test
  void emptyFieldListRoundTrips() {
    conflicts.record("spec", "auth", "b", "l", "r", null, null, List.of());
    assertEquals(List.of(), conflicts.pending().getFirst().fields());
  }

  @Test
  void conflictsAreScopedAndOrderedById() {
    conflicts.record("spec", "a", "b", "l", "r", null, null, List.of("x"));
    conflicts.record("spec", "b", "b", "l", "r", null, null, List.of("y"));

    var pending = conflicts.pending();
    assertEquals(2, pending.size());
    assertTrue(pending.get(0).id() < pending.get(1).id());
  }
}
