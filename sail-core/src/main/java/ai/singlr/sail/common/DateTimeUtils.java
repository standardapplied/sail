/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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

  /**
   * The clock {@link #now()} reads. A test pins it to write rows at the instants it names; the
   * stores order rows by their UUIDv7 id, as minted, whatever the clock makes of an instant's text.
   */
  public static volatile Clock clock = Clock.systemUTC();

  /**
   * UTC instant for "right now", at the millisecond every stored instant keeps. An {@code Instant}
   * is always UTC by definition.
   */
  public static Instant now() {
    return Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
  }

  /**
   * Now, or a millisecond past {@code last} when the clock has not yet passed it: the time of a
   * local revision of a row last revised at {@code last}, so each revision of the row has its own.
   */
  public static Instant nowAfter(Instant last) {
    var now = now();
    return last == null || now.isAfter(last) ? now : last.plusMillis(1);
  }

  /** The integer millisecond {@code instant} is stored as. */
  public static long toMillis(Instant instant) {
    return instant.toEpochMilli();
  }

  /** The instant a stored integer millisecond names. */
  public static Instant ofMillis(long millis) {
    return Instant.ofEpochMilli(millis);
  }

  /**
   * {@code instant} as text leaves the process: ISO-8601 UTC at millisecond width, always three
   * fraction digits ({@code 2026-10-20T19:09:39.656Z}); null stays null.
   */
  public static String format(Instant instant) {
    return instant == null ? null : EDGE.format(instant);
  }

  /** The instant {@code text} names in ISO-8601, at the millisecond it would be stored at. */
  public static Instant parse(String text) {
    return Instant.parse(text).truncatedTo(ChronoUnit.MILLIS);
  }

  private static final DateTimeFormatter EDGE =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  /** UUIDv7 identifier — time-ordered, monotonic. */
  public static UUID newId() {
    return Ids.newId();
  }
}
