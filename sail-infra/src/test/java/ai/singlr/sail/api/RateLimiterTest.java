/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void aNewKeyEvictsARefilledBucketRatherThanGrowingTheMap() {
    var clock = new AtomicLong(0);
    var oneTokenPerSecond = 1d / 1_000_000_000d;
    var limiter = new RateLimiter(1, oneTokenPerSecond, clock::get, 2);

    assertTrue(limiter.tryAcquire("a"));
    assertTrue(limiter.tryAcquire("b"));
    clock.set(1_000_000_000L);

    assertTrue(limiter.tryAcquire("c"), "both buckets refilled, so the cap makes room");
    assertFalse(limiter.tryAcquire("c"), "the evicting caller still gets a real bucket");
  }

  @Test
  void aFloodOfNewKeysIsRefusedRatherThanAllowedToGrowTheMap() {
    var limiter = new RateLimiter(1, 0d, () -> 0L, 2);

    assertTrue(limiter.tryAcquire("a"));
    assertTrue(limiter.tryAcquire("b"));

    assertFalse(limiter.tryAcquire("c"), "no bucket can be freed, so the new key is refused");
    assertFalse(limiter.tryAcquire("d"));
  }

  @Test
  void anEvictedKeyStartsFreshWhenItReturns() {
    var clock = new AtomicLong(0);
    var limiter = new RateLimiter(1, 1d / 1_000_000_000d, clock::get, 1);

    assertTrue(limiter.tryAcquire("a"));
    clock.set(1_000_000_000L);

    assertTrue(limiter.tryAcquire("b"), "'a' refilled and was evicted");
    assertFalse(limiter.tryAcquire("a"), "'a' is refused: 'b' now holds the only slot");
  }

  @Test
  void perMinuteFactoryBurstsThenThrottles() {
    var limiter = RateLimiter.perMinute(60, 2);

    assertTrue(limiter.tryAcquire("k"));
    assertTrue(limiter.tryAcquire("k"));
    assertFalse(limiter.tryAcquire("k"));
  }
}
