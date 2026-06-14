/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-key token-bucket rate limiter. Each key (an API credential) gets a bucket that refills at a
 * steady rate up to a burst capacity; {@link #tryAcquire} takes one token, returning {@code false}
 * when the bucket is empty so the caller can reject with {@code 429}. Buckets are created lazily
 * and keyed by credential, so the map is bounded by the number of issued credentials.
 *
 * <p>Refill is measured against a monotonic clock ({@link System#nanoTime}), the correct source for
 * elapsed time — it never jumps when the wall clock is adjusted. The clock is injectable so the
 * refill behaviour is deterministically testable.
 */
public final class RateLimiter {

  private static final double NANOS_PER_MINUTE = 60d * 1_000_000_000d;

  private final double capacity;
  private final double refillPerNano;
  private final LongSupplier nanoClock;
  private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

  RateLimiter(double capacity, double refillPerNano, LongSupplier nanoClock) {
    this.capacity = capacity;
    this.refillPerNano = refillPerNano;
    this.nanoClock = nanoClock;
  }

  /** A limiter allowing {@code permitsPerMinute} sustained, bursting up to {@code burst}. */
  public static RateLimiter perMinute(int permitsPerMinute, int burst) {
    return new RateLimiter(burst, permitsPerMinute / NANOS_PER_MINUTE, System::nanoTime);
  }

  /** Takes one token for {@code key}; {@code false} when the bucket is empty (caller rejects). */
  public boolean tryAcquire(String key) {
    var bucket =
        buckets.computeIfAbsent(key, ignored -> new Bucket(capacity, nanoClock.getAsLong()));
    synchronized (bucket) {
      var now = nanoClock.getAsLong();
      bucket.tokens =
          Math.min(capacity, bucket.tokens + (now - bucket.lastRefillNanos) * refillPerNano);
      bucket.lastRefillNanos = now;
      if (bucket.tokens >= 1d) {
        bucket.tokens -= 1d;
        return true;
      }
      return false;
    }
  }

  private static final class Bucket {
    private double tokens;
    private long lastRefillNanos;

    private Bucket(double tokens, long lastRefillNanos) {
      this.tokens = tokens;
      this.lastRefillNanos = lastRefillNanos;
    }
  }
}
