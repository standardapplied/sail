/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * NodeIdentity reads the shared {@code host.yaml}; the test asserts the total, environment-tolerant
 * contract: a config is always returned (unset when no file is present) and {@code handle()} is
 * exactly that config's handle.
 */
class NodeIdentityTest {

  @Test
  void configIsAlwaysPresentAndHandleMirrorsIt() {
    var config = NodeIdentity.config();

    assertNotNull(config);
    assertEquals(config.handle(), NodeIdentity.handle());
  }
}
