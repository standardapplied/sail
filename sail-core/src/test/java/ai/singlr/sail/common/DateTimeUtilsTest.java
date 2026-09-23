/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
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
}
