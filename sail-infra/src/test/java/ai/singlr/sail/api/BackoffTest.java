/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BackoffTest {
  static final class TestClock extends Clock {
    private Instant now = Instant.parse("2026-09-17T00:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }
  }

  @Test
  void invalidFailureCountsAndJitterFailFast() {
    var clock = new TestClock();
    var backoff = new Backoff(clock, () -> 0.5);
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> backoff.observe(clock.instant(), -1));
    for (var value : new double[] {Double.NaN, -0.1, 1.1}) {
      var invalid = new Backoff(clock, () -> value);
      org.junit.jupiter.api.Assertions.assertThrows(
          IllegalArgumentException.class, () -> invalid.observe(clock.instant(), 1));
    }
    assertEquals(java.time.ZoneOffset.UTC, clock.getZone());
    assertEquals(clock, clock.withZone(java.time.ZoneOffset.UTC));
  }

  @Test
  void failuresClimbTheLadderAndOpenTheCircuitUntilAnExplicitOrHalfOpenProbe() {
    var clock = new TestClock();
    var backoff = new Backoff(clock, () -> 0.5);
    for (var failures = 1; failures <= 4; failures++) {
      backoff.observe(clock.instant(), failures);
      var delay = Duration.ofSeconds(15L << (failures - 1));
      assertEquals(delay, backoff.remaining());
      assertFalse(backoff.ready(false));
      assertFalse(backoff.ready(true));
      clock.advance(delay);
      assertTrue(backoff.ready(false));
    }
    backoff.observe(clock.instant(), 5);
    assertTrue(backoff.open());
    assertFalse(backoff.ready(false));
    assertTrue(backoff.ready(true));
    assertFalse(backoff.probeDue());
    clock.advance(Duration.ofMinutes(5));
    assertFalse(backoff.ready(false));
    assertTrue(backoff.probeDue());
    backoff.observe(clock.instant(), 6);
    assertFalse(backoff.probeDue());
    backoff.observe(clock.instant(), 0);
    assertFalse(backoff.open());
    assertTrue(backoff.ready(false));
    assertEquals(Duration.ZERO, backoff.remaining());
  }

  @Test
  void jitterStaysWithinTwentyPercentAndObservingTheSameAttemptDoesNotMoveItsDeadline() {
    for (var jitter : new double[] {0, 0.5, 1}) {
      var clock = new TestClock();
      var backoff = new Backoff(clock, () -> jitter);
      var attempted = clock.instant();
      backoff.observe(attempted, 4);
      var delay = backoff.remaining();
      assertTrue(delay.compareTo(Duration.ofSeconds(96)) >= 0);
      assertTrue(delay.compareTo(Duration.ofSeconds(144)) <= 0);
      clock.advance(Duration.ofSeconds(1));
      backoff.observe(attempted, 4);
      assertEquals(delay.minusSeconds(1), backoff.remaining());
    }
  }
}
