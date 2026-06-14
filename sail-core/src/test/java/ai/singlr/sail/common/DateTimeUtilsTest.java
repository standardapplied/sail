/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void nowOffsetIsAnchoredToUtc() {
    assertEquals(ZoneOffset.UTC, DateTimeUtils.nowOffset().getOffset());
  }

  @Test
  void newIdIsAVersion7Uuid() {
    assertEquals(7, DateTimeUtils.newId().version());
  }

  @Test
  void formatIsoEmitsAZuluTimestamp() {
    var instant = Instant.parse("2026-06-14T12:00:00Z");
    assertEquals("2026-06-14T12:00:00Z", DateTimeUtils.formatIso(instant));
  }

  @Test
  void parseInstantAndFormatIsoRoundTrip() {
    var instant = Instant.parse("2026-06-14T12:34:56Z");

    var formatted = DateTimeUtils.formatIso(instant);

    assertEquals(instant, DateTimeUtils.parseInstant(formatted));
  }

  @Test
  void parseInstantAcceptsANonZuluOffset() {
    assertEquals(
        Instant.parse("2026-06-14T12:00:00Z"),
        DateTimeUtils.parseInstant("2026-06-14T14:00:00+02:00"));
  }

  @Test
  void successiveNowReadingsDoNotGoBackwards() {
    var first = DateTimeUtils.now();
    var second = DateTimeUtils.now();
    assertFalse(second.isBefore(first));
    assertTrue(Duration.between(first, second).toMillis() >= 0);
  }
}
