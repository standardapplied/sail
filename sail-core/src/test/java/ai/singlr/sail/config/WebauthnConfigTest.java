/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebauthnConfigTest {

  @Test
  void disabledHasNoOriginsAndIsNotConfigured() {
    var config = WebauthnConfig.disabled();
    assertFalse(config.isConfigured());
    assertTrue(config.origins().isEmpty());
    assertNull(config.rpId());
  }

  @Test
  void configuredRequiresRpIdAndOrigin() {
    assertFalse(new WebauthnConfig(null, "Sail", List.of("https://a")).isConfigured());
    assertFalse(new WebauthnConfig("  ", "Sail", List.of("https://a")).isConfigured());
    assertFalse(new WebauthnConfig("a.dev", "Sail", List.of()).isConfigured());
    assertTrue(new WebauthnConfig("a.dev", "Sail", List.of("https://a.dev")).isConfigured());
  }

  @Test
  void resolvedRpNameFallsBackToRpId() {
    assertEquals("a.dev", new WebauthnConfig("a.dev", null, List.of()).resolvedRpName());
    assertEquals("a.dev", new WebauthnConfig("a.dev", " ", List.of()).resolvedRpName());
    assertEquals("Sail", new WebauthnConfig("a.dev", "Sail", List.of()).resolvedRpName());
  }

  @Test
  void nullOriginsBecomesEmptyImmutableList() {
    var config = new WebauthnConfig("a.dev", "Sail", null);
    assertTrue(config.origins().isEmpty());
    assertThrows(
        UnsupportedOperationException.class, () -> config.origins().add("https://injected"));
  }

  @Test
  void fromMapReadsScalarsAndOriginList() {
    var config =
        WebauthnConfig.fromMap(
            Map.of(
                "rp_id",
                "a.dev",
                "rp_name",
                "Sail",
                "origins",
                List.of("https://a.dev", "https://b.dev")));
    assertEquals("a.dev", config.rpId());
    assertEquals(List.of("https://a.dev", "https://b.dev"), config.origins());
  }

  @Test
  void fromMapTreatsScalarOriginAsSingleton() {
    var config = WebauthnConfig.fromMap(Map.of("rp_id", "a.dev", "origins", "https://a.dev"));
    assertEquals(List.of("https://a.dev"), config.origins());
  }

  @Test
  void fromMapNullAndMissingOriginsAreEmpty() {
    assertEquals(WebauthnConfig.disabled(), WebauthnConfig.fromMap(null));
    assertTrue(WebauthnConfig.fromMap(Map.of("rp_id", "a.dev")).origins().isEmpty());
  }

  @Test
  void toMapRoundTrips() {
    var config = new WebauthnConfig("a.dev", "Sail", List.of("https://a.dev"));
    assertEquals(config, WebauthnConfig.fromMap(config.toMap()));
  }

  @Test
  void sessionTtlDefaultsToThirtyDays() {
    assertEquals(Duration.ofDays(30), WebauthnConfig.disabled().sessionTtl());
    assertEquals(Duration.ofDays(30), new WebauthnConfig("a.dev", "Sail", List.of()).sessionTtl());
  }

  @Test
  void configuredSessionTtlOverridesDefault() {
    var config = new WebauthnConfig("a.dev", "Sail", List.of("https://a.dev"), 12);
    assertEquals(Duration.ofHours(12), config.sessionTtl());
  }

  @Test
  void outOfRangeSessionTtlIsRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> new WebauthnConfig("a.dev", "Sail", List.of(), 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WebauthnConfig("a.dev", "Sail", List.of(), 90 * 24 + 1));
    assertEquals(Duration.ofHours(1), new WebauthnConfig(null, null, null, 1).sessionTtl());
    assertEquals(Duration.ofDays(90), new WebauthnConfig(null, null, null, 90 * 24).sessionTtl());
  }

  @Test
  void fromMapReadsSessionTtlHours() {
    assertEquals(720, WebauthnConfig.fromMap(Map.of("session_ttl_hours", 720)).sessionTtlHours());
    assertEquals(720, WebauthnConfig.fromMap(Map.of("session_ttl_hours", "720")).sessionTtlHours());
    assertNull(WebauthnConfig.fromMap(Map.of("rp_id", "a.dev")).sessionTtlHours());
    assertThrows(
        NumberFormatException.class,
        () -> WebauthnConfig.fromMap(Map.of("session_ttl_hours", "soon")));
  }

  @Test
  void toMapRoundTripsSessionTtlAndOmitsItWhenUnset() {
    var config = new WebauthnConfig("a.dev", "Sail", List.of("https://a.dev"), 48);
    assertEquals(config, WebauthnConfig.fromMap(config.toMap()));
    assertFalse(
        new WebauthnConfig("a.dev", "Sail", List.of("https://a.dev"))
            .toMap()
            .containsKey("session_ttl_hours"));
  }
}
