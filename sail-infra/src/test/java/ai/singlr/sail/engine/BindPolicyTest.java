/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BindPolicyTest {

  @Test
  void loopbackIsAllowedWithoutOptIn() {
    assertDoesNotThrow(() -> BindPolicy.requireBindable("127.0.0.1", false));
    assertDoesNotThrow(() -> BindPolicy.requireBindable("localhost", false));
    assertDoesNotThrow(() -> BindPolicy.requireBindable("::1", false));
  }

  @Test
  void nonLoopbackWithoutOptInIsRefused() {
    var error =
        assertThrows(
            IllegalArgumentException.class, () -> BindPolicy.requireBindable("0.0.0.0", false));
    assertTrue(error.getMessage().contains("--allow-remote"));
  }

  @Test
  void nonLoopbackWithOptInIsAllowed() {
    assertDoesNotThrow(() -> BindPolicy.requireBindable("0.0.0.0", true));
    assertDoesNotThrow(() -> BindPolicy.requireBindable("10.0.0.5", true));
  }

  @Test
  void optInOnLoopbackStaysAllowed() {
    assertDoesNotThrow(() -> BindPolicy.requireBindable("127.0.0.1", true));
  }
}
