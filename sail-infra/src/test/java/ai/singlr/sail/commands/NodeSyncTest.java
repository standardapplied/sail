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
import ai.singlr.sail.api.SyncStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.OperatorCommand;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    try (var main = NodeSync.scheduler(SyncConfig.unset(), false, Map.of(), OperatorCommand.PLAIN);
        var optedOut = NodeSync.scheduler(NODE, true, Map.of(), OperatorCommand.PLAIN)) {
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
    try (var scheduler =
        NodeSync.scheduler(
            NODE, false, Map.of(NodeSync.DEBOUNCE_ENV, "50"), OperatorCommand.PLAIN)) {
      assertNotNull(scheduler);
    }
  }

  @Test
  void sailApisSchedulerFollowsTheBoxIntoAndOutOfTheNodeRoleWithNoRestart() {
    var sync = new AtomicReference<SyncConfig>(SyncConfig.unset());
    var rounds = new AtomicInteger();
    try (var scheduler = NodeSync.serverScheduler(sync::get, Map.of(), rounds::incrementAndGet)) {
      scheduler.syncNow();
      assertEquals(0, rounds.get(), "a standalone box has no main to reconcile with");

      sync.set(new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", "eng"));
      scheduler.syncNow();
      assertEquals(1, rounds.get(), "joined after sail-api started, the box reconciles");

      sync.set(new SyncConfig(SyncConfig.ROLE_MAIN, null, "eng"));
      scheduler.syncNow();
      assertEquals(1, rounds.get(), "become main, it reconciles with nobody");
    }
  }

  @Test
  void onMainSailApisSchedulerTakesNoLockAndReadsNoHealthForAReadAWriteOrARound() {
    var sync = new AtomicReference<SyncConfig>(new SyncConfig(SyncConfig.ROLE_MAIN, null, "uday"));
    var rounds = new AtomicInteger();
    var healthReads = new AtomicInteger();
    try (var scheduler = NodeSync.serverScheduler(sync::get, Map.of(), rounds::incrementAndGet)) {
      scheduler.useHealth(
          () -> {
            healthReads.incrementAndGet();
            return SyncStatus.unattempted("main", null);
          });

      scheduler.freshenRead();
      scheduler.afterWrite();
      scheduler.syncNow();

      assertEquals(0, healthReads.get(), "main never asks its health on the read path");
      assertEquals(0, rounds.get());
    }
  }

  @Test
  void aConfigCaughtMidWriteSkipsTheRoundInsteadOfFailingTheCaller() {
    var rounds = new AtomicInteger();
    try (var scheduler =
        NodeSync.serverScheduler(
            () -> {
              throw new IllegalStateException("host.yaml is half written");
            },
            Map.of(),
            rounds::incrementAndGet)) {
      scheduler.freshenRead();
      scheduler.syncNow();

      assertEquals(0, rounds.get());
    }
  }
}
