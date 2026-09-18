/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.sync.SyncedEntities;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncStateTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SyncState state;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    state = new SyncState(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  @Test
  void checkpointDefaultsToZero() {
    assertEquals(0L, state.checkpoint("main", "spec"));
  }

  @Test
  void advanceThenReadBack() {
    state.advance("main", "spec", 42);
    assertEquals(42L, state.checkpoint("main", "spec"));
  }

  @Test
  void advanceIsMonotonicAndNeverGoesBackward() {
    state.advance("main", "spec", 42);
    state.advance("main", "spec", 10);
    assertEquals(42L, state.checkpoint("main", "spec"));
  }

  @Test
  void checkpointsAreTrackedPerPeerAndPerType() {
    state.advance("main", "spec", 5);
    state.advance("main", "file", 7);
    state.advance("backup", "spec", 9);
    assertEquals(5L, state.checkpoint("main", "spec"));
    assertEquals(7L, state.checkpoint("main", "file"));
    assertEquals(9L, state.checkpoint("backup", "spec"));
    assertEquals(0L, state.checkpoint("main", "run"));
  }

  @Test
  void theMigrationStartsEveryTypeFromZeroSoAnOverAdvancedProtocol3CheckpointHidesNothing() {
    try (var staged = Sqlite.open(tempDir.resolve("staged.db"))) {
      FloorSchema.stage(staged);
      staged.execute("PRAGMA foreign_keys = OFF");
      SchemaManager.ON_RAMP.forEach(staged::execute);
      var rewrite = migrationIndex("CREATE TABLE sync_state_v2");
      SchemaManager.MIGRATIONS.subList(0, rewrite).forEach(staged::execute);
      staged.execute("PRAGMA foreign_keys = ON");
      staged.execute(
          "INSERT INTO schema_version (version, applied_at) VALUES (?, 'staged')",
          SchemaManager.V1_VERSION + rewrite);
      staged.execute(
          "INSERT INTO sync_state (peer, checkpoint, updated_at) VALUES ('maindevbox', 314, 't0')");

      new SchemaManager(staged).migrate();

      var migrated = new SyncState(staged);
      for (var entity : SyncedEntities.all()) {
        assertEquals(0L, migrated.checkpoint("maindevbox", entity.type()), entity.type());
      }
      migrated.advance("maindevbox", "spec", 7);
      assertEquals(7L, migrated.checkpoint("maindevbox", "spec"));
      assertEquals(0L, migrated.checkpoint("maindevbox", "run"));
    }
  }

  private static int migrationIndex(String needle) {
    for (var i = 0; i < SchemaManager.MIGRATIONS.size(); i++) {
      if (SchemaManager.MIGRATIONS.get(i).contains(needle)) {
        return i;
      }
    }
    throw new AssertionError("no migration contains: " + needle);
  }
}
