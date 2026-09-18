/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.util.Objects;
import java.util.Optional;

/** Simple semantic version record with parsing and comparison. */
public record SemVer(int major, int minor, int patch) implements Comparable<SemVer> {

  /**
   * Parses a version string like {@code "1.7.0"}, {@code "v1.7.0"}, or {@code "1.7"}. The leading
   * {@code v} is stripped if present. A missing patch component defaults to 0.
   */
  public static SemVer parse(String version) {
    var v = version.startsWith("v") ? version.substring(1) : version;
    var parts = v.split("\\.");
    if (parts.length < 2) {
      throw new IllegalArgumentException("Invalid version: " + version);
    }
    var major = Integer.parseInt(parts[0]);
    var minor = Integer.parseInt(parts[1]);
    var patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
    return new SemVer(major, minor, patch);
  }

  /**
   * {@link #parse} for a version that may be missing or malformed — a dev build, a peer's unknown
   * shape — where the caller wants to carry on without it rather than fail. Both ends of the sync
   * wire lean on this one definition of "not a version".
   */
  public static Optional<SemVer> tryParse(String version) {
    try {
      return Optional.of(parse(Objects.requireNonNull(version)));
    } catch (RuntimeException malformed) {
      return Optional.empty();
    }
  }

  @Override
  public int compareTo(SemVer other) {
    var c = Integer.compare(major, other.major);
    if (c != 0) {
      return c;
    }
    c = Integer.compare(minor, other.minor);
    if (c != 0) {
      return c;
    }
    return Integer.compare(patch, other.patch);
  }

  @Override
  public String toString() {
    return major + "." + minor + "." + patch;
  }
}
