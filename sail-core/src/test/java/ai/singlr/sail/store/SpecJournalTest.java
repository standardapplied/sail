/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecJournalTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SpecStore store;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    store = new SpecStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private SpecStore.SpecRow spec(String id, String title, String status, String updatedBy) {
    return new SpecStore.SpecRow(
        id,
        "proj",
        title,
        SpecStatus.fromWire(status),
        null,
        null,
        null,
        null,
        null,
        0,
        "uday",
        "",
        "",
        updatedBy,
        List.of(),
        List.of());
  }

  @Test
  void createRecordsAnInitialRevision() {
    store.create(spec("auth", "Auth", "pending", "uday"));

    var history = store.history("auth");
    assertEquals(1, history.size());
    var first = history.getFirst();
    assertEquals("1", first.rev().split("-")[0]);
    assertEquals("local", first.origin());
    assertFalse(first.deleted());
    assertTrue(first.snapshot().contains("\"title\": \"Auth\""), first.snapshot());
  }

  @Test
  void everyMutationAppendsARevisionInOrder() {
    store.create(spec("auth", "Auth", "pending", "uday"));
    store.update(spec("auth", "Auth v2", "pending", "ada"));
    store.setContent("auth", "the body", "the plan");
    store.updateStatus("auth", SpecStatus.fromWire("in_progress"));

    var history = store.history("auth");
    assertEquals(4, history.size());
    assertEquals(
        List.of(1L, 2L, 3L, 4L), history.stream().map(e -> Revisions.counterOf(e.rev())).toList());
    assertTrue(history.getLast().snapshot().contains("\"the body\""));
    assertTrue(history.getLast().snapshot().contains("in_progress"));
  }

  @Test
  void deleteRecordsATombstoneRetainingTheLastSnapshot() {
    store.create(spec("auth", "Auth", "pending", "uday"));
    store.setContent("auth", "important work", "");

    store.delete("auth");

    assertTrue(store.findById("auth").isEmpty());
    var history = store.history("auth");
    assertTrue(history.getLast().deleted());
    assertTrue(history.getLast().snapshot().contains("important work"), "work survives the delete");
  }

  @Test
  void restoreBringsBackPriorContentAsANewRevision() {
    store.create(spec("auth", "Auth", "pending", "uday"));
    store.setContent("auth", "original body", "original plan");
    var goodRev = store.history("auth").getLast().rev();
    store.setContent("auth", "clobbered", "clobbered");

    store.restore("auth", goodRev);

    var content = store.getContent("auth").orElseThrow();
    assertEquals("original body", content.body());
    assertEquals("original plan", content.plan());
    var last = store.history("auth").getLast();
    assertEquals("restore", last.origin());
    assertTrue(
        Revisions.counterOf(last.rev()) > Revisions.counterOf(goodRev),
        "restore is a new forward revision, not a rewind");
  }

  @Test
  void restoreReCreatesADeletedSpec() {
    store.create(spec("auth", "Auth", "pending", "uday"));
    store.setContent("auth", "body", "plan");
    var beforeDelete = store.history("auth").getLast().rev();
    store.delete("auth");

    store.restore("auth", beforeDelete);

    var restored = store.findById("auth").orElseThrow();
    assertEquals("Auth", restored.title());
    assertEquals("body", store.getContent("auth").orElseThrow().body());
  }

  @Test
  void restoreRejectsUnknownRevision() {
    store.create(spec("auth", "Auth", "pending", "uday"));
    var thrown = assertThrows(IllegalArgumentException.class, () -> store.restore("auth", "99-x"));
    assertTrue(thrown.getMessage().contains("99-x"));
  }

  @Test
  void specRevColumnTracksTheCurrentRevision() {
    store.create(spec("auth", "Auth", "pending", "uday"));
    store.update(spec("auth", "Auth v2", "pending", "ada"));

    var rev = db.queryOne("SELECT rev FROM specs WHERE id = ?", row -> row.text(0), "auth");
    assertEquals(store.history("auth").getLast().rev(), rev.orElseThrow());
  }
}
