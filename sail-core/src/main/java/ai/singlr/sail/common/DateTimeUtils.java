/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Facade over identifier and time utilities. All application code goes through this seam rather
 * than calling {@code Instant.now()} or {@code Ids.now()} directly — keeps tests stubbable and lets
 * us swap providers without touching call sites.
 */
public final class DateTimeUtils {

  private DateTimeUtils() {}

  /** UTC instant for "right now". An {@code Instant} is always UTC by definition. */
  public static Instant now() {
    return Ids.now().toInstant();
  }

  /** UTC offset date-time for "right now". Use only when offset is semantically meaningful. */
  public static OffsetDateTime nowOffset() {
    return Ids.now();
  }

  /** UUIDv7 identifier — time-ordered, monotonic. */
  public static UUID newId() {
    return Ids.newId();
  }

  public static Instant parseInstant(String iso) {
    return OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
  }

  public static String formatIso(Instant instant) {
    return DateTimeFormatter.ISO_INSTANT.format(instant.atOffset(ZoneOffset.UTC));
  }
}
