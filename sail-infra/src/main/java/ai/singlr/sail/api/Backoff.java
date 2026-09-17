/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** Deterministic retry policy; clock and jitter are its only inputs. */
public final class Backoff {
  public static final Duration HALF_OPEN_DELAY = Duration.ofMinutes(5);
  private final Supplier<Instant> now;
  private final DoubleSupplier jitter;
  private Instant attemptedAt;
  private Instant nextAt;
  private int failures;

  public Backoff(Clock clock, DoubleSupplier jitter) {
    this(clock::instant, jitter);
  }

  Backoff(Supplier<Instant> now, DoubleSupplier jitter) {
    this.now = Objects.requireNonNull(now, "clock");
    this.jitter = Objects.requireNonNull(jitter, "jitter");
  }

  public void observe(Instant attemptedAt, int failures) {
    if (failures < 0) throw new IllegalArgumentException("failures must be non-negative");
    if (Objects.equals(this.attemptedAt, attemptedAt) && this.failures == failures) return;
    this.attemptedAt = attemptedAt;
    this.failures = failures;
    if (failures == 0) {
      nextAt = null;
      return;
    }
    Objects.requireNonNull(attemptedAt, "failed attempt time");
    var delay = HALF_OPEN_DELAY;
    if (!open()) {
      var sample = jitter.getAsDouble();
      if (!Double.isFinite(sample) || sample < 0 || sample > 1) {
        throw new IllegalArgumentException("jitter must be between 0 and 1");
      }
      delay = Duration.ofMillis(Math.round((15_000L << (failures - 1)) * (0.8 + 0.4 * sample)));
    }
    nextAt = attemptedAt.plus(delay);
  }

  public boolean open() {
    return failures >= 5;
  }

  public boolean ready(boolean explicit) {
    return open() ? explicit : remaining().isZero();
  }

  public boolean probeDue() {
    return open() && remaining().isZero();
  }

  public Duration remaining() {
    if (nextAt == null) return Duration.ZERO;
    var remaining = Duration.between(now.get(), nextAt);
    return remaining.isNegative() ? Duration.ZERO : remaining;
  }
}
