/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SnapshotDecisionTest {

  private final InputStream originalStdin = System.in;

  @AfterEach
  void restoreStdin() {
    System.setIn(originalStdin);
    ConsoleHelper.resetStdin();
  }

  @Test
  void overrideTrueAlwaysSnapshots() {
    assertTrue(SnapshotDecision.shouldSnapshot(Boolean.TRUE, false));
    assertTrue(SnapshotDecision.shouldSnapshot(Boolean.TRUE, true));
  }

  @Test
  void overrideFalseAlwaysSkips() {
    assertFalse(SnapshotDecision.shouldSnapshot(Boolean.FALSE, false));
    assertFalse(SnapshotDecision.shouldSnapshot(Boolean.FALSE, true));
  }

  @Test
  void inheritWithJsonModeSkipsSilently() {
    assertFalse(SnapshotDecision.shouldSnapshot(null, true));
  }

  @Test
  void inheritWithEofOnStdinDefaultsToNo() {
    ConsoleHelper.resetStdin();
    System.setIn(new ByteArrayInputStream(new byte[0]));

    assertFalse(SnapshotDecision.shouldSnapshot(null, false));
  }

  @Test
  void inheritWithEmptyLineOnStdinDefaultsToNo() {
    ConsoleHelper.resetStdin();
    System.setIn(new ByteArrayInputStream("\n".getBytes()));

    assertFalse(SnapshotDecision.shouldSnapshot(null, false));
  }

  @Test
  void inheritWithYesOnStdinSnapshots() {
    ConsoleHelper.resetStdin();
    System.setIn(new ByteArrayInputStream("y\n".getBytes()));

    assertTrue(SnapshotDecision.shouldSnapshot(null, false));
  }

  @Test
  void inheritWithNullConfigPromptsAndDefaultsNo() {
    ConsoleHelper.resetStdin();
    System.setIn(new ByteArrayInputStream(new byte[0]));

    assertFalse(SnapshotDecision.shouldSnapshot(null, false));
  }
}
