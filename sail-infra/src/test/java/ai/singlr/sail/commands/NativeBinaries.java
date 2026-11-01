/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The native binary the fleet lanes run: {@link #candidate()}, the build under test. It arrives as
 * {@code -Dsail.it.nativeBinary}; without it a test skips, except under {@code
 * -Dsail.it.requireNative=true}, where a missing binary fails loudly.
 */
record NativeBinaries(Path candidate) {

  static NativeBinaries requireOrSkip(String lane) {
    requireOrSkip(lane, unavailable());
    return new NativeBinaries(binary(NATIVE_BINARY));
  }

  private static final String NATIVE_BINARY = "sail.it.nativeBinary";

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

  /** Why the binary cannot be used, or {@code null} when it is an executable file. */
  static String unavailable() {
    var value = System.getProperty(NATIVE_BINARY, "");
    if (value.isBlank()) {
      return "-D" + NATIVE_BINARY + " is not set";
    }
    if (!Files.isExecutable(Path.of(value))) {
      return "-D" + NATIVE_BINARY + "=" + value + " is not an executable file";
    }
    return null;
  }

  private static Path binary(String property) {
    return Path.of(System.getProperty(property)).toAbsolutePath();
  }
}
