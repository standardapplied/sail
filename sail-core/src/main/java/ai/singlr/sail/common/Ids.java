/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates UUID v7 identifiers and provides UTC timestamps.
 *
 * <p>UUID v7 combines a Unix millisecond timestamp with a monotonic counter and random bits,
 * producing time-ordered, globally unique identifiers per RFC 9562.
 *
 * <p>Guarantees:
 *
 * <ul>
 *   <li>Monotonically increasing IDs across all threads
 *   <li>Up to 4096 IDs per millisecond before automatic timestamp advancement
 *   <li>Cryptographically secure random lower 62 bits
 *   <li>Lock-free via a single atomic CAS with no side effects
 * </ul>
 */
public final class Ids {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int COUNTER_BITS = 12;
  private static final long COUNTER_MASK = (1L << COUNTER_BITS) - 1;
  private static final long VERSION_7 = 0x7L << 12;
  private static final long VARIANT_RFC4122 = 0x8000_0000_0000_0000L;
  private static final long RANDOM_MASK = 0x3FFF_FFFF_FFFF_FFFFL;
  private static final AtomicLong STATE = new AtomicLong(0);

  private Ids() {}

  /**
   * Generates a new UUID v7 identifier.
   *
   * <p>Layout per RFC 9562:
   *
   * <pre>
   * MSB: [48-bit unix timestamp] [4-bit version=0111] [12-bit counter]
   * LSB: [2-bit variant=10]      [62-bit random]
   * </pre>
   *
   * @return a new UUID v7
   */
  public static UUID newId() {
    var now = Instant.now().toEpochMilli();
    var state = STATE.updateAndGet(last -> nextState(last, now));

    var timestamp = state >>> COUNTER_BITS;
    var counter = state & COUNTER_MASK;

    var msb = (timestamp << 16) | VERSION_7 | counter;
    var lsb = VARIANT_RFC4122 | (RANDOM.nextLong() & RANDOM_MASK);

    return new UUID(msb, lsb);
  }

  /**
   * Computes the next monotonic state from the current state and wall-clock time.
   *
   * <p>The state packs a millisecond timestamp in the high bits and a 12-bit counter in the low
   * bits. This method is a pure function with no side effects, making it safe for use inside {@link
   * AtomicLong#updateAndGet} which may retry on CAS failure.
   *
   * <p>Three cases:
   *
   * <ol>
   *   <li>Clock advanced: reset counter to zero with new timestamp
   *   <li>Counter exhausted (4096 IDs in one ms): advance timestamp by one
   *   <li>Otherwise: increment counter
   * </ol>
   *
   * @param currentState packed state (high bits = timestamp, low 12 bits = counter)
   * @param nowMillis current wall-clock time in epoch milliseconds
   * @return the next packed state, guaranteed greater than currentState
   */
  static long nextState(long currentState, long nowMillis) {
    var lastTimestamp = currentState >>> COUNTER_BITS;
    var lastCounter = currentState & COUNTER_MASK;

    if (nowMillis > lastTimestamp) {
      return nowMillis << COUNTER_BITS;
    }
    if (lastCounter >= COUNTER_MASK) {
      return (lastTimestamp + 1) << COUNTER_BITS;
    }
    return currentState + 1;
  }

  /**
   * Returns the current UTC timestamp.
   *
   * @return the current time in UTC
   */
  public static OffsetDateTime now() {
    return OffsetDateTime.now(Clock.systemUTC());
  }
}
