/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.webauthn;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Hashing helpers for the WebAuthn flow. */
final class Hashes {

  private Hashes() {}

  /** SHA-256 of {@code input} — used for the RP ID hash and the clientDataJSON hash. */
  static byte[] sha256(byte[] input) {
    return digest("SHA-256", input);
  }

  static byte[] digest(String algorithm, byte[] input) {
    try {
      return MessageDigest.getInstance(algorithm).digest(input);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(algorithm + " is unavailable", e);
    }
  }
}
