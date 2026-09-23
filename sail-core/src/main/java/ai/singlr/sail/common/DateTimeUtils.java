/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Facade over identifier and time utilities. All application code goes through this seam rather
 * than calling {@code Instant.now()} or {@code Ids.now()} directly — keeps tests stubbable and lets
 * us swap providers without touching call sites.
 */
public final class DateTimeUtils {

  private static final Pattern AGE = Pattern.compile("(\\d+)([dhm])");

  private DateTimeUtils() {}

  /**
   * Parses an age like {@code 90d}, {@code 24h} or {@code 30m} — the one spelling every "older
   * than" option and retention setting takes.
   *
   * @throws IllegalArgumentException naming the accepted forms when {@code value} is not one
   */
  public static Duration parseAge(String value) {
    var matcher = AGE.matcher(value == null ? "" : value.strip());
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          "Invalid age format: '"
              + value
              + "'. Use a number followed by d (days), h (hours), or m (minutes)."
              + " Examples: 7d, 24h, 30d");
    }
    var amount = Long.parseLong(matcher.group(1));
    return switch (matcher.group(2)) {
      case "d" -> Duration.ofDays(amount);
      case "h" -> Duration.ofHours(amount);
      default -> Duration.ofMinutes(amount);
    };
  }

  /** UTC instant for "right now". An {@code Instant} is always UTC by definition. */
  public static Instant now() {
    return Ids.now().toInstant();
  }

  /** UUIDv7 identifier — time-ordered, monotonic. */
  public static UUID newId() {
    return Ids.newId();
  }
}
