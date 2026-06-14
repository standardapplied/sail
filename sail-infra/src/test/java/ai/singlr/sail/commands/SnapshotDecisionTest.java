/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SailYaml;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
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
    assertTrue(SnapshotDecision.shouldSnapshot(Boolean.TRUE, configWithAutoSnapshot(false), false));
    assertTrue(SnapshotDecision.shouldSnapshot(Boolean.TRUE, configWithAutoSnapshot(true), false));
    assertTrue(SnapshotDecision.shouldSnapshot(Boolean.TRUE, configWithAutoSnapshot(false), true));
  }

  @Test
  void overrideFalseAlwaysSkips() {
    assertFalse(
        SnapshotDecision.shouldSnapshot(Boolean.FALSE, configWithAutoSnapshot(true), false));
    assertFalse(
        SnapshotDecision.shouldSnapshot(Boolean.FALSE, configWithAutoSnapshot(false), true));
  }

  @Test
  void yamlAutoSnapshotDoesNotBypassPrompt() {
    ConsoleHelper.resetStdin();
    System.setIn(new java.io.ByteArrayInputStream(new byte[0]));

    assertFalse(SnapshotDecision.shouldSnapshot(null, configWithAutoSnapshot(true), false));
  }

  @Test
  void inheritWithJsonModeSkipsSilently() {
    assertFalse(SnapshotDecision.shouldSnapshot(null, configWithAutoSnapshot(false), true));
  }

  @Test
  void inheritWithEofOnStdinDefaultsToNo() {
    ConsoleHelper.resetStdin();
    System.setIn(new ByteArrayInputStream(new byte[0]));

    assertFalse(SnapshotDecision.shouldSnapshot(null, configWithAutoSnapshot(false), false));
  }

  @Test
  void inheritWithEmptyLineOnStdinDefaultsToNo() {
    ConsoleHelper.resetStdin();
    System.setIn(new ByteArrayInputStream("\n".getBytes()));

    assertFalse(SnapshotDecision.shouldSnapshot(null, configWithAutoSnapshot(false), false));
  }

  @Test
  void inheritWithYesOnStdinSnapshots() {
    ConsoleHelper.resetStdin();
    System.setIn(new ByteArrayInputStream("y\n".getBytes()));

    assertTrue(SnapshotDecision.shouldSnapshot(null, configWithAutoSnapshot(false), false));
  }

  @Test
  void inheritWithNullConfigPromptsAndDefaultsNo() {
    ConsoleHelper.resetStdin();
    System.setIn(new ByteArrayInputStream(new byte[0]));

    assertFalse(SnapshotDecision.shouldSnapshot(null, null, false));
  }

  private static SailYaml configWithAutoSnapshot(boolean autoSnapshot) {
    var agent =
        new SailYaml.Agent(
            "claude-code",
            false,
            null,
            autoSnapshot,
            List.of(),
            Map.of(),
            null,
            "specs",
            null,
            null,
            null,
            null);
    return new SailYaml(
        "test", null, null, null, null, null, null, List.of(), Map.of(), Map.of(), agent, null,
        null);
  }
}
