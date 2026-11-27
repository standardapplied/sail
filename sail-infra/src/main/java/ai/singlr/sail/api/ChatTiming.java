/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Time as a chat's host reads it, injectable so tests never sleep: the clock, how a pause is waited
 * (the text window), how a read waits for an item ({@code patience}) and the most it waits ({@code
 * poll}), how long a request is held unanswered, and the ending grace.
 */
record ChatTiming(
    Supplier<Instant> clock,
    RoomWakeReactor.Delay delay,
    ChatTiming.Patience patience,
    Duration poll,
    Duration requestBound,
    ProcessEnd.Grace grace) {

  /**
   * How a read waits, holding {@code arrivals}, for an item to be logged, at most {@code within}.
   */
  @FunctionalInterface
  interface Patience {
    void await(Object arrivals, Duration within) throws InterruptedException;
  }

  static ChatTiming real() {
    return new ChatTiming(
        DateTimeUtils::now,
        Thread::sleep,
        (arrivals, within) -> arrivals.wait(Math.max(1, within.toMillis())),
        Duration.ofSeconds(25),
        Duration.ofMinutes(5),
        ProcessEnd.Grace.DEFAULT);
  }
}
