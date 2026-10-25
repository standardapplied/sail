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
  void fitKeepsAShortTextAndEndsALongOneInAnEllipsisAtTheWidth() {
    assertEquals("short", Strings.fit("short", 80));
    assertEquals("short", Strings.fit("short", 5));
    assertEquals("x".repeat(39) + "…", Strings.fit("x".repeat(120), 40));
    assertEquals("a\uD83D\uDE00…", Strings.fit("a\uD83D\uDE00bc", 4));
    assertEquals("a…", Strings.fit("a\uD83D\uDE00bc", 3));
    assertEquals("", Strings.fit("abc", 0));
    assertEquals("", Strings.fit("abc", -3));
  }

  @Test
  void fitCountsTheColumnsAWideCharacterSpans() {
    assertEquals("界界…", Strings.fit("界".repeat(100), 6));
    assertEquals("界界…", Strings.fit("界".repeat(100), 5));
    assertEquals("ab界", Strings.fit("ab界", 4));
  }

  @Test
  void fitMakesEveryControlCharacterButAnEscapeASpace() {
    assertEquals("a b c d e", Strings.fit("a\tb\nc\rd\u0007e", 80));
    assertEquals("a b…", Strings.fit("a\tb\tc\td", 4));
    assertEquals("\033[1ma b\033[0m", Strings.fit("\033[1ma\tb\033[0m", 80));
  }

  @Test
  void columnsCountsWideCharactersTwiceAndMarksNotAtAll() {
    assertEquals(3, Strings.columns("abc"));
    assertEquals(4, Strings.columns("界가"));
    assertEquals(2, Strings.columns("\uD83D\uDE00"));
    assertEquals(4, Strings.columns("ｗ\uD840\uDC00"));
    assertEquals(1, Strings.columns("e\u0301"));
    assertEquals(1, Strings.columns("…"));
  }

  @Test
  void columnsFollowsUnicodeEastAsianWidthToTheEdgesOfItsRanges() {
    assertEquals(2, Strings.columns("\uD83D\uDE80"));
    assertEquals(2, Strings.columns("\u231A"));
    assertEquals(2, Strings.columns("\u2705"));
    assertEquals(2, Strings.columns("\uD83E\uDEA9"));
    assertEquals(2, Strings.columns("\uFF21"));
    assertEquals(2, Strings.columns("\u1100"));
    assertEquals(2, Strings.columns("\u115F"));
    assertEquals(2, Strings.columns("\uD8BF\uDFFD"));
    assertEquals(1, Strings.columns("\u10FF"));
    assertEquals(1, Strings.columns("\u2713"));
    assertEquals(1, Strings.columns("\u2588"));
    assertEquals(1, Strings.columns("\uFF71"));
    assertEquals(1, Strings.columns("\u303F"));
    assertEquals(1, Strings.columns("\uD8BF\uDFFE"));
  }

  @Test
  void columnsCountsAJoiningHangulJamoAndAnInvisibleFormatAsNoneAndAVisibleFormatAsOne() {
    assertEquals(2, Strings.columns("\u1100\u1161\u11A8"));
    assertEquals(0, Strings.columns("\uD7B0\uD7FF"));
    assertEquals(0, Strings.columns("\u200B\u2060"));
    assertEquals(1, Strings.columns("\u00AD"));
    assertEquals(1, Strings.columns("\u0600"));
    assertEquals(1, Strings.columns("\uD804\uDCBD"));
  }

  @Test
  void aStyleEscapeTakesNoColumnsAndACutStyledTextEndsWithAReset() {
    var styled = "\033[1;36mabcdef\033[0m";

    assertEquals(6, Strings.columns(styled));
    assertEquals(styled, Strings.fit(styled, 6));
    assertEquals("\033[1;36mabc…\033[0m", Strings.fit(styled, 4));
    assertEquals("\033[1;36m…\033[0m", Strings.fit(styled, 1));
    assertEquals("ab…", Strings.fit("abcdef", 3));
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
