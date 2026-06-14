/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.ssh;

import ai.singlr.sail.common.Strings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Set;

/**
 * A parsed OpenSSH public key — the unit that binds an engineer's SSH key to their FDE. Parsing is
 * strict and fail-closed: the line must be {@code <type> <base64-blob> [comment]}, the type must be
 * one we support, the blob must be valid base64 whose embedded algorithm name matches the declared
 * type (so a random base64 string can't masquerade as a key). The {@link #fingerprint()} is the
 * standard OpenSSH {@code SHA256:} fingerprint and is the registry's stable primary key.
 */
public record SshPublicKey(String type, String blob, String comment, String fingerprint) {

  private static final Set<String> SUPPORTED_TYPES =
      Set.of(
          "ssh-ed25519",
          "ssh-rsa",
          "ecdsa-sha2-nistp256",
          "ecdsa-sha2-nistp384",
          "ecdsa-sha2-nistp521");

  /** Parses an {@code authorized_keys}-style line. Throws {@link IllegalArgumentException}. */
  public static SshPublicKey parse(String input) {
    if (Strings.isBlank(input)) {
      throw new IllegalArgumentException("SSH public key is empty.");
    }
    var parts = input.strip().split("\\s+", 3);
    if (parts.length < 2) {
      throw new IllegalArgumentException(
          "Not a valid SSH public key. Expected '<type> <base64> [comment]'.");
    }
    var type = parts[0];
    var blob = parts[1];
    var comment = parts.length == 3 ? parts[2] : null;
    if (!SUPPORTED_TYPES.contains(type)) {
      throw new IllegalArgumentException(
          "Unsupported SSH key type '" + type + "'. Supported: " + SUPPORTED_TYPES + ".");
    }
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(blob);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("SSH public key blob is not valid base64.");
    }
    if (!declaredTypeMatchesBlob(type, decoded)) {
      throw new IllegalArgumentException(
          "SSH public key blob does not encode the declared type '" + type + "'.");
    }
    return new SshPublicKey(type, blob, comment, fingerprintOf(decoded));
  }

  /** The canonical single-line form for an {@code authorized_keys} entry. */
  public String line() {
    return Strings.isBlank(comment) ? type + " " + blob : type + " " + blob + " " + comment;
  }

  private static boolean declaredTypeMatchesBlob(String type, byte[] blob) {
    if (blob.length < 4) {
      return false;
    }
    var length =
        ((blob[0] & 0xff) << 24)
            | ((blob[1] & 0xff) << 16)
            | ((blob[2] & 0xff) << 8)
            | (blob[3] & 0xff);
    if (length <= 0 || length > 64 || length > blob.length - 4) {
      return false;
    }
    return type.equals(new String(blob, 4, length, StandardCharsets.US_ASCII));
  }

  private static String fingerprintOf(byte[] blob) {
    try {
      var digest = MessageDigest.getInstance("SHA-256").digest(blob);
      return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
