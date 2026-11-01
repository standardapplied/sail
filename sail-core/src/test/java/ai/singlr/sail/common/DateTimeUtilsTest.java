/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DateTimeUtilsTest {

  @Test
  void nowIsAUtcInstantCloseToWallClock() {
    var before = Instant.now();
    var now = DateTimeUtils.now();
    assertFalse(now.isBefore(before.minusSeconds(1)));
    assertFalse(now.isAfter(Instant.now().plusSeconds(1)));
  }

  @Test
  void newIdIsAVersion7Uuid() {
    assertEquals(7, DateTimeUtils.newId().version());
  }

  @Test
  void successiveNowReadingsDoNotGoBackwards() {
    var first = DateTimeUtils.now();
    var second = DateTimeUtils.now();
    assertFalse(second.isBefore(first));
    assertTrue(Duration.between(first, second).toMillis() >= 0);
  }

  @Test
  void parseAgeReadsDaysHoursAndMinutes() {
    assertEquals(Duration.ofDays(7), DateTimeUtils.parseAge("7d"));
    assertEquals(Duration.ofDays(365), DateTimeUtils.parseAge("365d"));
    assertEquals(Duration.ofHours(24), DateTimeUtils.parseAge("24h"));
    assertEquals(Duration.ofMinutes(30), DateTimeUtils.parseAge("30m"));
    assertEquals(Duration.ofDays(3), DateTimeUtils.parseAge("  3d  "));
  }

  @Test
  void parseAgeRefusesAnythingElseNamingTheForms() {
    for (var bad : new String[] {"7x", "", "abc", "d7", null}) {
      var refused = assertThrows(IllegalArgumentException.class, () -> DateTimeUtils.parseAge(bad));
      assertTrue(refused.getMessage().contains("Invalid age format"), refused.getMessage());
      assertTrue(refused.getMessage().contains("Examples"), refused.getMessage());
    }
  }

  @Test
  void anInstantLeavesAsIsoUtcAtMillisecondWidthAlways() {
    assertEquals(
        "2026-10-20T19:09:39.000Z", DateTimeUtils.format(Instant.parse("2026-10-20T19:09:39Z")));
    assertEquals(
        "2026-10-20T19:09:39.650Z", DateTimeUtils.format(Instant.parse("2026-10-20T19:09:39.65Z")));
    assertEquals(
        "2026-10-20T19:09:39.656Z",
        DateTimeUtils.format(Instant.parse("2026-10-20T19:09:39.656789Z")));
    assertEquals(null, DateTimeUtils.format(null));
  }

  @Test
  void anInstantIsStoredAsItsMillisecondAndReadBackWhole() {
    var at = Instant.parse("2026-10-20T19:09:39.656Z");

    assertEquals(1792523379656L, DateTimeUtils.toMillis(at));
    assertEquals(at, DateTimeUtils.ofMillis(1792523379656L));
    assertEquals(at, DateTimeUtils.parse("2026-10-20T19:09:39.656999Z"));
  }

  @Test
  void nowIsAtTheMillisecondEveryStoredInstantKeeps() {
    var clock = DateTimeUtils.clock;
    try {
      DateTimeUtils.clock =
          Clock.fixed(Instant.parse("2026-10-20T19:09:51.123456789Z"), ZoneOffset.UTC);
      assertEquals(Instant.parse("2026-10-20T19:09:51.123Z"), DateTimeUtils.now());
    } finally {
      DateTimeUtils.clock = clock;
    }
  }

  @Test
  void aLocalRevisionIsNowOrAMillisecondPastTheLastWhenTheClockHasNotPassedIt() {
    var at = Instant.parse("2026-10-20T19:09:51.123Z");
    var clock = DateTimeUtils.clock;
    try {
      DateTimeUtils.clock = Clock.fixed(at, ZoneOffset.UTC);
      assertEquals(at, DateTimeUtils.nowAfter(null));
      assertEquals(at, DateTimeUtils.nowAfter(at.minusMillis(1)));
      assertEquals(at.plusMillis(1), DateTimeUtils.nowAfter(at));
      assertEquals(at.plusSeconds(60).plusMillis(1), DateTimeUtils.nowAfter(at.plusSeconds(60)));
    } finally {
      DateTimeUtils.clock = clock;
    }
  }
}
