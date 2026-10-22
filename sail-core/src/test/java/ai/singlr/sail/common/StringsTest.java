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

  @Test
  void clipKeepsAShortTextAndCutsALongOneBetweenCodePoints() {
    assertEquals("abc", Strings.clip("abc", 3));
    assertEquals("ab", Strings.clip("abc", 2));
    assertEquals("a\uD83D\uDE00", Strings.clip("a\uD83D\uDE00b", 2));
    assertEquals("", Strings.clip("abc", 0));
  }

  @Test
  void shellWordLeavesASafeWordBareAndQuotesAnyOtherToPasteBack() {
    assertEquals("--title", Strings.shellWord("--title"));
    assertEquals("/var/lib/sail=a:b.c", Strings.shellWord("/var/lib/sail=a:b.c"));
    assertEquals("sail@main,a+b", Strings.shellWord("sail@main,a+b"));
    assertEquals("'b c'", Strings.shellWord("b c"));
    assertEquals("'it'\\''s'", Strings.shellWord("it's"));
    assertEquals("''", Strings.shellWord(""));
  }
}
