/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.*;

import ai.singlr.sail.store.TokenStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ServerTokenCommandTest {

  @Test
  void defaultsToTheStandardLifetime() {
    assertEquals(TokenStore.DEFAULT_TTL, ServerTokenCommand.Create.resolveTtl(false, null));
  }

  @Test
  void noExpiryYieldsNoLifetime() {
    assertNull(ServerTokenCommand.Create.resolveTtl(true, null));
  }

  @Test
  void anExplicitDayCountWins() {
    assertEquals(Duration.ofDays(30), ServerTokenCommand.Create.resolveTtl(false, 30));
  }

  @Test
  void noExpiryAndTtlTogetherIsRejected() {
    var thrown =
        assertThrows(
            IllegalArgumentException.class, () -> ServerTokenCommand.Create.resolveTtl(true, 30));
    assertTrue(thrown.getMessage().contains("not both"));
  }

  @Test
  void aNonPositiveLifetimeIsRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> ServerTokenCommand.Create.resolveTtl(false, 0));
    assertThrows(
        IllegalArgumentException.class, () -> ServerTokenCommand.Create.resolveTtl(false, -5));
  }
}
