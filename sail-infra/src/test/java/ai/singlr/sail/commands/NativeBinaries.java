/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The two native binaries the upgrade lanes run: {@link #candidate()}, the build under test, and
 * {@link #released()}, the release the fleet upgrades from. They arrive as {@code
 * -Dsail.it.nativeBinary} and {@code -Dsail.it.releasedBinary}; without them a test skips, except
 * under {@code -Dsail.it.requireNative=true}, where a missing binary fails loudly.
 */
record NativeBinaries(Path candidate, Path released) {

  static NativeBinaries requireOrSkip(String lane) {
    requireOrSkip(lane, unavailable());
    return new NativeBinaries(binary("sail.it.nativeBinary"), binary("sail.it.releasedBinary"));
  }

  /** Fails or skips {@code lane} for {@code reason}; a {@code null} reason lets it run. */
  static void requireOrSkip(String lane, String reason) {
    if (reason == null) {
      return;
    }
    if (Boolean.getBoolean("sail.it.requireNative")) {
      throw new AssertionError(
          lane
              + " is required in this lane (-Dsail.it.requireNative=true) but cannot run — the"
              + " test cannot validate anything. Reason: "
              + reason);
    }
    assumeTrue(false, lane + " skipped (" + reason + ")");
  }

  /** Why the binaries cannot be used, or {@code null} when both are executable files. */
  static String unavailable() {
    for (var property : List.of("sail.it.nativeBinary", "sail.it.releasedBinary")) {
      var value = System.getProperty(property, "");
      if (value.isBlank()) {
        return "-D" + property + " is not set";
      }
      if (!Files.isExecutable(Path.of(value))) {
        return "-D" + property + "=" + value + " is not an executable file";
      }
    }
    return null;
  }

  private static Path binary(String property) {
    return Path.of(System.getProperty(property)).toAbsolutePath();
  }
}
