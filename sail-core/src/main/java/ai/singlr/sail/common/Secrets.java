/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Mints unguessable secrets — the bearer tokens, credentials, and tickets every store hands out.
 * One shared, thread-safe {@link SecureRandom} behind a single idiom (random bytes, hex-encoded,
 * optionally prefixed) so token generation reads identically everywhere instead of each store
 * hand-rolling the same {@code new byte[]} / {@code nextBytes} / {@code formatHex} dance.
 */
public final class Secrets {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int DEFAULT_BYTES = 32;

  private Secrets() {}

  /** A {@code <prefix>_<hex>} secret over 32 random bytes — the common bearer-token shape. */
  public static String mint(String prefix) {
    return mint(prefix, DEFAULT_BYTES);
  }

  /** A {@code <prefix>_<hex>} secret over {@code bytes} random bytes. */
  public static String mint(String prefix, int bytes) {
    return prefix + "_" + hex(bytes);
  }

  /** Hex of {@code bytes} random bytes, unprefixed — for values that carry their own framing. */
  public static String hex(int bytes) {
    var value = new byte[bytes];
    RANDOM.nextBytes(value);
    return HexFormat.of().formatHex(value);
  }
}
