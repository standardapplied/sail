/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.webauthn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoseKeyTest {

  private static Map<Object, Object> cose(Object... kv) {
    var m = new LinkedHashMap<Object, Object>();
    for (var i = 0; i < kv.length; i += 2) {
      m.put(kv[i], kv[i + 1]);
    }
    return m;
  }

  private static byte[] fixed(BigInteger v, int len) {
    var raw = v.toByteArray();
    if (raw.length == len) {
      return raw;
    }
    var out = new byte[len];
    if (raw.length > len) {
      System.arraycopy(raw, raw.length - len, out, 0, len); // drop sign byte
    } else {
      System.arraycopy(raw, 0, out, len - raw.length, raw.length); // left-pad
    }
    return out;
  }

  @Test
  void es256RoundTrips() throws Exception {
    var kpg = KeyPairGenerator.getInstance("EC");
    kpg.initialize(new ECGenParameterSpec("secp256r1"));
    var pub = (ECPublicKey) kpg.generateKeyPair().getPublic();
    var map =
        cose(
            1L,
            2L,
            3L,
            CoseKey.ES256,
            -1L,
            1L,
            -2L,
            fixed(pub.getW().getAffineX(), 32),
            -3L,
            fixed(pub.getW().getAffineY(), 32));

    var parsed = CoseKey.parse(map);
    assertEquals(CoseKey.ES256, parsed.algorithm());
    assertEquals("SHA256withECDSA", parsed.jdkSignatureAlgorithm());
    assertArrayEquals(pub.getEncoded(), parsed.publicKey().getEncoded());
  }

  @Test
  void rs256RoundTrips() throws Exception {
    var kpg = KeyPairGenerator.getInstance("RSA");
    kpg.initialize(2048);
    var pub = (RSAPublicKey) kpg.generateKeyPair().getPublic();
    var map =
        cose(
            1L,
            3L,
            3L,
            CoseKey.RS256,
            -1L,
            pub.getModulus().toByteArray(),
            -2L,
            pub.getPublicExponent().toByteArray());

    var parsed = CoseKey.parse(map);
    assertEquals(CoseKey.RS256, parsed.algorithm());
    assertEquals("SHA256withRSA", parsed.jdkSignatureAlgorithm());
    assertArrayEquals(pub.getEncoded(), parsed.publicKey().getEncoded());
  }

  @Test
  void eddsaRoundTrips() throws Exception {
    var pub = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
    var encoded = pub.getEncoded();
    var raw = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
    var map = cose(1L, 1L, 3L, CoseKey.EDDSA, -1L, 6L, -2L, raw);

    var parsed = CoseKey.parse(map);
    assertEquals(CoseKey.EDDSA, parsed.algorithm());
    assertEquals("Ed25519", parsed.jdkSignatureAlgorithm());
    assertArrayEquals(pub.getEncoded(), parsed.publicKey().getEncoded());
  }

  @Test
  void eddsaAcceptsBothPointSigns() throws Exception {
    var pub = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
    var enc = pub.getEncoded();
    var raw = Arrays.copyOfRange(enc, enc.length - 32, enc.length);
    var even = raw.clone();
    even[31] &= 0x7f; // x-sign bit clear
    var odd = raw.clone();
    odd[31] |= 0x80; // x-sign bit set
    assertDoesNotThrow(() -> CoseKey.parse(cose(1L, 1L, 3L, CoseKey.EDDSA, -1L, 6L, -2L, even)));
    assertDoesNotThrow(() -> CoseKey.parse(cose(1L, 1L, 3L, CoseKey.EDDSA, -1L, 6L, -2L, odd)));
  }

  @Test
  void rejectsUnsupportedKeyType() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> CoseKey.parse(cose(1L, 9L, 3L, CoseKey.ES256)));
    assertTrue(ex.getMessage().contains("key type"));
  }

  @Test
  void rejectsMissingOrNonIntegerLabels() {
    assertThrows(IllegalArgumentException.class, () -> CoseKey.parse(cose(3L, CoseKey.ES256)));
    assertThrows(
        IllegalArgumentException.class, () -> CoseKey.parse(cose(1L, "two", 3L, CoseKey.ES256)));
  }

  @Test
  void ec2RejectsWrongAlgCurveAndCoordinateLength() {
    assertThrows(
        IllegalArgumentException.class, () -> CoseKey.parse(cose(1L, 2L, 3L, CoseKey.RS256)));
    assertThrows(
        IllegalArgumentException.class,
        () -> CoseKey.parse(cose(1L, 2L, 3L, CoseKey.ES256, -1L, 2L)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CoseKey.parse(
                cose(1L, 2L, 3L, CoseKey.ES256, -1L, 1L, -2L, new byte[31], -3L, new byte[32])));
  }

  @Test
  void rsaRejectsUndersizedModulus() throws Exception {
    var kpg = KeyPairGenerator.getInstance("RSA");
    kpg.initialize(1024);
    var pub = (RSAPublicKey) kpg.generateKeyPair().getPublic();
    var map =
        cose(
            1L,
            3L,
            3L,
            CoseKey.RS256,
            -1L,
            pub.getModulus().toByteArray(),
            -2L,
            pub.getPublicExponent().toByteArray());

    var thrown = assertThrows(IllegalArgumentException.class, () -> CoseKey.parse(map));
    assertTrue(thrown.getMessage().contains("modulus"));
  }

  @Test
  void rsaRejectsEvenExponent() throws Exception {
    var kpg = KeyPairGenerator.getInstance("RSA");
    kpg.initialize(2048);
    var pub = (RSAPublicKey) kpg.generateKeyPair().getPublic();
    var map =
        cose(
            1L, 3L, 3L, CoseKey.RS256, -1L, pub.getModulus().toByteArray(), -2L, new byte[] {0x04});

    assertThrows(IllegalArgumentException.class, () -> CoseKey.parse(map));
  }

  @Test
  void rsaRejectsWrongAlg() {
    assertThrows(
        IllegalArgumentException.class, () -> CoseKey.parse(cose(1L, 3L, 3L, CoseKey.ES256)));
  }

  @Test
  void okpRejectsWrongAlgCurveAndLength() {
    assertThrows(
        IllegalArgumentException.class, () -> CoseKey.parse(cose(1L, 1L, 3L, CoseKey.ES256)));
    assertThrows(
        IllegalArgumentException.class,
        () -> CoseKey.parse(cose(1L, 1L, 3L, CoseKey.EDDSA, -1L, 1L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> CoseKey.parse(cose(1L, 1L, 3L, CoseKey.EDDSA, -1L, 6L, -2L, new byte[31])));
  }

  @Test
  void missingByteStringCoordinateRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CoseKey.parse(cose(1L, 2L, 3L, CoseKey.ES256, -1L, 1L)));
  }

  @Test
  void ec2RejectsCoordinatesOutsideTheField() {
    var tooBig = new byte[32];
    Arrays.fill(tooBig, (byte) 0xff); // 2^256-1 > P-256 prime
    var valid = new byte[32]; // 0 < prime
    assertThrows(
        IllegalArgumentException.class,
        () -> CoseKey.parse(cose(1L, 2L, 3L, CoseKey.ES256, -1L, 1L, -2L, tooBig, -3L, valid)));
    assertThrows(
        IllegalArgumentException.class,
        () -> CoseKey.parse(cose(1L, 2L, 3L, CoseKey.ES256, -1L, 1L, -2L, valid, -3L, tooBig)));
  }

  @Test
  void jdkSignatureAlgorithmRejectsUnknown() {
    var key = new CoseKey(null, 999L);
    assertThrows(IllegalArgumentException.class, key::jdkSignatureAlgorithm);
  }

  @Test
  void malformedKeyMaterialFailsClosed() {
    var ec2 = cose(1L, 2L, 3L, CoseKey.ES256, -1L, 1L, -2L, new byte[32], -3L, new byte[32]);
    assertThrows(IllegalArgumentException.class, () -> CoseKey.parse(ec2));

    var rsa = cose(1L, 3L, 3L, CoseKey.RS256, -1L, new byte[0], -2L, new byte[] {1});
    assertThrows(IllegalArgumentException.class, () -> CoseKey.parse(rsa));

    var allOnes = new byte[32];
    Arrays.fill(allOnes, (byte) 0xff);
    var okp = cose(1L, 1L, 3L, CoseKey.EDDSA, -1L, 6L, -2L, allOnes);
    assertThrows(IllegalArgumentException.class, () -> CoseKey.parse(okp));
  }
}
