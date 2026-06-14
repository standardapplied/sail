/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SailVersionTest {

  @Test
  void versionIsNotDev() {
    assertNotEquals("dev", SailVersion.version());
  }

  @Test
  void versionMatchesSemverFormat() {
    var version = SailVersion.version();
    assertTrue(version.matches("\\d+\\.\\d+\\.\\d+"), "Expected X.Y.Z format, got: " + version);
  }

  @Test
  void versionProviderReturnsSailPrefix() {
    var provider = new SailVersion();
    var lines = provider.getVersion();
    assertEquals(1, lines.length);
    assertTrue(lines[0].startsWith("sail "), "Expected 'sail X.Y.Z', got: " + lines[0]);
  }
}
