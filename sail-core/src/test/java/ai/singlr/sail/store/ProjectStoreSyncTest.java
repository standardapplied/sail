/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the sync machinery {@link ProjectStore} grows in Brick B — the contract a {@code
 * StoreReplica} will delegate to — mirroring the revision/CAS/tombstone behaviour proven for {@link
 * FileStore}. The comparable snapshot is the {@code definition} alone.
 */
@ActingAs(value = Actor.Lane.CLI, handle = "uday")
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
    store.upsert("acme", "name: acme\n");

    assertEquals(def("name: acme\n", "uday"), store.comparableSnapshot("acme"));
    assertNotEquals(null, store.latestRev("acme"));
    assertTrue(store.syncEntityIds().contains("acme"));
  }

  @Test
  void editingMintsANewRevisionAndComparableSnapshotFollows() {
    store.upsert("acme", "v1");
    var rev1 = store.latestRev("acme");

    store.upsert("acme", "v2");

    assertNotEquals(rev1, store.latestRev("acme"));
    assertEquals(def("v2", "uday"), store.comparableSnapshot("acme"));
    assertEquals(def("v1"), store.comparableAtRev("acme", rev1));
  }

  @Test
  @ActingAs(Actor.Lane.MAIN)
  void applyRevisionAdoptsMainsStateAtItsExactRevAsTheBase() {
    store.applyRevision("acme", def("from-main", "sumesh"), "rev-xyz");

    assertEquals(def("from-main", "sumesh"), store.comparableSnapshot("acme"));
    assertEquals("sumesh", new ChangeLog(db).history("project", "acme").getLast().actor());
    assertEquals("rev-xyz", store.latestRev("acme"));
    assertEquals("rev-xyz", store.baseRevOf("acme"));
  }

  @Test
  @ActingAs(value = Actor.Lane.SYNC, handle = "node")
  void commitAcceptsWhenExpectedRevMatchesAndMintsANewOne() {
    store.applyRevision("acme", def("base"), "rev-base");

    var outcome = store.commitRevision("acme", def("pushed"), "rev-base");

    var accepted = assertInstanceOf(PushOutcome.Accepted.class, outcome);
    assertNotEquals("rev-base", accepted.rev());
    assertEquals(accepted.rev(), store.latestRev("acme"));
    assertEquals(def("pushed", "node"), store.comparableSnapshot("acme"));
  }

  @Test
  @ActingAs(value = Actor.Lane.SYNC, handle = "node")
  void commitRejectsAsStaleWhenMainMovedUnderUs() {
    store.applyRevision("acme", def("base"), "rev-base");
    store.commitRevision("acme", def("moved"), "rev-base");

    var outcome = store.commitRevision("acme", def("racing"), "rev-base");

    var stale = assertInstanceOf(PushOutcome.Stale.class, outcome);
    assertEquals(def("moved", "node"), stale.currentSnapshot());
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
    store.upsert("acme", "local");

    store.applyRevision("acme", null, "rev-del");

    assertNull(store.comparableSnapshot("acme"));
    assertEquals("rev-del", store.latestRev("acme"));
  }

  @Test
  void resolveConflictTakeTheirsAdoptsMainsDefinition() {
    store.upsert("acme", "mine");

    store.resolveConflict("acme", def("theirs"), def("theirs"));

    assertEquals(def("theirs", "uday"), store.comparableSnapshot("acme"));
  }

  @Test
  void resolveConflictKeepMineWritesAForwardEditThatPushes() {
    store.applyRevision("acme", def("theirs"), "rev-theirs");

    store.resolveConflict("acme", def("mine"), def("theirs"));

    assertEquals(def("mine", "uday"), store.comparableSnapshot("acme"));
    assertNotEquals("rev-theirs", store.latestRev("acme"));
    assertNotEquals(
        store.baseRevOf("acme"), store.latestRev("acme"), "a forward local edit awaits push");
  }
}
