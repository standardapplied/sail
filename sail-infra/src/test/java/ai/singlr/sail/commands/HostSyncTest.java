/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.OperatorCommand;
import org.junit.jupiter.api.Test;

class HostSyncTest {

  private static final SyncConfig STANDALONE = SyncConfig.unset();
  private static final SyncConfig MAIN = new SyncConfig(SyncConfig.ROLE_MAIN, null, null);
  private static final SyncConfig NODE =
      new SyncConfig(SyncConfig.ROLE_NODE, "sail@maindevbox", null);

  @Test
  void standaloneHasNoPeerAndNoPropagationHint() {
    assertTrue(HostSync.isStandalone(STANDALONE));
    assertFalse(HostSync.hasPeers(STANDALONE));
    assertFalse(HostSync.isNode(STANDALONE));
    assertEquals("", HostSync.propagationHint(STANDALONE, OperatorCommand.PLAIN));
  }

  @Test
  void aNodeHasPeersAndIsToldToSync() {
    assertFalse(HostSync.isStandalone(NODE));
    assertTrue(HostSync.hasPeers(NODE));
    assertTrue(HostSync.isNode(NODE));
    assertEquals(
        "Run sail sync to propagate.", HostSync.propagationHint(NODE, OperatorCommand.PLAIN));
  }

  @Test
  void theMainHubHasPeersButPropagatesPassively() {
    assertFalse(HostSync.isStandalone(MAIN));
    assertTrue(HostSync.hasPeers(MAIN));
    assertFalse(HostSync.isNode(MAIN));
    assertTrue(HostSync.propagationHint(MAIN, OperatorCommand.PLAIN).contains("pull"));
  }
}
