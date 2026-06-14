/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    var updated = HostSyncCommand.configure(BASE, true, null);

    assertTrue(updated.sync().isMain());
    assertEquals("10.0.0.1", updated.serverIp(), "other host config is preserved");
  }

  @Test
  void mainTargetMakesItANodePointedAtMainInOneStep() {
    var updated = HostSyncCommand.configure(BASE, false, "sail@maindevbox");

    assertEquals("node", updated.sync().role());
    assertEquals("sail@maindevbox", updated.sync().main());
  }

  @Test
  void aMalformedMainTargetIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> HostSyncCommand.configure(BASE, false, "not a target!"));
  }
}
