/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
  void renameTombstonesTheOldIdentityAndCreatesANewEntity() {
    store.upsert("old", "name: old\nimage: ubuntu/24.04\n", "uday");
    var rev = store.latestRev("old");

    store.rename("old", "renamed", "name: renamed\nimage: ubuntu/24.04\n");

    assertTrue(store.findByName("old").isEmpty(), "the old name is gone");
    var row = store.findByName("renamed").orElseThrow();
    assertTrue(row.definition().contains("name: renamed"));
    assertTrue(store.latestRev("old") != null, "the old identity keeps a tombstone");
    assertTrue(store.latestRev("renamed") != null, "the new identity has its own revision");
    assertTrue(store.blocksResurrection("old"));
    assertFalse(store.blocksResurrection("renamed"));
    assertFalse(rev.equals(store.latestRev("old")), "the tombstone advances old's revision");
    assertFalse(rev.equals(store.latestRev("renamed")), "the new identity starts fresh history");
    assertTrue(store.syncEntityIds().containsAll(List.of("old", "renamed")));
  }

  @Test
  void renameIsIdempotentOnceTheOldNameIsGone() {
    store.upsert("old", "name: old\n", "uday");
    store.rename("old", "renamed", "name: renamed\n");

    store.rename("old", "renamed", "name: renamed\n");

    assertTrue(store.findByName("renamed").isPresent());
  }

  @Test
  void renameRefusesToReplaceAnExistingProject() {
    store.upsert("old", "name: old\n", "uday");
    store.upsert("taken", "name: taken\n", "sumesh");

    assertThrows(IllegalStateException.class, () -> store.rename("old", "taken", "name: taken\n"));

    assertTrue(store.findByName("old").isPresent());
    assertEquals("sumesh", store.findByName("taken").orElseThrow().updatedBy());
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
        "git:\n  name: Uday Chandra\n  email: uday@example.com\n"
            + "ssh:\n  authorized_keys:\n    - ssh-ed25519 SECRETKEY main\n",
        "uday");

    var definition = store.findByName("acme").orElseThrow().definition();
    assertTrue(definition.contains("${GIT_NAME}"));
    assertTrue(definition.contains("${SSH_PUBLIC_KEY}"));
    assertFalse(definition.contains("Uday Chandra"));
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
