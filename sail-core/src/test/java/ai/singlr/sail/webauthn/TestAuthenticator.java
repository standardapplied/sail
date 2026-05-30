/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.webauthn;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * A minimal ES256 WebAuthn authenticator emulator for tests: one real P-256 key pair and one stable
 * credential id, producing spec-format {@code clientDataJSON}, attestation objects, and signed
 * assertions. Because the key pair persists across calls, a {@link #register} response and the
 * {@link #assert} responses that follow form a coherent register-then-login round trip. The
 * signature counter advances on every assertion so the relying party's clone check is satisfied.
 */
public final class TestAuthenticator {

  private static final byte[] AAGUID = new byte[16];

  private final String rpId;
  private final byte[] credentialId;
  private final KeyPair keyPair;
  private final byte[] cose;
  private long signCount;

  public TestAuthenticator(String rpId) {
    this.rpId = rpId;
    this.credentialId = randomBytes(20);
    try {
      var kpg = KeyPairGenerator.getInstance("EC");
      kpg.initialize(new ECGenParameterSpec("secp256r1"));
      this.keyPair = kpg.generateKeyPair();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    var pub = (ECPublicKey) keyPair.getPublic();
    this.cose = coseEc2(fixed32(pub.getW().getAffineX()), fixed32(pub.getW().getAffineY()));
  }

  public byte[] credentialId() {
    return credentialId.clone();
  }

  public record Registration(byte[] clientDataJson, byte[] attestationObject) {}

  public record Assertion(
      byte[] credentialId, byte[] clientDataJson, byte[] authenticatorData, byte[] signature) {}

  /** A registration response (UP | UV | AT set, sign count 0) for the given ceremony. */
  public Registration register(byte[] challenge, String origin) {
    var authData =
        cat(
            rpIdHash(rpId),
            new byte[] {0x45},
            be32(0),
            AAGUID,
            be16(credentialId.length),
            credentialId,
            cose);
    var attestationObject =
        cat(
            new byte[] {(byte) 0xa3},
            cborText("fmt"),
            cborText("none"),
            cborText("attStmt"),
            new byte[] {(byte) 0xa0},
            cborText("authData"),
            cborBytes(authData));
    return new Registration(clientJson("webauthn.create", challenge, origin), attestationObject);
  }

  /**
   * An assertion response (UP | UV set) for the given ceremony, advancing the signature counter.
   */
  public Assertion assertResponse(byte[] challenge, String origin) {
    var authData = cat(rpIdHash(rpId), new byte[] {0x05}, be32(++signCount));
    var clientData = clientJson("webauthn.get", challenge, origin);
    try {
      var signer = Signature.getInstance("SHA256withECDSA");
      signer.initSign(keyPair.getPrivate());
      signer.update(cat(authData, sha256(clientData)));
      return new Assertion(credentialId.clone(), clientData, authData, signer.sign());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static byte[] clientJson(String type, byte[] challenge, String origin) {
    var c = Base64.getUrlEncoder().withoutPadding().encodeToString(challenge);
    return ("{\"type\":\"" + type + "\",\"challenge\":\"" + c + "\",\"origin\":\"" + origin + "\"}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] coseEc2(byte[] x, byte[] y) {
    return cat(
        new byte[] {(byte) 0xa5, 0x01, 0x02, 0x03, 0x26, 0x20, 0x01, 0x21, 0x58, 0x20},
        x,
        new byte[] {0x22, 0x58, 0x20},
        y);
  }

  private static byte[] cborText(String s) {
    var b = s.getBytes(StandardCharsets.UTF_8);
    return cat(new byte[] {(byte) (0x60 | b.length)}, b);
  }

  private static byte[] cborBytes(byte[] b) {
    if (b.length < 256) {
      return cat(new byte[] {0x58, (byte) b.length}, b);
    }
    return cat(new byte[] {0x59, (byte) (b.length >> 8), (byte) b.length}, b);
  }

  private static byte[] rpIdHash(String rpId) {
    return sha256(rpId.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] sha256(byte[] in) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(in);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static byte[] randomBytes(int n) {
    var out = new byte[n];
    new SecureRandom().nextBytes(out);
    return out;
  }

  private static byte[] be16(int v) {
    return new byte[] {(byte) (v >> 8), (byte) v};
  }

  private static byte[] be32(long v) {
    return new byte[] {(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
  }

  private static byte[] fixed32(BigInteger v) {
    var raw = v.toByteArray();
    var out = new byte[32];
    if (raw.length > 32) {
      System.arraycopy(raw, raw.length - 32, out, 0, 32);
    } else {
      System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
    }
    return out;
  }

  private static byte[] cat(byte[]... parts) {
    var out = new ByteArrayOutputStream();
    for (var p : parts) {
      out.writeBytes(p);
    }
    return out.toByteArray();
  }
}
