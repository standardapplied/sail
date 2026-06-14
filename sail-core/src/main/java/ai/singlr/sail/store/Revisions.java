/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.Strings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Mints entity revision ids of the form {@code <counter>-<short content hash>}. The counter is a
 * per-entity monotonic sequence (so revisions order unambiguously without relying on wall-clock);
 * the hash is the first bytes of SHA-256 over the snapshot, making a revision content-addressable
 * and identical content evident. The DB-sync engine (later brick) treats the main devbox's
 * assignment as authoritative; single-box, the counter is simply the local revision number.
 */
public final class Revisions {

  private static final int HASH_CHARS = 12;

  private Revisions() {}

  /** Returns the next revision id after {@code currentRev} for content {@code snapshot}. */
  public static String next(String currentRev, String snapshot) {
    return (counterOf(currentRev) + 1) + "-" + shortHash(snapshot);
  }

  /** Extracts the monotonic counter from a revision id; 0 for null/blank/malformed. */
  public static long counterOf(String rev) {
    if (Strings.isBlank(rev)) {
      return 0;
    }
    var dash = rev.indexOf('-');
    var prefix = dash < 0 ? rev : rev.substring(0, dash);
    try {
      return Long.parseLong(prefix);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static String shortHash(String content) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      var hex = HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
      return hex.substring(0, HASH_CHARS);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
