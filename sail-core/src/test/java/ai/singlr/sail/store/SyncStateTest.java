/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Map;
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
  void aMarkIsWhatTheNodeReadOfEachTypeItHoldsACheckpointOver() {
    state.read("main", "spec", 40, "s1", "2-a");
    state.advance("main", "spec", 42);
    state.advance("main", "file", 7);
    state.read("main", "room", 9, "r1", "1-a");
    state.read("backup", "spec", 3, "s0", "1-a");
    state.advance("backup", "spec", 3);

    assertEquals(
        Map.of(
            "spec", new SyncState.Mark(42, 40, "s1", "2-a"), "file", SyncState.Mark.nothingRead(7)),
        state.marks("main"),
        "a room read at no checkpoint is walked from the first head anyway");
  }

  @Test
  void aReadPastItsCheckpointIsTheMarkSoARoundCutShortStillNamesWhatItTook() {
    state.advance("main", "spec", 42);
    state.read("main", "spec", 50, "s2", "1-b");

    assertEquals(Map.of("spec", new SyncState.Mark(42, 50, "s2", "1-b")), state.marks("main"));

    state.advance("main", "spec", 50);
    assertEquals(Map.of("spec", new SyncState.Mark(50, 50, "s2", "1-b")), state.marks("main"));
  }

  @Test
  void resettingAPeerForgetsWhatWasReadAlongWithHowFar() {
    state.read("main", "spec", 40, "s1", "2-a");
    state.advance("main", "spec", 42);

    state.reset("main");
    assertEquals(Map.of(), state.marks("main"));

    state.advance("main", "spec", 42);
    assertEquals(Map.of("spec", SyncState.Mark.nothingRead(42)), state.marks("main"));
  }

  @Test
  void aMarkNamesAnEntryExactlyWhenItReadOneAtACheckpoint() {
    var refused =
        assertThrows(IllegalArgumentException.class, () -> new SyncState.Mark(0, 5, "s", "1-a"));

    assertEquals("A mark reads seq 5 of s at 1-a, checkpoint 0", refused.getMessage());
    assertThrows(IllegalArgumentException.class, () -> new SyncState.Mark(4, -1, "s", "1-a"));
    assertThrows(IllegalArgumentException.class, () -> new SyncState.Mark(4, 0, "s", "1-a"));
    assertThrows(IllegalArgumentException.class, () -> new SyncState.Mark(4, 3, "", ""));
  }
}
