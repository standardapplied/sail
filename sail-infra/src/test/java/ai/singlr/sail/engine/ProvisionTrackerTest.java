/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProvisionTrackerTest {

  @TempDir Path tempDir;

  /** A simple phase enum for testing. */
  enum TestPhase {
    STEP_ONE,
    STEP_TWO,
    STEP_THREE,
    COMPLETE,
  }

  private Path stateFile() {
    return tempDir.resolve("provision-state.yaml");
  }

  @Test
  void freshTrackerHasNoCompletedPhases() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load();

    assertFalse(tracker.isCompleted(TestPhase.STEP_ONE));
    assertFalse(tracker.isCompleted(TestPhase.COMPLETE));
  }

  @Test
  void freshTrackerResumePointIsFirstPhase() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load();

    var resume = tracker.resumePoint();
    assertTrue(resume.isPresent());
    assertEquals(TestPhase.STEP_ONE, resume.get());
  }

  @Test
  void advancePersistsStateToDisk() throws Exception {
    var file = stateFile();
    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker.load();

    tracker.advance(TestPhase.STEP_ONE);

    assertTrue(Files.exists(file));
    var content = Files.readString(file);
    assertTrue(content.contains("STEP_ONE"));
  }

  @Test
  void loadReadsPersistedState() throws Exception {
    var file = stateFile();
    var tracker1 = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker1.load();
    tracker1.advance(TestPhase.STEP_TWO);

    var tracker2 = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker2.load();

    assertTrue(tracker2.isCompleted(TestPhase.STEP_ONE));
    assertTrue(tracker2.isCompleted(TestPhase.STEP_TWO));
    assertFalse(tracker2.isCompleted(TestPhase.STEP_THREE));
  }

  @Test
  void isCompletedReturnsTrueForPriorPhases() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load();
    tracker.advance(TestPhase.STEP_THREE);

    assertTrue(tracker.isCompleted(TestPhase.STEP_ONE));
    assertTrue(tracker.isCompleted(TestPhase.STEP_TWO));
    assertTrue(tracker.isCompleted(TestPhase.STEP_THREE));
    assertFalse(tracker.isCompleted(TestPhase.COMPLETE));
  }

  @Test
  void resumePointReturnsFirstIncompletePhase() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load();
    tracker.advance(TestPhase.STEP_TWO);

    var resume = tracker.resumePoint();
    assertTrue(resume.isPresent());
    assertEquals(TestPhase.STEP_THREE, resume.get());
  }

  @Test
  void resumePointReturnsEmptyWhenAllComplete() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load();
    tracker.advance(TestPhase.COMPLETE);

    var resume = tracker.resumePoint();
    assertTrue(resume.isEmpty());
  }

  @Test
  void recordFailurePersistsErrorDetails() throws Exception {
    var file = stateFile();
    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker.load();
    tracker.advance(TestPhase.STEP_ONE);
    tracker.recordFailure(TestPhase.STEP_TWO, "apt-get failed: network unreachable");

    var state = tracker.currentState();
    assertEquals("STEP_ONE", state.completedPhase());
    assertNotNull(state.error());
    assertEquals("STEP_TWO", state.error().failedPhase());
    assertEquals("apt-get failed: network unreachable", state.error().message());
    assertNotNull(state.error().failedAt());

    var content = Files.readString(file);
    assertTrue(content.contains("STEP_TWO"));
    assertTrue(content.contains("network unreachable"));
  }

  @Test
  void advanceClearsPriorError() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load();
    tracker.advance(TestPhase.STEP_ONE);
    tracker.recordFailure(TestPhase.STEP_TWO, "some error");

    assertNotNull(tracker.currentState().error());

    tracker.advance(TestPhase.STEP_TWO);

    assertNull(tracker.currentState().error());
  }

  @Test
  void cleanupDeletesStateFile() throws Exception {
    var file = stateFile();
    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker.load();
    tracker.advance(TestPhase.COMPLETE);

    assertTrue(Files.exists(file));

    tracker.cleanup();

    assertFalse(Files.exists(file));
  }

  @Test
  void cleanupNoOpWhenNoFile() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load();

    assertDoesNotThrow(() -> tracker.cleanup());
  }

  @Test
  void dryRunDoesNotWriteStateToDisk() throws Exception {
    var file = stateFile();
    var tracker = new ProvisionTracker<>(TestPhase.class, file, true);
    tracker.load();

    tracker.advance(TestPhase.STEP_ONE);
    tracker.advance(TestPhase.STEP_TWO);

    assertFalse(Files.exists(file));
  }

  @Test
  void dryRunStillTracksInMemory() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), true);
    tracker.load();

    tracker.advance(TestPhase.STEP_TWO);

    assertTrue(tracker.isCompleted(TestPhase.STEP_ONE));
    assertTrue(tracker.isCompleted(TestPhase.STEP_TWO));
    assertFalse(tracker.isCompleted(TestPhase.STEP_THREE));
  }

  @Test
  void hasIncompleteRunDetectsPartialState() throws Exception {
    var file = stateFile();
    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker.load();
    tracker.advance(TestPhase.STEP_TWO);

    var tracker2 = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker2.load();

    assertTrue(tracker2.hasIncompleteRun());
  }

  @Test
  void hasIncompleteRunReturnsFalseWhenComplete() throws Exception {
    var file = stateFile();
    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker.load();
    tracker.advance(TestPhase.COMPLETE);

    var tracker2 = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker2.load();

    assertFalse(tracker2.hasIncompleteRun());
  }

  @Test
  void hasIncompleteRunReturnsFalseForFreshState() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load();

    assertFalse(tracker.hasIncompleteRun());
  }

  @Test
  void atomicWriteDoesNotLeavePartialFile() throws Exception {
    var file = stateFile();
    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker.load();
    tracker.advance(TestPhase.STEP_ONE);

    var tmpFile = file.resolveSibling(file.getFileName() + ".tmp");
    assertFalse(Files.exists(tmpFile));
    assertTrue(Files.exists(file));
  }

  @Test
  void stateFileContainsTimestamps() throws Exception {
    var file = stateFile();
    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker.load();
    tracker.advance(TestPhase.STEP_ONE);

    var state = tracker.currentState();
    assertNotNull(state.startedAt());
    assertNotNull(state.updatedAt());
  }

  @Test
  void startedAtPreservedAcrossAdvances() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load();
    tracker.advance(TestPhase.STEP_ONE);

    var startedAt = tracker.currentState().startedAt();

    tracker.advance(TestPhase.STEP_TWO);

    assertEquals(startedAt, tracker.currentState().startedAt());
  }

  @Test
  void advanceRejectsRegressionToEarlierPhase() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load();
    tracker.advance(TestPhase.STEP_TWO);

    var ex = assertThrows(IllegalStateException.class, () -> tracker.advance(TestPhase.STEP_ONE));
    assertTrue(ex.getMessage().contains("Cannot regress from STEP_TWO to STEP_ONE"));
  }

  @Test
  void advanceRejectsReplayOfSamePhase() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load();
    tracker.advance(TestPhase.STEP_ONE);

    var ex = assertThrows(IllegalStateException.class, () -> tracker.advance(TestPhase.STEP_ONE));
    assertTrue(ex.getMessage().contains("Cannot regress from STEP_ONE to STEP_ONE"));
  }

  @Test
  void dryRunCleanupIsNoOp() throws Exception {
    var file = stateFile();
    var realTracker = new ProvisionTracker<>(TestPhase.class, file, false);
    realTracker.load();
    realTracker.advance(TestPhase.STEP_ONE);
    assertTrue(Files.exists(file));

    var dryTracker = new ProvisionTracker<>(TestPhase.class, file, true);
    dryTracker.cleanup();
    assertTrue(Files.exists(file));
  }

  @Test
  void resetClearsInMemoryStateToEmpty() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load();
    tracker.advance(TestPhase.STEP_TWO);

    assertTrue(tracker.isCompleted(TestPhase.STEP_ONE));
    assertTrue(tracker.isCompleted(TestPhase.STEP_TWO));

    tracker.reset();

    assertFalse(tracker.isCompleted(TestPhase.STEP_ONE));
    assertFalse(tracker.isCompleted(TestPhase.STEP_TWO));
    assertNull(tracker.currentState().completedPhase());
  }

  @Test
  void resetDeletesStateFileOnDisk() throws Exception {
    var file = stateFile();
    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker.load();
    tracker.advance(TestPhase.STEP_ONE);
    assertTrue(Files.exists(file));

    tracker.reset();

    assertFalse(Files.exists(file));
  }

  @Test
  void resetMakesHasIncompleteRunReturnFalse() throws Exception {
    var file = stateFile();
    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker.load();
    tracker.advance(TestPhase.STEP_TWO);
    assertTrue(tracker.hasIncompleteRun());

    tracker.reset();

    assertFalse(tracker.hasIncompleteRun());
  }

  @Test
  void resetAllowsAdvancingFromFirstPhaseAgain() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load();
    tracker.advance(TestPhase.STEP_THREE);

    tracker.reset();

    assertDoesNotThrow(() -> tracker.advance(TestPhase.STEP_ONE));
    assertTrue(tracker.isCompleted(TestPhase.STEP_ONE));
    assertFalse(tracker.isCompleted(TestPhase.STEP_TWO));
  }

  @Test
  void resetResumePointReturnsFirstPhase() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load();
    tracker.advance(TestPhase.STEP_THREE);

    tracker.reset();

    var resume = tracker.resumePoint();
    assertTrue(resume.isPresent());
    assertEquals(TestPhase.STEP_ONE, resume.get());
  }

  @Test
  void dryRunResetClearsMemoryButKeepsFile() throws Exception {
    var file = stateFile();
    var realTracker = new ProvisionTracker<>(TestPhase.class, file, false);
    realTracker.load();
    realTracker.advance(TestPhase.STEP_TWO);
    assertTrue(Files.exists(file));

    var dryTracker = new ProvisionTracker<>(TestPhase.class, file, true);
    dryTracker.load();
    assertTrue(dryTracker.isCompleted(TestPhase.STEP_TWO));

    dryTracker.reset();

    assertFalse(dryTracker.isCompleted(TestPhase.STEP_ONE));
    assertTrue(Files.exists(file), "State file should remain on disk in dry-run mode");
  }

  @Test
  void resetIsIdempotentOnFreshTracker() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load();

    assertDoesNotThrow(() -> tracker.reset());
    assertFalse(tracker.hasIncompleteRun());
    assertNull(tracker.currentState().completedPhase());
  }
}
