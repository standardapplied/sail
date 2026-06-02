/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.ssh;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** Builds well-formed {@code ssh-ed25519} public-key lines for tests (no real key pair needed). */
public final class TestSshKeys {

  private TestSshKeys() {}

  /**
   * A valid ed25519 line whose 32-byte key body is derived from {@code seed} (distinct per seed).
   */
  public static String ed25519(String seed, String comment) {
    return line(digest(seed), comment);
  }

  private static String line(byte[] pub32, String comment) {
    var out = new ByteArrayOutputStream();
    sshString(out, "ssh-ed25519".getBytes(StandardCharsets.US_ASCII));
    sshString(out, pub32);
    var blob = Base64.getEncoder().encodeToString(out.toByteArray());
    return comment == null ? "ssh-ed25519 " + blob : "ssh-ed25519 " + blob + " " + comment;
  }

  private static void sshString(ByteArrayOutputStream out, byte[] bytes) {
    out.write(bytes.length >>> 24);
    out.write(bytes.length >>> 16);
    out.write(bytes.length >>> 8);
    out.write(bytes.length);
    out.writeBytes(bytes);
  }

  private static byte[] digest(String seed) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
