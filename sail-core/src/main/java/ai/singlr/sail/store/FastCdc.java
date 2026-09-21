/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.store;

import java.util.Objects;

/** Content-defined Gear chunking with minimum skipping and normalized cut-point masks. */
public final class FastCdc {
  public static final int MIN = 64 * 1024;
  public static final int TARGET = 256 * 1024;
  public static final int MAX = 1024 * 1024;
  private static final long[] GEAR = gear();
  private static final long EARLY_MASK = (1L << 19) - 1;
  private static final long LATE_MASK = (1L << 17) - 1;

  private FastCdc() {}

  /** Returns the first chunk's length in a window of at most {@link #MAX} bytes. */
  public static int cut(byte[] bytes, int length) {
    Objects.checkFromIndexSize(0, length, bytes.length);
    var end = Math.min(length, MAX);
    var fingerprint = 0L;
    for (var i = MIN; i < end; i++) {
      fingerprint = (fingerprint << 1) + GEAR[bytes[i] & 255];
      var mask = i < TARGET ? EARLY_MASK : LATE_MASK;
      if ((fingerprint & mask) == 0) return i + 1;
    }
    return end;
  }

  private static long[] gear() {
    var gear = new long[256];
    var seed = 0x243f6a8885a308d3L;
    for (var i = 0; i < gear.length; i++) {
      seed += 0x9e3779b97f4a7c15L;
      var value = seed;
      value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
      value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
      gear[i] = value ^ (value >>> 31);
    }
    return gear;
  }
}
