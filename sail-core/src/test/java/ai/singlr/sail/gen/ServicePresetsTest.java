/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    var pg = ServicePresets.all().getFirst();
    assertEquals("16", pg.defaultVersion());
  }

  @Test
  void defaultVersionReturnsLatestForUntagged() {
    var ms = ServicePresets.all().get(2);
    assertEquals("latest", ms.defaultVersion());
  }

  @Test
  void withVersionReplacesTag() {
    var pg = ServicePresets.all().getFirst();
    var svc = pg.withVersion("17");

    assertEquals("postgres:17", svc.image());
    assertEquals(pg.service().ports(), svc.ports());
    assertEquals(pg.service().environment(), svc.environment());
  }

  @Test
  void withVersionReplacesLatestTag() {
    var ms = ServicePresets.all().get(2);
    var svc = ms.withVersion("v1.12");

    assertEquals("getmeili/meilisearch:v1.12", svc.image());
  }
}
