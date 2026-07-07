/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OriginPreflightTest {

  @Test
  void passesWhenTheCanonicalOriginIsAllowlisted() {
    assertDoesNotThrow(
        () ->
            OriginPreflight.check(
                "devbox", Map.of("origins", List.of("https://sail.acme.dev", SshTunnel.ORIGIN))));
  }

  @Test
  void passesWhenTheServerDoesNotAdvertiseOrigins() {
    assertDoesNotThrow(() -> OriginPreflight.check("devbox", Map.of("challenge_id", "wac_1")));
  }

  @Test
  void failsWithTheExactHostYamlBlockWhenTheCanonicalOriginIsMissing() {
    var error =
        assertThrows(
            IllegalStateException.class,
            () ->
                OriginPreflight.check(
                    "devbox", Map.of("origins", List.of("https://sail.acme.dev"))));
    assertTrue(error.getMessage().contains("devbox"));
    assertTrue(error.getMessage().contains("rp_id: localhost"));
    assertTrue(error.getMessage().contains("- http://localhost:7070"));
    assertTrue(error.getMessage().contains("sudo sail host service restart"));
  }

  @Test
  void failsWithTheSameGuidanceWhenPasskeysAreNotConfiguredAtAll() {
    var error =
        assertThrows(
            IllegalStateException.class,
            () ->
                OriginPreflight.check(
                    "devbox",
                    Map.of(
                        "error",
                        Map.of("code", "passkey_not_configured", "message", "not configured"))));
    assertTrue(error.getMessage().contains("rp_id: localhost"));
    assertTrue(error.getMessage().contains("- http://localhost:7070"));
  }

  @Test
  void surfacesOtherControlPlaneErrorsVerbatim() {
    var error =
        assertThrows(
            IllegalStateException.class,
            () ->
                OriginPreflight.check(
                    "devbox", Map.of("error", Map.of("code", "internal", "message", "boom"))));
    assertTrue(error.getMessage().contains("devbox"));
    assertTrue(error.getMessage().contains("boom"));
  }
}
