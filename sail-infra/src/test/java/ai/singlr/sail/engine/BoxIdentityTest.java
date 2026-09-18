/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SyncConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoxIdentityTest {

  @Test
  void aPersistedIdWinsAndAnUnpersistedRoleFallsBackToTheHostname(@TempDir Path dir)
      throws Exception {
    var persisted = dir.resolve("persisted.yaml");
    Files.writeString(persisted, "sync:\n  role: main\n  box_id: box-7\n");
    assertEquals("box-7", BoxIdentity.config(persisted, "devbox").boxId());

    var unpersisted = dir.resolve("unpersisted.yaml");
    Files.writeString(unpersisted, "sync:\n  role: node\n  main: sail@maindevbox\n");
    var config = BoxIdentity.config(unpersisted, "devbox");
    assertEquals("devbox", config.boxId());
    assertEquals("sail@maindevbox", config.main());
  }

  @Test
  void aMissingHostConfigIsAnUndeclaredBoxKnownByItsHostname(@TempDir Path dir) {
    var config = BoxIdentity.config(dir.resolve("absent.yaml"), "devbox");
    assertNull(config.role());
    assertEquals("devbox", config.boxId());
    assertEquals(SyncConfig.unset().withBoxId("devbox"), config);
  }

  @Test
  void aHostConfigThatExistsButCannotBeReadFailsLoudInsteadOfFlippingIdentity(@TempDir Path dir)
      throws Exception {
    var unreadable = Files.createDirectory(dir.resolve("host.yaml"));
    var failure =
        assertThrows(IllegalStateException.class, () -> BoxIdentity.config(unreadable, "devbox"));
    assertTrue(failure.getMessage().contains(unreadable.toString()), failure.getMessage());
    assertTrue(failure.getMessage().contains("one id"), failure.getMessage());
  }
}
