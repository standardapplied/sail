/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InitIntentTest {

  @Test
  void asMainResolvesToMain() {
    assertEquals(new InitIntent.Main(), InitIntent.resolve(true, null));
  }

  @Test
  void mainTargetResolvesToANode() {
    assertEquals(new InitIntent.Node("sail@host"), InitIntent.resolve(false, "  sail@host "));
  }

  @Test
  void bothFlagsAreRejected() {
    var error =
        assertThrows(IllegalArgumentException.class, () -> InitIntent.resolve(true, "sail@host"));
    assertTrue(error.getMessage().contains("not both"));
  }

  @Test
  void neitherFlagIsRejected() {
    var error = assertThrows(IllegalArgumentException.class, () -> InitIntent.resolve(false, "  "));
    assertTrue(error.getMessage().contains("--as-main"));
  }
}
