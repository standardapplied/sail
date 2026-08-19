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

import org.junit.jupiter.api.Test;

class EngagementTest {

  @Test
  void fullIsTheDefaultMode() {
    var engagement = Engagement.of("claude-code", null, null, "2026-08-18T00:00:00Z");
    assertEquals(Engagement.MODE_FULL, engagement.mode());
    assertTrue(engagement.full());
    assertNull(engagement.model());
  }

  @Test
  void readOnlyIsTheExplicitNarrowChoice() {
    var engagement =
        Engagement.of("claude-code", Engagement.MODE_READ_ONLY, "opus", "2026-08-18T00:00:00Z");
    assertFalse(engagement.full());
    assertEquals("opus", engagement.model());
  }

  @Test
  void validationFailsLoud() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Engagement.of("", null, null, "2026-08-18T00:00:00Z"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Engagement.of("claude-code", "yolo", null, "2026-08-18T00:00:00Z"));
    assertThrows(
        IllegalArgumentException.class, () -> Engagement.of("claude-code", null, null, ""));
  }

  @Test
  void jsonRoundTripsIncludingAbsentModel() {
    var full = Engagement.of("codex", "full", null, "2026-08-18T01:02:03Z");
    assertEquals(full, Engagement.fromJson(full.toJson()));
    var readOnly = Engagement.of("claude-code", "read-only", "opus", "2026-08-18T01:02:03Z");
    assertEquals(readOnly, Engagement.fromJson(readOnly.toJson()));
  }

  @Test
  void corruptStoredValuesReadAsNotEngagedNeverThrow() {
    assertNull(Engagement.fromJson(null));
    assertNull(Engagement.fromJson(""));
    assertNull(Engagement.fromJson("not json at all {{{"));
    assertNull(Engagement.fromJson("{\"mode\":\"full\"}"));
    assertNull(
        Engagement.fromJson("{\"agent\":\"claude-code\",\"mode\":\"bogus\",\"engaged_at\":\"t\"}"));
  }
}
