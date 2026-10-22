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

  /** {@code word} as one shell word to paste back: bare when that is safe, else single-quoted. */
  public static String shellWord(String word) {
    return word.matches("[A-Za-z0-9._/=:@,+-]+") ? word : "'" + word.replace("'", "'\\''") + "'";
  }
}
