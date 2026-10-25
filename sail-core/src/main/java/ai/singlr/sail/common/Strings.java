/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import java.util.regex.Pattern;

/**
 * Null-safe string predicates. All application code checks emptiness/blankness through this seam
 * rather than inlining {@code s == null || s.isBlank()}, so the intent reads consistently and a
 * null is never dereferenced.
 */
public final class Strings {

  private static final Pattern ESCAPE = Pattern.compile("\033\\[[0-?]*[ -/]*[@-~]");

  private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\033]]");

  private static final String RESET = "\033[0m";

  private Strings() {}

  /** Checks if a string is null or empty. */
  public static boolean isEmpty(String s) {
    return s == null || s.isEmpty();
  }

  /** Checks if a string is null, empty, or contains only whitespace. */
  public static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /** Checks if a string holds at least one non-whitespace character. */
  public static boolean isNotBlank(String s) {
    return !isBlank(s);
  }

  /**
   * Returns {@code value} when it holds a non-whitespace character, else throws {@link
   * IllegalArgumentException} naming {@code name} — the one throwing blank-guard so precondition
   * checks read consistently instead of each caller inlining its own.
   */
  public static String requireNonBlank(String value, String name) {
    if (isBlank(value)) {
      throw new IllegalArgumentException(name + " must not be blank.");
    }
    return value;
  }

  /**
   * {@code text} itself when it is at most {@code maxCodePoints} long, otherwise its beginning, cut
   * between code points so a surrogate pair is never split.
   */
  public static String clip(String text, int maxCodePoints) {
    return text.codePointCount(0, text.length()) <= maxCodePoints
        ? text
        : text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
  }

  /**
   * {@code text} as one line when it spans at most {@code width} terminal columns, otherwise its
   * beginning ending in "…", at most {@code width} columns in all; nothing when there is no room. A
   * terminal's style escapes take no columns and are kept up to the cut, and a cut styled text ends
   * with a reset so its style does not run on past it. It is one line: a tab, newline or other
   * control character, which a terminal moves its cursor on rather than draws, becomes a space.
   */
  public static String fit(String text, int width) {
    var line = CONTROL.matcher(text).replaceAll(" ");
    if (columns(line) <= width) {
      return line;
    }
    if (width < 1) {
      return "";
    }
    var escape = ESCAPE.matcher(line);
    var styled = false;
    var end = 0;
    var used = 0;
    while (end < line.length()) {
      if (escape.region(end, line.length()).lookingAt()) {
        styled = true;
        end = escape.end();
        continue;
      }
      var size = TerminalColumns.of(line.codePointAt(end));
      if (used + size > width - 1) {
        break;
      }
      used += size;
      end = line.offsetByCodePoints(end, 1);
    }
    return line.substring(0, end) + "…" + (styled ? RESET : "");
  }

  /**
   * The terminal columns {@code text} spans: two for each wide character, none for a mark or a
   * style escape.
   */
  public static int columns(String text) {
    return ESCAPE.matcher(text).replaceAll("").codePoints().map(TerminalColumns::of).sum();
  }

  /** {@code word} as one shell word to paste back: bare when that is safe, else single-quoted. */
  public static String shellWord(String word) {
    return word.matches("[A-Za-z0-9._/=:@,+-]+") ? word : "'" + word.replace("'", "'\\''") + "'";
  }
}
