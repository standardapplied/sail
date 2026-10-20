/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/**
 * A clock a test moves by hand, and never past its horizon: a test that would have to wait longer
 * than it means to fails at once instead of hanging.
 */
final class ManualClock implements InstantSource {

  private final Instant horizon;
  private volatile Instant now;

  ManualClock(Instant start, Duration horizon) {
    this.now = start;
    this.horizon = start.plus(horizon);
  }

  @Override
  public Instant instant() {
    return now;
  }

  void advance(Duration by) {
    var next = now.plus(by);
    if (next.isAfter(horizon)) {
      throw new IllegalStateException("the clock would pass its horizon " + horizon);
    }
    now = next;
  }
}
