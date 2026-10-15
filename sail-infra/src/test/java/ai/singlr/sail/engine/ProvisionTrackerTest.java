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

import ai.singlr.sail.config.YamlUtil;
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

  private static final String X = "inputs-x";
  private static final String Y = "inputs-y";

  private Path stateFile() {
    return tempDir.resolve("provision-state.yaml");
  }

  @Test
  void freshTrackerHasNoCompletedPhases() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load(X);

    assertFalse(tracker.isCompleted(TestPhase.STEP_ONE));
    assertFalse(tracker.isCompleted(TestPhase.COMPLETE));
  }

  @Test
  void freshTrackerResumePointIsFirstPhase() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load(X);

    var resume = tracker.resumePoint();
    assertTrue(resume.isPresent());
    assertEquals(TestPhase.STEP_ONE, resume.get());
  }

  @Test
  void advancePersistsStateToDisk() throws Exception {
    var file = stateFile();
    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker.load(X);

    tracker.advance(TestPhase.STEP_ONE);

    assertTrue(Files.exists(file));
    var content = Files.readString(file);
    assertTrue(content.contains("STEP_ONE"));
  }

  @Test
  void aCompletedPhaseThisVersionNoLongerHasIsReadAsNoProgress() throws Exception {
    var file = stateFile();
    Files.writeString(
        file,
        "completed_phase: STEP_GONE\nstarted_at: 2026-10-09T00:00:00Z\n"
            + "updated_at: 2026-10-09T00:00:00Z\n");
    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);

    tracker.load(X);

    assertFalse(tracker.isCompleted(TestPhase.STEP_ONE));
    assertEquals(TestPhase.STEP_ONE, tracker.resumePoint().orElseThrow());
  }

  @Test
  void loadReadsPersistedState() throws Exception {
    var file = stateFile();
    var tracker1 = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker1.load(X);
    tracker1.advance(TestPhase.STEP_TWO);

    var tracker2 = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker2.load(X);

    assertTrue(tracker2.isCompleted(TestPhase.STEP_ONE));
    assertTrue(tracker2.isCompleted(TestPhase.STEP_TWO));
    assertFalse(tracker2.isCompleted(TestPhase.STEP_THREE));
  }

  @Test
  void isCompletedReturnsTrueForPriorPhases() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load(X);
    tracker.advance(TestPhase.STEP_THREE);

    assertTrue(tracker.isCompleted(TestPhase.STEP_ONE));
    assertTrue(tracker.isCompleted(TestPhase.STEP_TWO));
    assertTrue(tracker.isCompleted(TestPhase.STEP_THREE));
    assertFalse(tracker.isCompleted(TestPhase.COMPLETE));
  }

  @Test
  void resumePointReturnsFirstIncompletePhase() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load(X);
    tracker.advance(TestPhase.STEP_TWO);

    var resume = tracker.resumePoint();
    assertTrue(resume.isPresent());
    assertEquals(TestPhase.STEP_THREE, resume.get());
  }

  @Test
  void resumePointReturnsEmptyWhenAllComplete() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load(X);
    tracker.advance(TestPhase.COMPLETE);

    var resume = tracker.resumePoint();
    assertTrue(resume.isEmpty());
  }

  @Test
  void recordFailurePersistsErrorDetails() throws Exception {
    var file = stateFile();
    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker.load(X);
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
    tracker.load(X);
    tracker.advance(TestPhase.STEP_ONE);
    tracker.recordFailure(TestPhase.STEP_TWO, "some error");

    assertNotNull(tracker.currentState().error());

    tracker.advance(TestPhase.STEP_TWO);

    assertNull(tracker.currentState().error());
  }

  @Test
  void dryRunDoesNotWriteStateToDisk() throws Exception {
    var file = stateFile();
    var tracker = new ProvisionTracker<>(TestPhase.class, file, true);
    tracker.load(X);

    tracker.advance(TestPhase.STEP_ONE);
    tracker.advance(TestPhase.STEP_TWO);

    assertFalse(Files.exists(file));
  }

  @Test
  void dryRunStillTracksInMemory() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), true);
    tracker.load(X);

    tracker.advance(TestPhase.STEP_TWO);

    assertTrue(tracker.isCompleted(TestPhase.STEP_ONE));
    assertTrue(tracker.isCompleted(TestPhase.STEP_TWO));
    assertFalse(tracker.isCompleted(TestPhase.STEP_THREE));
  }

  @Test
  void hasIncompleteRunDetectsPartialState() throws Exception {
    var file = stateFile();
    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker.load(X);
    tracker.advance(TestPhase.STEP_TWO);

    var tracker2 = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker2.load(X);

    assertTrue(tracker2.hasIncompleteRun());
  }

  @Test
  void hasIncompleteRunReturnsFalseWhenComplete() throws Exception {
    var file = stateFile();
    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker.load(X);
    tracker.advance(TestPhase.COMPLETE);

    var tracker2 = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker2.load(X);

    assertFalse(tracker2.hasIncompleteRun());
  }

  @Test
  void hasIncompleteRunReturnsFalseForFreshState() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load(X);

    assertFalse(tracker.hasIncompleteRun());
  }

  @Test
  void atomicWriteDoesNotLeavePartialFile() throws Exception {
    var file = stateFile();
    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker.load(X);
    tracker.advance(TestPhase.STEP_ONE);

    var tmpFile = file.resolveSibling(file.getFileName() + ".tmp");
    assertFalse(Files.exists(tmpFile));
    assertTrue(Files.exists(file));
  }

  @Test
  void stateFileContainsTimestamps() throws Exception {
    var file = stateFile();
    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker.load(X);
    tracker.advance(TestPhase.STEP_ONE);

    var state = tracker.currentState();
    assertNotNull(state.startedAt());
    assertNotNull(state.updatedAt());
  }

  @Test
  void startedAtPreservedAcrossAdvances() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load(X);
    tracker.advance(TestPhase.STEP_ONE);

    var startedAt = tracker.currentState().startedAt();

    tracker.advance(TestPhase.STEP_TWO);

    assertEquals(startedAt, tracker.currentState().startedAt());
  }

  @Test
  void advanceRejectsRegressionToEarlierPhase() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load(X);
    tracker.advance(TestPhase.STEP_TWO);

    var ex = assertThrows(IllegalStateException.class, () -> tracker.advance(TestPhase.STEP_ONE));
    assertTrue(ex.getMessage().contains("Cannot regress from STEP_TWO to STEP_ONE"));
  }

  @Test
  void advanceRejectsReplayOfSamePhase() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load(X);
    tracker.advance(TestPhase.STEP_ONE);

    var ex = assertThrows(IllegalStateException.class, () -> tracker.advance(TestPhase.STEP_ONE));
    assertTrue(ex.getMessage().contains("Cannot regress from STEP_ONE to STEP_ONE"));
  }

  @Test
  void resetClearsInMemoryStateToEmpty() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load(X);
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
    tracker.load(X);
    tracker.advance(TestPhase.STEP_ONE);
    assertTrue(Files.exists(file));

    tracker.reset();

    assertFalse(Files.exists(file));
  }

  @Test
  void resetMakesHasIncompleteRunReturnFalse() throws Exception {
    var file = stateFile();
    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker.load(X);
    tracker.advance(TestPhase.STEP_TWO);
    assertTrue(tracker.hasIncompleteRun());

    tracker.reset();

    assertFalse(tracker.hasIncompleteRun());
  }

  @Test
  void resetAllowsAdvancingFromFirstPhaseAgain() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load(X);
    tracker.advance(TestPhase.STEP_THREE);

    tracker.reset();

    assertDoesNotThrow(() -> tracker.advance(TestPhase.STEP_ONE));
    assertTrue(tracker.isCompleted(TestPhase.STEP_ONE));
    assertFalse(tracker.isCompleted(TestPhase.STEP_TWO));
  }

  @Test
  void resetResumePointReturnsFirstPhase() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load(X);
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
    realTracker.load(X);
    realTracker.advance(TestPhase.STEP_TWO);
    assertTrue(Files.exists(file));

    var dryTracker = new ProvisionTracker<>(TestPhase.class, file, true);
    dryTracker.load(X);
    assertTrue(dryTracker.isCompleted(TestPhase.STEP_TWO));

    dryTracker.reset();

    assertFalse(dryTracker.isCompleted(TestPhase.STEP_ONE));
    assertTrue(Files.exists(file), "State file should remain on disk in dry-run mode");
  }

  @Test
  void resetIsIdempotentOnFreshTracker() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load(X);

    assertDoesNotThrow(() -> tracker.reset());
    assertFalse(tracker.hasIncompleteRun());
    assertNull(tracker.currentState().completedPhase());
  }

  @Test
  void progressRecordedForOtherInputsRestartsFromTheFirstPhaseAndIsPersisted() throws Exception {
    var file = stateFile();
    var first = new ProvisionTracker<>(TestPhase.class, file, false);
    first.load(X);
    first.advance(TestPhase.STEP_TWO);
    first.recordFailure(TestPhase.STEP_THREE, "boom");
    var startedAt = first.currentState().startedAt();

    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker.load(Y);

    assertTrue(tracker.hasIncompleteRun());
    assertFalse(tracker.isCompleted(TestPhase.STEP_ONE));
    assertEquals(TestPhase.STEP_ONE, tracker.resumePoint().orElseThrow());
    assertEquals("STEP_TWO", tracker.superseded().orElseThrow().completedPhase());
    var persisted = YamlUtil.parseFile(file);
    assertNull(persisted.get("completed_phase"));
    assertEquals(startedAt, persisted.get("started_at"));
    assertNull(persisted.get("error"));
    assertEquals(Y, persisted.get("inputs"));
    assertEquals(
        new ProvisionState(null, startedAt, (String) persisted.get("updated_at"), null, Y),
        tracker.currentState());
  }

  @Test
  void aStateWrittenBeforeInputsWereRecordedRestartsFromTheFirstPhase() throws Exception {
    var file = stateFile();
    Files.writeString(
        file,
        "completed_phase: STEP_TWO\nstarted_at: 2026-10-09T00:00:00Z\n"
            + "updated_at: 2026-10-09T00:00:00Z\nerror: null\n");
    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);

    tracker.load(X);

    assertTrue(tracker.hasIncompleteRun());
    assertEquals(TestPhase.STEP_ONE, tracker.resumePoint().orElseThrow());
    var persisted = YamlUtil.parseFile(file);
    assertNull(persisted.get("completed_phase"));
    assertEquals("2026-10-09T00:00:00Z", persisted.get("started_at"));
    assertEquals(X, persisted.get("inputs"));
  }

  @Test
  void progressRecordedForTheSameInputsIsKept() throws Exception {
    var file = stateFile();
    var first = new ProvisionTracker<>(TestPhase.class, file, false);
    first.load(X);
    first.advance(TestPhase.STEP_TWO);

    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker.load(X);

    assertEquals(TestPhase.STEP_THREE, tracker.resumePoint().orElseThrow());
    assertTrue(tracker.superseded().isEmpty());
  }

  @Test
  void advanceAndRecordFailureKeepTheLoadedInputs() throws Exception {
    var file = stateFile();
    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker.load(X);

    tracker.advance(TestPhase.STEP_ONE);
    assertEquals(X, YamlUtil.parseFile(file).get("inputs"));

    tracker.recordFailure(TestPhase.STEP_TWO, "boom");
    assertEquals(X, YamlUtil.parseFile(file).get("inputs"));
    assertEquals(X, tracker.currentState().inputs());
  }

  @Test
  void aDryRunRestartsInMemoryAndWritesNothing() throws Exception {
    var file = stateFile();
    var real = new ProvisionTracker<>(TestPhase.class, file, false);
    real.load(X);
    real.advance(TestPhase.STEP_TWO);
    var before = Files.readString(file);

    var dry = new ProvisionTracker<>(TestPhase.class, file, true);
    dry.load(Y);

    assertFalse(dry.isCompleted(TestPhase.STEP_ONE));
    assertEquals(Y, dry.currentState().inputs());
    assertEquals(before, Files.readString(file));
  }

  @Test
  void aFinishedRunIsCompleteOnlyForItsOwnInputs() throws Exception {
    var file = stateFile();
    var tracker = new ProvisionTracker<>(TestPhase.class, file, false);
    tracker.load(X);
    assertFalse(tracker.isComplete());
    tracker.advance(TestPhase.COMPLETE);

    var same = new ProvisionTracker<>(TestPhase.class, file, false).load(X);
    assertTrue(same.isComplete());
    assertFalse(same.hasIncompleteRun());

    var changed = new ProvisionTracker<>(TestPhase.class, file, false).load(Y);
    assertFalse(changed.isComplete());
    assertTrue(changed.hasIncompleteRun());
    assertEquals("COMPLETE", changed.superseded().orElseThrow().completedPhase());
  }

  @Test
  void resetKeepsTheInputsAFreshRunRecords() throws Exception {
    var tracker = new ProvisionTracker<>(TestPhase.class, stateFile(), false);
    tracker.load(X);
    tracker.advance(TestPhase.STEP_ONE);

    tracker.reset();
    tracker.advance(TestPhase.STEP_ONE);

    assertEquals(X, YamlUtil.parseFile(stateFile()).get("inputs"));
  }
}
