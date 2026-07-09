/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.config.SyncConfig;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NodeSyncTest {

  private static final SyncConfig NODE =
      new SyncConfig(SyncConfig.ROLE_NODE, "sail@maindevbox", "mady");

  @Test
  void aNodePropagatesAutomaticallyByDefault() {
    assertTrue(NodeSync.shouldSync(NODE, false));
  }

  @Test
  void noSyncOptsOutEvenOnANode() {
    assertFalse(NodeSync.shouldSync(NODE, true));
  }

  @Test
  void mainHasNoPeerToPushToSoItNeverSyncs() {
    assertFalse(NodeSync.shouldSync(new SyncConfig(SyncConfig.ROLE_MAIN, null, "uday"), false));
  }

  @Test
  void aStandaloneBoxNeverSyncs() {
    assertFalse(NodeSync.shouldSync(SyncConfig.unset(), false));
  }

  @Test
  void mainAndOptedOutBoxesGetTheNoOpScheduler() {
    try (var main = NodeSync.scheduler(SyncConfig.unset(), false, Map.of());
        var optedOut = NodeSync.scheduler(NODE, true, Map.of())) {
      main.syncNow();
      optedOut.afterWrite();
    }
  }

  @Test
  void debounceAndTtlDefaultsHoldWhenTheEnvironmentIsSilent() {
    assertEquals(
        SyncScheduler.DEFAULT_DEBOUNCE,
        NodeSync.millis(Map.of(), NodeSync.DEBOUNCE_ENV, SyncScheduler.DEFAULT_DEBOUNCE));
    assertEquals(
        SyncScheduler.DEFAULT_FRESHEN_TTL,
        NodeSync.millis(
            Map.of(NodeSync.FRESHEN_TTL_ENV, "  "),
            NodeSync.FRESHEN_TTL_ENV,
            SyncScheduler.DEFAULT_FRESHEN_TTL));
  }

  @Test
  void environmentOverridesTheDebounceAndTtl() {
    var env = Map.of(NodeSync.DEBOUNCE_ENV, "50", NodeSync.FRESHEN_TTL_ENV, " 250 ");

    assertEquals(
        Duration.ofMillis(50),
        NodeSync.millis(env, NodeSync.DEBOUNCE_ENV, SyncScheduler.DEFAULT_DEBOUNCE));
    assertEquals(
        Duration.ofMillis(250),
        NodeSync.millis(env, NodeSync.FRESHEN_TTL_ENV, SyncScheduler.DEFAULT_FRESHEN_TTL));
  }

  @Test
  void garbageAndNonPositiveOverridesFallBackWithAWarning() {
    assertEquals(
        SyncScheduler.DEFAULT_DEBOUNCE,
        NodeSync.millis(
            Map.of(NodeSync.DEBOUNCE_ENV, "soon"),
            NodeSync.DEBOUNCE_ENV,
            SyncScheduler.DEFAULT_DEBOUNCE));
    assertEquals(
        SyncScheduler.DEFAULT_DEBOUNCE,
        NodeSync.millis(
            Map.of(NodeSync.DEBOUNCE_ENV, "0"),
            NodeSync.DEBOUNCE_ENV,
            SyncScheduler.DEFAULT_DEBOUNCE));
  }

  @Test
  void aNodeGetsALiveSchedulerWiredToTheConfiguredWindows() {
    try (var scheduler = NodeSync.scheduler(NODE, false, Map.of(NodeSync.DEBOUNCE_ENV, "50"))) {
      assertNotNull(scheduler);
    }
  }
}
