/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    assertEquals(0L, state.checkpoint("main"));
  }

  @Test
  void advanceThenReadBack() {
    state.advance("main", 42);
    assertEquals(42L, state.checkpoint("main"));
  }

  @Test
  void advanceIsMonotonicAndNeverGoesBackward() {
    state.advance("main", 42);
    state.advance("main", 10);
    assertEquals(42L, state.checkpoint("main"));
  }

  @Test
  void checkpointsAreTrackedPerPeer() {
    state.advance("main", 5);
    state.advance("backup", 9);
    assertEquals(5L, state.checkpoint("main"));
    assertEquals(9L, state.checkpoint("backup"));
  }
}
