/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * RunStatus is the canonical run-lifecycle vocabulary — the wire strings the {@code runs.status}
 * column stored and matched as bare text, with {@code isTerminal} as the single definition of
 * "finished" that the run store, the sync narrator, and the missed-stop reconciler once each kept
 * their own copy of.
 */
class RunStatusTest {

  @Test
  void wireMatchesTheStoredStatusStrings() {
    assertEquals("running", RunStatus.RUNNING.wire());
    assertEquals("stopping", RunStatus.STOPPING.wire());
    assertEquals("stopped", RunStatus.STOPPED.wire());
    assertEquals("completed", RunStatus.COMPLETED.wire());
    assertEquals("failed", RunStatus.FAILED.wire());
  }

  @Test
  void ofRoundTripsEveryWireForm() {
    for (var status : RunStatus.values()) {
      assertEquals(Optional.of(status), RunStatus.of(status.wire()));
    }
  }

  @Test
  void ofIsEmptyForUnknownOrNull() {
    assertEquals(Optional.empty(), RunStatus.of("gone"));
    assertEquals(Optional.empty(), RunStatus.of(null));
  }

  @Test
  void terminalIsStoppedCompletedOrFailed() {
    assertTrue(RunStatus.STOPPED.isTerminal());
    assertTrue(RunStatus.COMPLETED.isTerminal());
    assertTrue(RunStatus.FAILED.isTerminal());
    assertFalse(RunStatus.RUNNING.isTerminal());
    assertFalse(RunStatus.STOPPING.isTerminal());
  }

  @Test
  void terminalByWireIsTheSingleSourceTheStringSetsReplace() {
    assertTrue(RunStatus.isTerminal("completed"));
    assertTrue(RunStatus.isTerminal("stopped"));
    assertTrue(RunStatus.isTerminal("failed"));
    assertFalse(RunStatus.isTerminal("running"));
    assertFalse(RunStatus.isTerminal(null));
    assertFalse(RunStatus.isTerminal("nonsense"));
  }
}
