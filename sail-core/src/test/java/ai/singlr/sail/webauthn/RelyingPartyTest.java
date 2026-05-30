/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.webauthn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Exercises registration verification against spec-format ceremonies produced by a minimal test
 * authenticator emulator (real P-256 key, real CBOR/JSON wire encoding).
 */
class RelyingPartyTest {

  private static final String RP_ID = "example.com";
  private static final String ORIGIN = "https://example.com";
  private static final byte[] CHALLENGE = new byte[32];
  private static final byte[] CRED_ID = {10, 20, 30, 40, 50};
  private static final byte[] AAGUID = new byte[16];

  static {
    for (var i = 0; i < 32; i++) {
      CHALLENGE[i] = (byte) (i + 1);
    }
  }

  private final RelyingParty rp = new RelyingParty(RP_ID, Set.of(ORIGIN));

  @Test
  void verifiesAndExtractsTheRegisteredCredential() throws Exception {
    var key = newEcKey();
    var attObj = attestationObject(authData(rpIdHash(RP_ID), 0x45, 9, key)); // UP | UV | AT

    var cred =
        rp.finishRegistration(clientJson("webauthn.create", CHALLENGE, ORIGIN), attObj, CHALLENGE);

    assertArrayEquals(CRED_ID, cred.credentialId());
    assertEquals(CoseKey.ES256, cred.coseAlgorithm());
    assertEquals(9, cred.signCount());
    assertArrayEquals(AAGUID, cred.aaguid());
    // the stored COSE bytes re-parse to the same public key
    var reparsed = CoseKey.parse((java.util.Map<?, ?>) Cbor.decode(cred.publicKeyCose()));
    assertArrayEquals(key.getEncoded(), reparsed.publicKey().getEncoded());
  }

  @Test
  void rejectsWrongCeremonyType() throws Exception {
    var attObj = attestationObject(authData(rpIdHash(RP_ID), 0x45, 0, newEcKey()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            rp.finishRegistration(
                clientJson("webauthn.get", CHALLENGE, ORIGIN), attObj, CHALLENGE));
  }

  @Test
  void rejectsChallengeMismatch() throws Exception {
    var attObj = attestationObject(authData(rpIdHash(RP_ID), 0x45, 0, newEcKey()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            rp.finishRegistration(
                clientJson("webauthn.create", CHALLENGE, ORIGIN), attObj, new byte[32]));
  }

  @Test
  void rejectsDisallowedOrigin() throws Exception {
    var attObj = attestationObject(authData(rpIdHash(RP_ID), 0x45, 0, newEcKey()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            rp.finishRegistration(
                clientJson("webauthn.create", CHALLENGE, "https://evil.com"), attObj, CHALLENGE));
  }

  @Test
  void rejectsRpIdHashMismatch() throws Exception {
    var attObj = attestationObject(authData(rpIdHash("other.com"), 0x45, 0, newEcKey()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            rp.finishRegistration(
                clientJson("webauthn.create", CHALLENGE, ORIGIN), attObj, CHALLENGE));
  }

  @Test
  void rejectsMissingUserPresenceAndVerification() throws Exception {
    var noUp = attestationObject(authData(rpIdHash(RP_ID), 0x44, 0, newEcKey())); // UV | AT, no UP
    assertThrows(
        IllegalArgumentException.class,
        () ->
            rp.finishRegistration(
                clientJson("webauthn.create", CHALLENGE, ORIGIN), noUp, CHALLENGE));
    var noUv = attestationObject(authData(rpIdHash(RP_ID), 0x41, 0, newEcKey())); // UP | AT, no UV
    assertThrows(
        IllegalArgumentException.class,
        () ->
            rp.finishRegistration(
                clientJson("webauthn.create", CHALLENGE, ORIGIN), noUv, CHALLENGE));
  }

  @Test
  void rejectsMissingAttestedCredentialData() {
    var authData = cat(rpIdHash(RP_ID), new byte[] {0x05}, be32(0)); // UP | UV, no AT
    var attObj = attestationObject(authData);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            rp.finishRegistration(
                clientJson("webauthn.create", CHALLENGE, ORIGIN), attObj, CHALLENGE));
  }

  @Test
  void rejectsAttestationObjectThatIsNotAMap() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            rp.finishRegistration(
                clientJson("webauthn.create", CHALLENGE, ORIGIN), new byte[] {0x01}, CHALLENGE));
  }

  @Test
  void rejectsAttestationObjectMissingAuthData() {
    var attObj = cat(new byte[] {(byte) 0xa1}, cborText("fmt"), cborText("none"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            rp.finishRegistration(
                clientJson("webauthn.create", CHALLENGE, ORIGIN), attObj, CHALLENGE));
  }

  // --- test authenticator emulator ---------------------------------------------------------

  private static ECPublicKey newEcKey() throws Exception {
    var kpg = KeyPairGenerator.getInstance("EC");
    kpg.initialize(new ECGenParameterSpec("secp256r1"));
    return (ECPublicKey) kpg.generateKeyPair().getPublic();
  }

  private static byte[] authData(byte[] rpIdHash, int flags, long signCount, ECPublicKey key) {
    return cat(
        rpIdHash,
        new byte[] {(byte) flags},
        be32(signCount),
        AAGUID,
        be16(CRED_ID.length),
        CRED_ID,
        coseEc2(fixed32(key.getW().getAffineX()), fixed32(key.getW().getAffineY())));
  }

  private static byte[] attestationObject(byte[] authData) {
    return cat(
        new byte[] {(byte) 0xa3},
        cborText("fmt"),
        cborText("none"),
        cborText("attStmt"),
        new byte[] {(byte) 0xa0},
        cborText("authData"),
        cborBytes(authData));
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
    return cat(new byte[] {(byte) (0x60 | b.length)}, b); // length < 24 for our keys
  }

  private static byte[] cborBytes(byte[] b) {
    if (b.length < 256) {
      return cat(new byte[] {0x58, (byte) b.length}, b);
    }
    return cat(new byte[] {0x59, (byte) (b.length >> 8), (byte) b.length}, b);
  }

  private static byte[] rpIdHash(String rpId) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(rpId.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
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
