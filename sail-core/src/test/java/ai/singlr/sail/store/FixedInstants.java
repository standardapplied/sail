/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/**
 * Writes under {@link DateTimeUtils#clock} pinned to one instant. An instant's text drops trailing
 * zeros, so each pair {@link #textSortsBackwards} names is earlier then later in time and later
 * then earlier as text: the pairs a clock of any resolution can land on.
 */
final class FixedInstants {

  private FixedInstants() {}

  static Stream<Arguments> textSortsBackwards() {
    return Stream.of(
        Arguments.of("2026-10-19T12:00:51.123Z", "2026-10-19T12:00:51.123400Z"),
        Arguments.of("2026-10-19T12:00:51Z", "2026-10-19T12:00:51.001Z"));
  }

  static <T> T at(String instant, Supplier<T> write) {
    DateTimeUtils.clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    try {
      return write.get();
    } finally {
      DateTimeUtils.clock = Clock.systemUTC();
    }
  }
}
