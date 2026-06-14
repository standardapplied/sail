/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ServicePresetsTest {

  @Test
  void allPresetsNonEmpty() {
    assertFalse(ServicePresets.all().isEmpty());
  }

  @Test
  void allPresetsHaveRequiredFields() {
    for (var preset : ServicePresets.all()) {
      assertNotNull(preset.key());
      assertNotNull(preset.displayName());
      assertNotNull(preset.service());
      assertNotNull(preset.service().image());
      assertNotNull(preset.service().ports());
      assertFalse(preset.service().ports().isEmpty());
    }
  }

  @Test
  void buildServicesMapReturnsSelectedOnly() {
    var selected = ServicePresets.buildServicesMap(List.of("postgres", "redis"));

    assertEquals(2, selected.size());
    assertTrue(selected.containsKey("postgres"));
    assertTrue(selected.containsKey("redis"));
    assertFalse(selected.containsKey("meilisearch"));
  }

  @Test
  void buildServicesMapPreservesCatalogOrder() {
    var selected = ServicePresets.buildServicesMap(List.of("redis", "postgres"));

    var keys = selected.keySet().stream().toList();
    assertEquals("postgres", keys.get(0));
    assertEquals("redis", keys.get(1));
  }

  @Test
  void buildServicesMapHandlesEmptySelection() {
    var selected = ServicePresets.buildServicesMap(List.of());
    assertTrue(selected.isEmpty());
  }

  @Test
  void buildServicesMapIgnoresUnknownKeys() {
    var selected = ServicePresets.buildServicesMap(List.of("nonexistent"));
    assertTrue(selected.isEmpty());
  }

  @Test
  void postgresPresetHasCorrectDefaults() {
    var pg =
        ServicePresets.all().stream()
            .filter(p -> "postgres".equals(p.key()))
            .findFirst()
            .orElseThrow();

    assertEquals("postgres:16", pg.service().image());
    assertEquals(List.of(5432), pg.service().ports());
    assertNotNull(pg.service().environment());
    assertTrue(pg.service().environment().containsKey("POSTGRES_DB"));
  }

  @Test
  void redpandaPresetHasMultiplePorts() {
    var rp =
        ServicePresets.all().stream()
            .filter(p -> "redpanda".equals(p.key()))
            .findFirst()
            .orElseThrow();

    assertEquals(3, rp.service().ports().size());
    assertTrue(rp.service().ports().contains(9092));
    assertNotNull(rp.service().command());
  }

  @Test
  void defaultVersionExtractsTag() {
    var pg = ServicePresets.findByKey("postgres");
    assertNotNull(pg);
    assertEquals("16", pg.defaultVersion());
  }

  @Test
  void defaultVersionReturnsLatestForUntagged() {
    var ms = ServicePresets.findByKey("meilisearch");
    assertNotNull(ms);
    assertEquals("latest", ms.defaultVersion());
  }

  @Test
  void withVersionReplacesTag() {
    var pg = ServicePresets.findByKey("postgres");
    assertNotNull(pg);
    var svc = pg.withVersion("17");

    assertEquals("postgres:17", svc.image());
    assertEquals(pg.service().ports(), svc.ports());
    assertEquals(pg.service().environment(), svc.environment());
  }

  @Test
  void withVersionReplacesLatestTag() {
    var ms = ServicePresets.findByKey("meilisearch");
    assertNotNull(ms);
    var svc = ms.withVersion("v1.12");

    assertEquals("getmeili/meilisearch:v1.12", svc.image());
  }

  @Test
  void findByKeyReturnsNullForUnknown() {
    assertNull(ServicePresets.findByKey("nonexistent"));
  }

  @Test
  void findByKeyReturnsMatchingPreset() {
    var redis = ServicePresets.findByKey("redis");
    assertNotNull(redis);
    assertEquals("redis", redis.key());
  }
}
