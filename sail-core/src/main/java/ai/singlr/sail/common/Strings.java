/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

/**
 * Null-safe string predicates. All application code checks emptiness/blankness through this seam
 * rather than inlining {@code s == null || s.isBlank()}, so the intent reads consistently and a
 * null is never dereferenced.
 */
public final class Strings {

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
   * {@code text} itself when it spans at most {@code width} terminal columns, otherwise its
   * beginning ending in "…", at most {@code width} columns in all; nothing when there is no room.
   */
  public static String fit(String text, int width) {
    if (columns(text) <= width) {
      return text;
    }
    if (width < 1) {
      return "";
    }
    var end = 0;
    var used = 0;
    while (end < text.length()) {
      var size = columns(text.codePointAt(end));
      if (used + size > width - 1) {
        break;
      }
      used += size;
      end = text.offsetByCodePoints(end, 1);
    }
    return text.substring(0, end) + "…";
  }

  /** The terminal columns {@code text} spans: two for each wide character, none for a mark. */
  public static int columns(String text) {
    return text.codePoints().map(Strings::columns).sum();
  }

  private static int columns(int codePoint) {
    return switch (Character.getType(codePoint)) {
      case Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT -> 0;
      default -> isWide(codePoint) ? 2 : 1;
    };
  }

  /** East Asian wide and fullwidth characters and emoji, after Markus Kuhn's wcwidth ranges. */
  private static boolean isWide(int c) {
    return (c >= 0x1100 && c <= 0x115F)
        || (c >= 0x2E80 && c <= 0x303E)
        || (c >= 0x3041 && c <= 0x33FF)
        || (c >= 0x3400 && c <= 0x4DBF)
        || (c >= 0x4E00 && c <= 0x9FFF)
        || (c >= 0xA000 && c <= 0xA4CF)
        || (c >= 0xAC00 && c <= 0xD7A3)
        || (c >= 0xF900 && c <= 0xFAFF)
        || (c >= 0xFE30 && c <= 0xFE4F)
        || (c >= 0xFF00 && c <= 0xFF60)
        || (c >= 0xFFE0 && c <= 0xFFE6)
        || (c >= 0x1F300 && c <= 0x1F64F)
        || (c >= 0x1F900 && c <= 0x1F9FF)
        || (c >= 0x20000 && c <= 0x3FFFD);
  }

  /** {@code word} as one shell word to paste back: bare when that is safe, else single-quoted. */
  public static String shellWord(String word) {
    return word.matches("[A-Za-z0-9._/=:@,+-]+") ? word : "'" + word.replace("'", "'\\''") + "'";
  }
}
