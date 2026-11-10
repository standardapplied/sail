/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.identity;

import java.util.Locale;

/**
 * The role a person or credential holds; what each may do is its column of the access policy
 * ({@code ai.singlr.sail.access.Policy}). Roles are stored as lowercase strings; {@link
 * #fromAttribute} resolves the stored value, failing safe to the least-privileged {@link #VIEWER}
 * for any missing, blank, or unrecognized value so a malformed role can never silently escalate.
 * Every credential carries an explicit role — {@code api_tokens.role} and {@code fdes.role} are
 * both {@code NOT NULL} with a CHECK constraint — so this fallback is defense in depth, not a path
 * any live credential travels.
 */
public enum Role {
  ADMIN,
  MEMBER,
  VIEWER;

  /**
   * This role, or {@code cap} when {@code cap} grants less: a credential never outranks itself. The
   * constants are declared from most to least authority, and this comparison relies on that order.
   */
  public Role cappedBy(Role cap) {
    return compareTo(cap) >= 0 ? this : cap;
  }

  /** The stored, lowercase form {@link #fromAttribute} resolves. */
  public String attribute() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Resolves the role from its stored form. Any null, blank, or unrecognized value fails safe to
   * {@link #VIEWER} so a malformed or absent role can never silently escalate.
   */
  public static Role fromAttribute(Object attribute) {
    if (attribute == null) {
      return VIEWER;
    }
    var value = attribute.toString().strip().toLowerCase(Locale.ROOT);
    return switch (value) {
      case "admin" -> ADMIN;
      case "member" -> MEMBER;
      default -> VIEWER;
    };
  }
}
