/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.Month;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What came of the login a run gave its harness, as the harness's own report says ({@link
 * Harness#loginOutcome}): refused, limited by its account's quota, passed over for another
 * credential, or never seen at all. A report that says none of these has no outcome, and nothing is
 * guessed from it. Nothing a harness printed is carried: a reset time is Sail's own rendering of
 * the time it parsed, and a source is one of the names its harness declares.
 */
public sealed interface LoginOutcome {

  /** The harness refused the login: expired, revoked or wrong. */
  record Refused() implements LoginOutcome {}

  /**
   * The login's account ran out of quota; {@code resets} is when, as Sail renders the time its
   * harness gave ({@link #resetTime}), or null when it gave none Sail could parse.
   */
  record Limited(String resets) implements LoginOutcome {}

  /** The harness took its credential from {@code source}, not from the login it was given. */
  record Overridden(String source) implements LoginOutcome {}

  /**
   * The harness saw no login at all, though Sail gave it one: Sail's delivery failed, and the login
   * itself was never tried.
   */
  record Undelivered() implements LoginOutcome {}

  /**
   * A reset time as a harness writes one: an optional month and day ({@code Nov 16}, {@code Nov
   * 16th, 2026}, {@code Nov 16 at}), the hour with optional minutes and am or pm, and an optional
   * zone in brackets.
   */
  Pattern RESET =
      Pattern.compile(
          "(?:([A-Za-z]{3}) (\\d{1,2})(?:st|nd|rd|th)?,?(?: (\\d{4}))?(?: at)? )?"
              + "(\\d{1,2})(?::(\\d{2}))? ?([AaPp][Mm])(?: \\(([A-Za-z_]+(?:/[A-Za-z_]+)*)\\))?");

  /**
   * {@code said} parsed as a reset time and rendered by Sail ({@code 2026-11-16 15:04}, {@code
   * 11-16 15:04}, {@code 15:00 UTC}), or null when it is not wholly one: no word of it is repeated.
   */
  static String resetTime(String said) {
    var parsed = RESET.matcher(said == null ? "" : said.strip());
    if (!parsed.matches()) {
      return null;
    }
    try {
      var hour = Integer.parseInt(parsed.group(4));
      if (hour < 1 || hour > 12) {
        return null;
      }
      var pm = parsed.group(6).equalsIgnoreCase("pm");
      var time =
          LocalTime.of(
              hour % 12 + (pm ? 12 : 0),
              parsed.group(5) == null ? 0 : Integer.parseInt(parsed.group(5)));
      var zone = parsed.group(7) == null ? "" : " " + ZoneId.of(parsed.group(7)).getId();
      return day(parsed.group(1), parsed.group(2), parsed.group(3)) + time + zone;
    } catch (DateTimeException | IllegalArgumentException unparsed) {
      return null;
    }
  }

  private static String day(String month, String dayOfMonth, String year) {
    if (month == null) {
      return "";
    }
    var named =
        Arrays.stream(Month.values())
            .filter(m -> m.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).equalsIgnoreCase(month))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no month " + month));
    var day = MonthDay.of(named, Integer.parseInt(dayOfMonth));
    return (year == null ? day.toString().substring(2) : day.atYear(Integer.parseInt(year))) + " ";
  }
}
