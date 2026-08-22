/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * EngagementMode is the one spelling of a room agent's access level. Its {@code wire()} is the
 * canonical snake_case form every surface now emits; {@code of} still accepts the legacy hyphenated
 * {@code read-only} so a stored engagement written before the unification reads back correctly.
 */
class EngagementModeTest {

  @Test
  void wireIsTheCanonicalSnakeCaseForm() {
    assertEquals("full", EngagementMode.FULL.wire());
    assertEquals("read_only", EngagementMode.READ_ONLY.wire());
  }

  @Test
  void ofParsesTheCanonicalForms() {
    assertEquals(EngagementMode.FULL, EngagementMode.of("full"));
    assertEquals(EngagementMode.READ_ONLY, EngagementMode.of("read_only"));
  }

  @Test
  void ofStillAcceptsTheLegacyHyphenatedReadOnly() {
    assertEquals(EngagementMode.READ_ONLY, EngagementMode.of("read-only"));
  }

  @Test
  void ofRejectsBlankOrUnknown() {
    assertThrows(IllegalArgumentException.class, () -> EngagementMode.of(""));
    assertThrows(IllegalArgumentException.class, () -> EngagementMode.of(null));
    assertThrows(IllegalArgumentException.class, () -> EngagementMode.of("halfway"));
  }

  @Test
  void isFullDistinguishesTheTwo() {
    assertTrue(EngagementMode.FULL.isFull());
    assertFalse(EngagementMode.READ_ONLY.isFull());
  }
}
