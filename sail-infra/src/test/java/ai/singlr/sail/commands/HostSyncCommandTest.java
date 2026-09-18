/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.WebauthnConfig;
import org.junit.jupiter.api.Test;

class HostSyncCommandTest {

  private static final HostYaml BASE =
      new HostYaml(
          "dir",
          "devpool",
          null,
          "incusbr0",
          "singlr-base",
          "ubuntu/24.04",
          "6.21",
          "10.0.0.1",
          "2026-02-18T01:00:00Z",
          WebauthnConfig.disabled());

  @Test
  void asMainSetsTheMainRole() {
    var updated = HostSyncCommand.configure(BASE, true, null, "devbox");

    assertTrue(updated.sync().isMain());
    assertEquals("10.0.0.1", updated.serverIp(), "other host config is preserved");
  }

  @Test
  void mainTargetMakesItANodePointedAtMainInOneStep() {
    var updated = HostSyncCommand.configure(BASE, false, "sail@maindevbox", "devbox");

    assertEquals("node", updated.sync().role());
    assertEquals("sail@maindevbox", updated.sync().main());
  }

  @Test
  void takingARoleMintsABoxIdOnceAndKeepsAnExistingOne() {
    var asMain = HostSyncCommand.configure(BASE, true, null, "devbox");
    assertNotNull(asMain.sync().boxId());
    var asNode = HostSyncCommand.configure(asMain, false, "sail@maindevbox", "devbox");
    assertEquals(asMain.sync().boxId(), asNode.sync().boxId(), "a box keeps its identity");
    var another = HostSyncCommand.configure(BASE, false, "sail@maindevbox", "devbox");
    assertNotEquals(asMain.sync().boxId(), another.sync().boxId());
  }

  @Test
  void aBoxThatTookItsRoleBeforeIdsExistedAdoptsItsHostnameInsteadOfMintingOne() {
    var roledBeforeIds = HostConfigSetCommand.applyChange(BASE, "sync-role", "main");
    assertNull(roledBeforeIds.sync().boxId());
    var redeclared = HostSyncCommand.configure(roledBeforeIds, true, null, "devbox");
    assertEquals("devbox", redeclared.sync().boxId(), "peers checkpoint against the hostname");
    var asNode = HostSyncCommand.configure(redeclared, false, "sail@maindevbox", "elsewhere");
    assertEquals("devbox", asNode.sync().boxId(), "an id, once held, is kept");
  }

  @Test
  void aMalformedMainTargetIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> HostSyncCommand.configure(BASE, false, "not a target!", "devbox"));
  }
}
