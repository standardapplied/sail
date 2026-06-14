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
}
