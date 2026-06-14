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
}
