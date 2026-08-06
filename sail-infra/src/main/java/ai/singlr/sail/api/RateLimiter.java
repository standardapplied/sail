/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-key token-bucket rate limiter. Each key gets a bucket that refills at a steady rate up to a
 * burst capacity; {@link #tryAcquire} takes one token, returning {@code false} when the bucket is
 * empty so the caller can reject with {@code 429}.
 *
 * <p>Buckets are created lazily, so the key space is whatever the caller keys by — and on the
 * pre-authentication surface that is the remote address, which an attacker chooses. The map is
 * therefore capped at {@value #DEFAULT_MAX_KEYS} keys: when a new key arrives at the cap, fully
 * refilled buckets are evicted first, and only if none can be freed is the request refused.
 * Evicting a full bucket costs nothing, because a full bucket and a freshly created one hold
 * identical state — so the cap bounds memory without ever forgetting that someone is being
 * throttled.
 *
 * <p>Refill is measured against a monotonic clock ({@link System#nanoTime}), the correct source for
 * elapsed time — it never jumps when the wall clock is adjusted. The clock is injectable so the
 * refill behaviour is deterministically testable.
 */
public final class RateLimiter {

  private static final double NANOS_PER_MINUTE = 60d * 1_000_000_000d;

  static final int DEFAULT_MAX_KEYS = 50_000;

  private final double capacity;
  private final double refillPerNano;
  private final LongSupplier nanoClock;
  private final int maxKeys;
  private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

  RateLimiter(double capacity, double refillPerNano, LongSupplier nanoClock) {
    this(capacity, refillPerNano, nanoClock, DEFAULT_MAX_KEYS);
  }

  RateLimiter(double capacity, double refillPerNano, LongSupplier nanoClock, int maxKeys) {
    this.capacity = capacity;
    this.refillPerNano = refillPerNano;
    this.nanoClock = nanoClock;
    this.maxKeys = maxKeys;
  }

  /** A limiter allowing {@code permitsPerMinute} sustained, bursting up to {@code burst}. */
  public static RateLimiter perMinute(int permitsPerMinute, int burst) {
    return new RateLimiter(burst, permitsPerMinute / NANOS_PER_MINUTE, System::nanoTime);
  }

  /** Takes one token for {@code key}; {@code false} when the bucket is empty (caller rejects). */
  public boolean tryAcquire(String key) {
    var bucket = buckets.get(key);
    if (bucket == null) {
      if (buckets.size() >= maxKeys && !evictRefilled()) {
        return false;
      }
      bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(capacity, nanoClock.getAsLong()));
    }
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

  /**
   * Drops every bucket that has refilled to capacity, since such a bucket is indistinguishable from
   * the one a returning caller would be given anyway. A request in flight against an evicted bucket
   * simply charges a detached one that had a full budget — the same answer it would have got.
   *
   * @return {@code true} if there is now room for a new key.
   */
  private boolean evictRefilled() {
    var now = nanoClock.getAsLong();
    buckets
        .entrySet()
        .removeIf(
            entry -> {
              var bucket = entry.getValue();
              synchronized (bucket) {
                return bucket.tokens + (now - bucket.lastRefillNanos) * refillPerNano >= capacity;
              }
            });
    return buckets.size() < maxKeys;
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
