/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SyncConfig;
import org.junit.jupiter.api.Test;

class SpecSyncTest {

  private static final SyncConfig NODE =
      new SyncConfig(SyncConfig.ROLE_NODE, "sail@maindevbox", "mady");

  @Test
  void aNodeSyncsBeforeSpecCommandsByDefault() {
    assertTrue(SpecSync.shouldSync(NODE, false));
  }

  @Test
  void noSyncOptsOutEvenOnANode() {
    assertFalse(SpecSync.shouldSync(NODE, true));
  }

  @Test
  void mainHasNoPeerToPullFromSoItNeverSyncs() {
    assertFalse(SpecSync.shouldSync(new SyncConfig(SyncConfig.ROLE_MAIN, null, "uday"), false));
  }

  @Test
  void aStandaloneBoxNeverSyncs() {
    assertFalse(SpecSync.shouldSync(SyncConfig.unset(), false));
  }
}
