/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the sync machinery {@link ProjectStore} grows in Brick B — the contract a {@code
 * ProjectReplica} will delegate to — mirroring the revision/CAS/tombstone behaviour proven for
 * {@link FileStore}. The comparable snapshot is the {@code definition} alone.
 */
class ProjectStoreSyncTest {

  @TempDir Path dir;
  private Sqlite db;
  private ProjectStore store;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(dir.resolve("test.db"));
    new SchemaManager(db).migrate();
    store = new ProjectStore(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private static Map<String, Object> def(String definition) {
    return Map.of("definition", definition);
  }

  private static Map<String, Object> def(String definition, String actor) {
    return Map.of("definition", definition, "_actor", actor);
  }

  @Test
  void upsertJournalsAComparableRevision() {
    store.upsert("acme", "name: acme\n", "uday");

    assertEquals(def("name: acme\n", "uday"), store.comparableSnapshot("acme"));
    assertNotEquals(null, store.latestRev("acme"));
    assertTrue(store.syncEntityIds().contains("acme"));
  }

  @Test
  void editingMintsANewRevisionAndComparableSnapshotFollows() {
    store.upsert("acme", "v1", "uday");
    var rev1 = store.latestRev("acme");

    store.upsert("acme", "v2", "uday");

    assertNotEquals(rev1, store.latestRev("acme"));
    assertEquals(def("v2", "uday"), store.comparableSnapshot("acme"));
    assertEquals(def("v1"), store.comparableAtRev("acme", rev1));
  }

  @Test
  void applyRevisionAdoptsMainsStateAtItsExactRevAsTheBase() {
    store.applyRevision("acme", def("from-main"), "rev-xyz");

    assertEquals(def("from-main", "sync"), store.comparableSnapshot("acme"));
    assertEquals("rev-xyz", store.latestRev("acme"));
    assertEquals("rev-xyz", store.baseRevOf("acme"));
  }

  @Test
  void commitAcceptsWhenExpectedRevMatchesAndMintsANewOne() {
    store.applyRevision("acme", def("base"), "rev-base");

    var outcome = store.commitRevision("acme", def("pushed"), "rev-base");

    var accepted = assertInstanceOf(PushOutcome.Accepted.class, outcome);
    assertNotEquals("rev-base", accepted.rev());
    assertEquals(accepted.rev(), store.latestRev("acme"));
    assertEquals(def("pushed", "sync"), store.comparableSnapshot("acme"));
  }

  @Test
  void commitRejectsAsStaleWhenMainMovedUnderUs() {
    store.applyRevision("acme", def("base"), "rev-base");
    store.commitRevision("acme", def("moved"), "rev-base");

    var outcome = store.commitRevision("acme", def("racing"), "rev-base");

    var stale = assertInstanceOf(PushOutcome.Stale.class, outcome);
    assertEquals(def("moved", "sync"), stale.currentSnapshot());
  }

  @Test
  void deleteTombstonesAndBaseRevSurvivesForDeleteVsEditDetection() {
    store.applyRevision("acme", def("base"), "rev-base");

    assertTrue(store.delete("acme"));

    assertNull(store.comparableSnapshot("acme"));
    assertEquals("rev-base", store.baseRevOf("acme"), "base rev recovered from the tombstone");
    assertTrue(store.syncEntityIds().contains("acme"), "tombstone still a known entity");
  }

  @Test
  void applyRevisionNullAdoptsADeletionFromMain() {
    store.upsert("acme", "local", "uday");

    store.applyRevision("acme", null, "rev-del");

    assertNull(store.comparableSnapshot("acme"));
    assertEquals("rev-del", store.latestRev("acme"));
  }

  @Test
  void resolveConflictTakeTheirsAdoptsMainsDefinition() {
    store.upsert("acme", "mine", "uday");

    store.resolveConflict("acme", def("theirs"), def("theirs"));

    assertEquals(def("theirs", "sync"), store.comparableSnapshot("acme"));
  }

  @Test
  void backfillMakesACataloguedButUnjournaledProjectSyncable() {
    db.execute(
        "INSERT INTO projects (name, definition, created_at, updated_at)"
            + " VALUES ('legacy', 'name: legacy\n', '2026-01-01', '2026-01-01')");
    assertFalse(store.syncEntityIds().contains("legacy"), "no change-log entry yet");

    assertEquals(1, store.backfillRevisions());

    assertTrue(store.syncEntityIds().contains("legacy"), "now visible to sync");
    assertEquals(def("name: legacy\n"), store.comparableSnapshot("legacy"));
  }

  @Test
  void backfillIsIdempotentAndSkipsAlreadyJournaledProjects() {
    store.upsert("acme", "v1", "uday");

    assertEquals(0, store.backfillRevisions(), "an already-journaled project is left untouched");
  }

  @Test
  void resolveConflictKeepMineWritesAForwardEditThatPushes() {
    store.applyRevision("acme", def("theirs"), "rev-theirs");

    store.resolveConflict("acme", def("mine"), def("theirs"));

    assertEquals(def("mine", "resolve"), store.comparableSnapshot("acme"));
    assertNotEquals("rev-theirs", store.latestRev("acme"));
    assertNotEquals(
        store.baseRevOf("acme"), store.latestRev("acme"), "a forward local edit awaits push");
  }
}
