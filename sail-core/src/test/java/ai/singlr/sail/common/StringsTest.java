/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StringsTest {

  @Test
  void isEmptyTreatsNullAndEmptyAsEmptyButNotWhitespace() {
    assertTrue(Strings.isEmpty(null));
    assertTrue(Strings.isEmpty(""));
    assertFalse(Strings.isEmpty(" "));
    assertFalse(Strings.isEmpty("x"));
  }

  @Test
  void isBlankTreatsNullEmptyAndWhitespaceAsBlank() {
    assertTrue(Strings.isBlank(null));
    assertTrue(Strings.isBlank(""));
    assertTrue(Strings.isBlank("   "));
    assertFalse(Strings.isBlank("x"));
  }

  @Test
  void isNotBlankIsTheInverseOfIsBlank() {
    assertFalse(Strings.isNotBlank(null));
    assertFalse(Strings.isNotBlank("  "));
    assertTrue(Strings.isNotBlank("x"));
  }

  @Test
  void requireNonBlankReturnsTheValueWhenPresent() {
    assertEquals("x", Strings.requireNonBlank("x", "field"));
  }

  @Test
  void requireNonBlankThrowsNamingTheFieldWhenBlank() {
    for (var blank : new String[] {null, "", "   "}) {
      var ex =
          assertThrows(
              IllegalArgumentException.class, () -> Strings.requireNonBlank(blank, "project"));
      assertTrue(ex.getMessage().contains("project"), ex.getMessage());
    }
  }
}
