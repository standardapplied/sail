/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

  @Test
  void allowsUpToBurstThenRejects() {
    var limiter = new RateLimiter(3, 0d, () -> 0L);

    assertTrue(limiter.tryAcquire("k"));
    assertTrue(limiter.tryAcquire("k"));
    assertTrue(limiter.tryAcquire("k"));
    assertFalse(limiter.tryAcquire("k"), "the burst is spent");
  }

  @Test
  void refillsOverTime() {
    var clock = new AtomicLong(0);
    var oneTokenPerSecond = 1d / 1_000_000_000d;
    var limiter = new RateLimiter(1, oneTokenPerSecond, clock::get);

    assertTrue(limiter.tryAcquire("k"));
    assertFalse(limiter.tryAcquire("k"));

    clock.set(1_000_000_000L);
    assertTrue(limiter.tryAcquire("k"), "one second later, one token is back");
  }

  @Test
  void refillIsCappedAtCapacity() {
    var clock = new AtomicLong(0);
    var limiter = new RateLimiter(2, 1d / 1_000_000_000d, clock::get);

    limiter.tryAcquire("k");
    clock.set(1_000_000_000_000L);

    assertTrue(limiter.tryAcquire("k"));
    assertTrue(limiter.tryAcquire("k"));
    assertFalse(limiter.tryAcquire("k"), "idle time cannot bank more than the burst capacity");
  }

  @Test
  void keysHaveIndependentBuckets() {
    var limiter = new RateLimiter(1, 0d, () -> 0L);

    assertTrue(limiter.tryAcquire("a"));
    assertTrue(limiter.tryAcquire("b"));
    assertFalse(limiter.tryAcquire("a"));
  }

  @Test
  void perMinuteFactoryBurstsThenThrottles() {
    var limiter = RateLimiter.perMinute(60, 2);

    assertTrue(limiter.tryAcquire("k"));
    assertTrue(limiter.tryAcquire("k"));
    assertFalse(limiter.tryAcquire("k"));
  }
}
