/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SyncConfigTest {

  @Test
  void unsetIsNeitherMainNorPointedAnywhere() {
    var sync = SyncConfig.unset();
    assertFalse(sync.isMain());
    assertNull(sync.role());
    assertNull(sync.main());
  }

  @Test
  void blankFieldsNormalizeToNull() {
    var sync = new SyncConfig("  ", "");
    assertNull(sync.role());
    assertNull(sync.main());
  }

  @Test
  void mainRoleIsRecognized() {
    assertTrue(new SyncConfig(SyncConfig.ROLE_MAIN, null).isMain());
    assertFalse(new SyncConfig(SyncConfig.ROLE_NODE, "sail@host").isMain());
  }

  @Test
  void roundTripsThroughAMap() {
    var sync = new SyncConfig(SyncConfig.ROLE_NODE, "sail@maindevbox");
    var restored = SyncConfig.fromMap(sync.toMap());
    assertEquals(SyncConfig.ROLE_NODE, restored.role());
    assertEquals("sail@maindevbox", restored.main());
  }

  @Test
  void fromANullMapIsUnset() {
    assertFalse(SyncConfig.fromMap(null).isMain());
    assertNull(SyncConfig.fromMap(Map.of()).role());
  }
}
