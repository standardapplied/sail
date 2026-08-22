/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

/**
 * The access level a room agent holds — full write access or read-only. The canonical wire form is
 * snake_case ({@code full} / {@code read_only}), the spelling most surfaces (the agents endpoint,
 * invites, and the Mast client) already use; {@link #of} additionally accepts the legacy hyphenated
 * {@code read-only} an engagement stored before this unification, so old data reads back correctly
 * and migrates to the canonical form the next time it is written.
 */
public enum EngagementMode {
  FULL("full"),
  READ_ONLY("read_only");

  private static final String LEGACY_READ_ONLY = "read-only";

  private final String wire;

  EngagementMode(String wire) {
    this.wire = wire;
  }

  /** The canonical wire form. */
  public String wire() {
    return wire;
  }

  /** Whether this is the full-access mode. */
  public boolean isFull() {
    return this == FULL;
  }

  /**
   * The mode for a wire string — the canonical {@code full}/{@code read_only} or the legacy {@code
   * read-only}. Throws on a blank or unrecognized value; callers apply their own default for an
   * absent mode.
   */
  public static EngagementMode of(String mode) {
    if (FULL.wire.equals(mode)) {
      return FULL;
    }
    if (READ_ONLY.wire.equals(mode) || LEGACY_READ_ONLY.equals(mode)) {
      return READ_ONLY;
    }
    throw new IllegalArgumentException(
        "Engagement mode must be '"
            + FULL.wire
            + "' or '"
            + READ_ONLY.wire
            + "', got '"
            + mode
            + "'.");
  }
}
