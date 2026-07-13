/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SyncConfig;
import org.junit.jupiter.api.Test;

/** Exactly one Slack notifier per fleet: main (or a lone box) narrates, a node never does. */
class ServerStartCommandTest {

  @Test
  void mainNarratesSlack() {
    assertTrue(ServerStartCommand.narratesSlack(new SyncConfig("main", null, "uday")));
  }

  @Test
  void aStandaloneBoxNarratesItsOwnWork() {
    assertTrue(ServerStartCommand.narratesSlack(SyncConfig.unset()));
  }

  @Test
  void aNodePointedAtMainNeverPosts() {
    assertFalse(ServerStartCommand.narratesSlack(new SyncConfig("node", "sail@main", "uday")));
  }
}
