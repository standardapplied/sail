/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.webauthn;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.security.spec.KeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Map;

/**
 * A COSE_Key (RFC 9052) — the credential public key an authenticator registers — decoded into a
 * usable {@link PublicKey} plus its COSE signature algorithm. Supports exactly the algorithms real
 * passkey authenticators use: ES256 (EC P-256), RS256 (RSA), and EdDSA (Ed25519). Any other key
 * type, algorithm, curve, or key-type/algorithm mismatch is rejected.
 *
 * <p>EC points are explicitly checked to lie on the P-256 curve and Ed25519 {@code y} within the
 * field, because the JDK key factories do not validate this — accepting an off-curve or
 * out-of-range point would open an invalid-curve attack.
 */
public record CoseKey(PublicKey publicKey, long algorithm) {

  public static final long ES256 = -7;
  public static final long RS256 = -257;
  public static final long EDDSA = -8;

  private static final long LABEL_KTY = 1;
  private static final long LABEL_ALG = 3;
  private static final long LABEL_CRV = -1;
  private static final long LABEL_X_OR_N = -2;
  private static final long LABEL_Y_OR_E = -3;

  private static final int KTY_OKP = 1;
  private static final int KTY_EC2 = 2;
  private static final int KTY_RSA = 3;

  private static final long CRV_P256 = 1;
  private static final long CRV_ED25519 = 6;
  private static final int P256_COORD_LEN = 32;
  private static final int ED25519_KEY_LEN = 32;

  private static final BigInteger P256_P =
      new BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16);
  private static final BigInteger P256_A = P256_P.subtract(BigInteger.valueOf(3));
  private static final BigInteger P256_B =
      new BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16);
  private static final BigInteger P256_N =
      new BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16);
  private static final ECPoint P256_GENERATOR =
      new ECPoint(
          new BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16),
          new BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16));
  private static final ECParameterSpec P256 =
      new ECParameterSpec(
          new EllipticCurve(new ECFieldFp(P256_P), P256_A, P256_B), P256_GENERATOR, P256_N, 1);

  private static final BigInteger ED25519_P =
      BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));

  /** Decodes a COSE key map (as produced by {@link Cbor}) into a public key and its algorithm. */
  public static CoseKey parse(Map<Object, Object> cose) {
    var kty = readLong(cose, LABEL_KTY, "kty");
    var alg = readLong(cose, LABEL_ALG, "alg");
    var key =
        switch (Math.toIntExact(kty)) {
          case KTY_EC2 -> ec2(cose, alg);
          case KTY_RSA -> rsa(cose, alg);
          case KTY_OKP -> okp(cose, alg);
          default -> throw new IllegalArgumentException("Unsupported COSE key type (kty): " + kty);
        };
    return new CoseKey(key, alg);
  }

  /** The JDK {@link java.security.Signature} algorithm name for this key's COSE algorithm. */
  public String jdkSignatureAlgorithm() {
    return switch (Math.toIntExact(algorithm)) {
      case (int) ES256 -> "SHA256withECDSA";
      case (int) RS256 -> "SHA256withRSA";
      case (int) EDDSA -> "Ed25519";
      default -> throw new IllegalArgumentException("Unsupported COSE algorithm: " + algorithm);
    };
  }

  private static PublicKey ec2(Map<Object, Object> cose, long alg) {
    if (alg != ES256) {
      throw new IllegalArgumentException("EC2 key requires alg ES256 (-7), got " + alg);
    }
    if (readLong(cose, LABEL_CRV, "crv") != CRV_P256) {
      throw new IllegalArgumentException("EC2 key curve must be P-256");
    }
    var x = new BigInteger(1, coordinate(cose, LABEL_X_OR_N, "x"));
    var y = new BigInteger(1, coordinate(cose, LABEL_Y_OR_E, "y"));
    requireOnP256Curve(x, y);
    return generate("EC", new ECPublicKeySpec(new ECPoint(x, y), P256));
  }

  private static PublicKey rsa(Map<Object, Object> cose, long alg) {
    if (alg != RS256) {
      throw new IllegalArgumentException("RSA key requires alg RS256 (-257), got " + alg);
    }
    var n = new BigInteger(1, readBytes(cose, LABEL_CRV, "n"));
    var e = new BigInteger(1, readBytes(cose, LABEL_X_OR_N, "e"));
    return generate("RSA", new RSAPublicKeySpec(n, e));
  }

  private static PublicKey okp(Map<Object, Object> cose, long alg) {
    if (alg != EDDSA) {
      throw new IllegalArgumentException("OKP key requires alg EdDSA (-8), got " + alg);
    }
    if (readLong(cose, LABEL_CRV, "crv") != CRV_ED25519) {
      throw new IllegalArgumentException("OKP key curve must be Ed25519");
    }
    var raw = readBytes(cose, LABEL_X_OR_N, "x");
    if (raw.length != ED25519_KEY_LEN) {
      throw new IllegalArgumentException("Ed25519 public key must be 32 bytes, got " + raw.length);
    }
    var xOdd = (raw[ED25519_KEY_LEN - 1] & 0x80) != 0;
    var yLittleEndian = raw.clone();
    yLittleEndian[ED25519_KEY_LEN - 1] &= 0x7f;
    var y = new BigInteger(1, reversed(yLittleEndian));
    if (y.compareTo(ED25519_P) >= 0) {
      throw new IllegalArgumentException("Ed25519 y coordinate out of field range");
    }
    return generate(
        "Ed25519", new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(xOdd, y)));
  }

  private static void requireOnP256Curve(BigInteger x, BigInteger y) {
    // x and y come from BigInteger(1, ...) so are never negative; only the upper bound can fail.
    if (x.compareTo(P256_P) >= 0 || y.compareTo(P256_P) >= 0) {
      throw new IllegalArgumentException("EC2 coordinate out of field range");
    }
    var lhs = y.multiply(y).mod(P256_P);
    var rhs = x.multiply(x).multiply(x).add(P256_A.multiply(x)).add(P256_B).mod(P256_P);
    if (!lhs.equals(rhs)) {
      throw new IllegalArgumentException("EC2 point is not on the P-256 curve");
    }
  }

  private static PublicKey generate(String algorithm, KeySpec spec) {
    try {
      return KeyFactory.getInstance(algorithm).generatePublic(spec);
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("Invalid " + algorithm + " public key", e);
    }
  }

  private static byte[] coordinate(Map<Object, Object> cose, long label, String name) {
    var bytes = readBytes(cose, label, name);
    if (bytes.length != P256_COORD_LEN) {
      throw new IllegalArgumentException(
          "P-256 " + name + " coordinate must be 32 bytes, got " + bytes.length);
    }
    return bytes;
  }

  private static long readLong(Map<Object, Object> cose, long label, String name) {
    if (cose.get(label) instanceof Long value) {
      return value;
    }
    throw new IllegalArgumentException(
        "COSE key missing integer " + name + " (label " + label + ")");
  }

  private static byte[] readBytes(Map<Object, Object> cose, long label, String name) {
    if (cose.get(label) instanceof byte[] value) {
      return value;
    }
    throw new IllegalArgumentException(
        "COSE key missing byte string " + name + " (label " + label + ")");
  }

  private static byte[] reversed(byte[] input) {
    var out = new byte[input.length];
    for (var i = 0; i < input.length; i++) {
      out[i] = input[input.length - 1 - i];
    }
    return out;
  }
}
