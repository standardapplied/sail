/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectStoreTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private ProjectStore store;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    store = new ProjectStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  @Test
  void renameTombstonesTheOldIdentityAndCreatesTheNew() {
    store.upsert("old", "name: old\nimage: ubuntu/24.04\n", "uday");
    var oldRev = store.latestRev("old");

    store.rename("old", "renamed", "name: renamed\nimage: ubuntu/24.04\n");

    assertTrue(store.findByName("old").isEmpty(), "the old name is gone from the catalog");
    assertTrue(
        store.blocksResurrection("old"),
        "the old identity keeps a resurrection-blocking tombstone so a stale peer cannot revive it");
    var row = store.findByName("renamed").orElseThrow();
    assertTrue(row.definition().contains("name: renamed"));
    assertNotEquals(
        oldRev, store.latestRev("renamed"), "the new identity is a fresh revision, not a re-key");
  }

  @Test
  void renameIsIdempotentOnceTheOldNameIsGone() {
    store.upsert("old", "name: old\n", "uday");
    store.rename("old", "renamed", "name: renamed\n");

    store.rename("old", "renamed", "name: renamed\n");

    assertTrue(store.findByName("renamed").isPresent());
  }

  @Test
  void renameOntoALiveNameIsRejectedAndLeavesBothIntact() {
    store.upsert("old", "name: old\n", "uday");
    store.upsert("taken", "name: taken\n", "uday");

    assertThrows(IllegalStateException.class, () -> store.rename("old", "taken", "name: taken\n"));

    assertTrue(store.findByName("old").isPresent(), "the failed rename left the old name intact");
    assertFalse(store.blocksResurrection("old"), "a rejected rename records no tombstone");
  }

  @Test
  void plainDeleteDoesNotBlockResurrection() {
    store.upsert("p", "name: p\n", "uday");

    store.delete("p");

    assertTrue(store.findByName("p").isEmpty(), "the plain delete removed the catalog row");
    assertFalse(
        store.blocksResurrection("p"),
        "only a rename tombstone is authoritative over a stale create; a plain delete is not");
  }

  @Test
  void upsertInsertsAndRoundTripsTheDefinitionBlob() {
    store.upsert("acme", "name: acme\nresources:\n  cpu: 2\n", "uday");

    var row = store.findByName("acme").orElseThrow();
    assertEquals("acme", row.name());
    assertEquals("name: acme\nresources:\n  cpu: 2\n", row.definition());
    assertEquals("uday", row.createdBy());
    assertEquals("uday", row.updatedBy());
    assertEquals(row.createdAt(), row.updatedAt());
  }

  @Test
  void upsertReplacesDefinitionPreservingCreationProvenance() {
    store.upsert("acme", "v1", "uday");
    var created = store.findByName("acme").orElseThrow();

    store.upsert("acme", "v2", "ada");

    var updated = store.findByName("acme").orElseThrow();
    assertEquals("v2", updated.definition());
    assertEquals("uday", updated.createdBy(), "created_by is preserved across an update");
    assertEquals(created.createdAt(), updated.createdAt(), "created_at is preserved");
    assertEquals("ada", updated.updatedBy());
    assertTrue(updated.updatedAt().compareTo(updated.createdAt()) >= 0, "updated_at is monotonic");
  }

  @Test
  void listIsOrderedByName() {
    store.upsert("zeta", "z", null);
    store.upsert("alpha", "a", null);
    store.upsert("mu", "m", null);

    assertEquals(
        List.of("alpha", "mu", "zeta"),
        store.list().stream().map(ProjectStore.ProjectRow::name).toList());
  }

  @Test
  void findByNameIsEmptyWhenAbsent() {
    assertTrue(store.findByName("ghost").isEmpty());
  }

  @Test
  void deleteRemovesAndReportsWhetherARowWentAway() {
    store.upsert("acme", "x", null);

    assertTrue(store.delete("acme"));
    assertTrue(store.findByName("acme").isEmpty());
    assertFalse(store.delete("acme"));
  }

  @Test
  void definitionMayBeNullActorButNotNullBlob() {
    store.upsert("acme", "x", null);
    assertNull(store.findByName("acme").orElseThrow().createdBy());
    assertThrows(SqliteException.class, () -> store.upsert("broken", null, null));
  }

  @Test
  void upsertRedactsPersonalFieldsBeforeStoring() {
    store.upsert(
        "acme",
        "git:\n  name: Alex Morgan\n  email: uday@example.com\n"
            + "ssh:\n  authorized_keys:\n    - ssh-ed25519 SECRETKEY main\n",
        "uday");

    var definition = store.findByName("acme").orElseThrow().definition();
    assertTrue(definition.contains("${GIT_NAME}"));
    assertTrue(definition.contains("${SSH_PUBLIC_KEY}"));
    assertFalse(definition.contains("Alex Morgan"));
    assertFalse(definition.contains("SECRETKEY"));
  }

  @Test
  void theSyncedSnapshotCarriesPlaceholdersNotIdentity() {
    store.upsert("acme", "git:\n  name: Uday\n  email: uday@example.com\n", "uday");

    var snapshot = store.comparableSnapshot("acme");
    assertTrue(snapshot.get("definition").toString().contains("${GIT_NAME}"));
    assertFalse(snapshot.get("definition").toString().contains("uday@example.com"));
  }

  @Test
  void canonicalizeScrubsAPreBrickRowAndCountsIt() {
    db.execute(
        "INSERT INTO projects (name, definition, created_at, updated_at)"
            + " VALUES ('legacy', 'git:\n  name: Uday\n  email: uday@example.com\n', 'now', 'now')");

    assertEquals(1, store.canonicalizeDefinitions());

    var definition = store.findByName("legacy").orElseThrow().definition();
    assertTrue(definition.contains("${GIT_NAME}"));
    assertFalse(definition.contains("uday@example.com"));
  }

  @Test
  void canonicalizeIsANoOpForAlreadyRedactedRows() {
    store.upsert("acme", "git:\n  name: Uday\n  email: uday@example.com\n", "uday");
    assertEquals(0, store.canonicalizeDefinitions(), "upsert already redacted it");
  }
}
