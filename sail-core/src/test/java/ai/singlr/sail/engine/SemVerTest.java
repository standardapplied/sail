/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SemVerTest {

  @Test
  void parseThreePartVersion() {
    var v = SemVer.parse("1.7.3");
    assertEquals(1, v.major());
    assertEquals(7, v.minor());
    assertEquals(3, v.patch());
  }

  @Test
  void parseStripsVPrefix() {
    var v = SemVer.parse("v2.0.1");
    assertEquals(2, v.major());
    assertEquals(0, v.minor());
    assertEquals(1, v.patch());
  }

  @Test
  void parseTwoPartDefaultsPatchToZero() {
    var v = SemVer.parse("1.7");
    assertEquals(1, v.major());
    assertEquals(7, v.minor());
    assertEquals(0, v.patch());
  }

  @Test
  void parseRejectsSinglePart() {
    assertThrows(IllegalArgumentException.class, () -> SemVer.parse("1"));
  }

  @Test
  void parseRejectsNonNumeric() {
    assertThrows(NumberFormatException.class, () -> SemVer.parse("1.abc.0"));
  }

  @Test
  void compareEqual() {
    assertEquals(0, SemVer.parse("1.6.0").compareTo(SemVer.parse("1.6.0")));
  }

  @Test
  void compareMajor() {
    assertTrue(SemVer.parse("1.9.9").compareTo(SemVer.parse("2.0.0")) < 0);
    assertTrue(SemVer.parse("2.0.0").compareTo(SemVer.parse("1.9.9")) > 0);
  }

  @Test
  void compareMinor() {
    assertTrue(SemVer.parse("1.6.0").compareTo(SemVer.parse("1.7.0")) < 0);
    assertTrue(SemVer.parse("1.7.0").compareTo(SemVer.parse("1.6.0")) > 0);
  }

  @Test
  void comparePatch() {
    assertTrue(SemVer.parse("1.6.0").compareTo(SemVer.parse("1.6.1")) < 0);
    assertTrue(SemVer.parse("1.6.1").compareTo(SemVer.parse("1.6.0")) > 0);
  }

  @Test
  void toStringFormat() {
    assertEquals("1.7.0", SemVer.parse("v1.7").toString());
    assertEquals("2.3.4", SemVer.parse("2.3.4").toString());
  }
}
