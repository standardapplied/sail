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
  void unsetIsNeitherMainNorPointedAnywhereNorBoundToAnFde() {
    var sync = SyncConfig.unset();
    assertFalse(sync.isMain());
    assertNull(sync.role());
    assertNull(sync.main());
    assertNull(sync.handle());
  }

  @Test
  void blankFieldsNormalizeToNull() {
    var sync = new SyncConfig("  ", "", "   ");
    assertNull(sync.role());
    assertNull(sync.main());
    assertNull(sync.handle());
  }

  @Test
  void mainRoleIsRecognized() {
    assertTrue(new SyncConfig(SyncConfig.ROLE_MAIN, null, "uday").isMain());
    assertFalse(new SyncConfig(SyncConfig.ROLE_NODE, "sail@host", "mady").isMain());
  }

  @Test
  void roundTripsThroughAMapIncludingTheHandle() {
    var sync = new SyncConfig(SyncConfig.ROLE_NODE, "sail@maindevbox", "mady");
    var restored = SyncConfig.fromMap(sync.toMap());
    assertEquals(SyncConfig.ROLE_NODE, restored.role());
    assertEquals("sail@maindevbox", restored.main());
    assertEquals("mady", restored.handle());
  }

  @Test
  void aLegacyMapWithoutAHandleLoadsWithANullHandle() {
    var restored = SyncConfig.fromMap(Map.of("role", SyncConfig.ROLE_NODE, "main", "sail@m"));
    assertEquals(SyncConfig.ROLE_NODE, restored.role());
    assertNull(restored.handle());
  }

  @Test
  void theBoxIdRoundTripsAndIsNullUntilMinted() {
    var minted = new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", "mady", "box-7");
    assertEquals("box-7", SyncConfig.fromMap(minted.toMap()).boxId());
    assertEquals("box-7", minted.toMap().get("box_id"));
    var legacy = new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", "mady");
    assertNull(legacy.boxId());
    assertNull(new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", "mady", " ").boxId());
    assertEquals("box-9", legacy.withBoxId("box-9").boxId());
    assertEquals("mady", legacy.withBoxId("box-9").handle());
    assertNull(SyncConfig.fromMap(Map.of("role", SyncConfig.ROLE_NODE)).boxId());
  }

  @Test
  void fromANullMapIsUnset() {
    assertFalse(SyncConfig.fromMap(null).isMain());
    assertNull(SyncConfig.fromMap(Map.of()).role());
  }
}
