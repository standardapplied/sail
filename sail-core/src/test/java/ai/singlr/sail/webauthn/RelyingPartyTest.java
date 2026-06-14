/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.webauthn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
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

  private final RelyingParty rp = new RelyingParty(RP_ID, "Sail", Set.of(ORIGIN));

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

  // --- ceremony options ----------------------------------------------------------------------

  @Test
  void startRegistrationProducesCreationOptions() {
    var opts =
        rp.startRegistration(
            "uid".getBytes(StandardCharsets.UTF_8), "uday", "Uday", java.util.List.of());
    var pk = opts.publicKey();
    assertEquals(java.util.Map.of("id", RP_ID, "name", "Sail"), pk.get("rp"));
    var user = (java.util.Map<?, ?>) pk.get("user");
    assertEquals("uday", user.get("name"));
    assertEquals("Uday", user.get("displayName"));
    assertEquals("none", pk.get("attestation"));
    var sel = (java.util.Map<?, ?>) pk.get("authenticatorSelection");
    assertEquals("required", sel.get("residentKey"));
    assertEquals("required", sel.get("userVerification"));
    var params = (java.util.List<?>) pk.get("pubKeyCredParams");
    assertEquals(3, params.size());
    assertFalse(pk.containsKey("excludeCredentials"));
    // the challenge field round-trips to the retained raw challenge
    assertArrayEquals(
        opts.challenge(), Base64.getUrlDecoder().decode((String) pk.get("challenge")));
    assertEquals(32, opts.challenge().length);
    // serializes and re-parses as JSON
    assertEquals(RP_ID, ((java.util.Map<?, ?>) parseJson(opts.json()).get("rp")).get("id"));
  }

  @Test
  void startRegistrationIncludesExcludeCredentials() {
    var opts =
        rp.startRegistration(
            "uid".getBytes(StandardCharsets.UTF_8), "u", "U", java.util.List.of(CRED_ID));
    var excluded = (java.util.List<?>) opts.publicKey().get("excludeCredentials");
    assertEquals(1, excluded.size());
    var d = (java.util.Map<?, ?>) excluded.get(0);
    assertEquals("public-key", d.get("type"));
    assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(CRED_ID), d.get("id"));
  }

  @Test
  void startAssertionProducesRequestOptions() {
    var opts = rp.startAssertion(java.util.List.of());
    var pk = opts.publicKey();
    assertEquals(RP_ID, pk.get("rpId"));
    assertEquals("required", pk.get("userVerification"));
    assertFalse(pk.containsKey("allowCredentials"));
    assertArrayEquals(
        opts.challenge(), Base64.getUrlDecoder().decode((String) pk.get("challenge")));
  }

  @Test
  void startAssertionIncludesAllowCredentials() {
    var opts = rp.startAssertion(java.util.List.of(CRED_ID));
    assertEquals(1, ((java.util.List<?>) opts.publicKey().get("allowCredentials")).size());
  }

  @Test
  void challengesAreRandomPerCeremony() {
    assertFalse(
        java.util.Arrays.equals(
            rp.startAssertion(java.util.List.of()).challenge(),
            rp.startAssertion(java.util.List.of()).challenge()));
  }

  private static java.util.Map<String, Object> parseJson(String json) {
    return ai.singlr.sail.config.YamlUtil.parseMap(json);
  }

  // --- assertion verification ----------------------------------------------------------------

  @Test
  void verifiesValidAssertionAndReturnsNewSignCount() throws Exception {
    var a = signEc(0x05, 11, CHALLENGE, ORIGIN); // UP | UV
    var result = rp.finishAssertion(a.cose, 10, a.clientData, a.authData, a.signature, CHALLENGE);
    assertEquals(11, result.signCount());
    assertTrue(result.userVerified());
  }

  @Test
  void acceptsZeroSignCountWhenStoredIsZero() throws Exception {
    var a = signEc(0x05, 0, CHALLENGE, ORIGIN);
    assertEquals(
        0,
        rp.finishAssertion(a.cose, 0, a.clientData, a.authData, a.signature, CHALLENGE)
            .signCount());
  }

  @Test
  void rejectsNonIncreasingSignCountAsClone() throws Exception {
    var a = signEc(0x05, 5, CHALLENGE, ORIGIN);
    assertThrows(
        IllegalArgumentException.class,
        () -> rp.finishAssertion(a.cose, 5, a.clientData, a.authData, a.signature, CHALLENGE));
  }

  @Test
  void rejectsRegressionToZeroSignCount() throws Exception {
    var a = signEc(0x05, 0, CHALLENGE, ORIGIN); // authenticator reports 0
    assertThrows(
        IllegalArgumentException.class,
        () -> rp.finishAssertion(a.cose, 5, a.clientData, a.authData, a.signature, CHALLENGE));
  }

  @Test
  void rejectsAssertionWrongType() throws Exception {
    var a = sign(0x05, 1, CHALLENGE, ORIGIN, "webauthn.create");
    assertThrows(
        IllegalArgumentException.class,
        () -> rp.finishAssertion(a.cose, 0, a.clientData, a.authData, a.signature, CHALLENGE));
  }

  @Test
  void rejectsAssertionChallengeOriginRpIdAndFlags() throws Exception {
    var ok = signEc(0x05, 1, CHALLENGE, ORIGIN);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            rp.finishAssertion(ok.cose, 0, ok.clientData, ok.authData, ok.signature, new byte[32]));

    var badOrigin = signEc(0x05, 1, CHALLENGE, "https://evil.com");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            rp.finishAssertion(
                badOrigin.cose,
                0,
                badOrigin.clientData,
                badOrigin.authData,
                badOrigin.signature,
                CHALLENGE));

    var badRp = signEc(0x05, 1, CHALLENGE, ORIGIN, "other.com");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            rp.finishAssertion(
                badRp.cose, 0, badRp.clientData, badRp.authData, badRp.signature, CHALLENGE));

    var noUv = signEc(0x01, 1, CHALLENGE, ORIGIN); // UP only
    assertThrows(
        IllegalArgumentException.class,
        () ->
            rp.finishAssertion(
                noUv.cose, 0, noUv.clientData, noUv.authData, noUv.signature, CHALLENGE));

    var noUp = signEc(0x04, 1, CHALLENGE, ORIGIN); // UV only
    assertThrows(
        IllegalArgumentException.class,
        () ->
            rp.finishAssertion(
                noUp.cose, 0, noUp.clientData, noUp.authData, noUp.signature, CHALLENGE));
  }

  @Test
  void rejectsSignatureOverTamperedData() throws Exception {
    var a = signEc(0x05, 1, CHALLENGE, ORIGIN);
    // same parsed fields, different bytes -> different clientDataHash -> signature no longer
    // matches
    var tampered =
        new String(a.clientData, StandardCharsets.UTF_8)
            .replace("{", "{ ")
            .getBytes(StandardCharsets.UTF_8);
    assertThrows(
        IllegalArgumentException.class,
        () -> rp.finishAssertion(a.cose, 0, tampered, a.authData, a.signature, CHALLENGE));
  }

  @Test
  void rejectsMalformedSignatureBytes() throws Exception {
    var a = signEc(0x05, 1, CHALLENGE, ORIGIN);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            rp.finishAssertion(
                a.cose, 0, a.clientData, a.authData, new byte[] {1, 2, 3}, CHALLENGE));
  }

  @Test
  void rejectsStoredKeyThatIsNotACborMap() throws Exception {
    var a = signEc(0x05, 1, CHALLENGE, ORIGIN);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            rp.finishAssertion(
                new byte[] {0x01}, 0, a.clientData, a.authData, a.signature, CHALLENGE));
  }

  private record Signed(byte[] cose, byte[] clientData, byte[] authData, byte[] signature) {}

  private static Signed signEc(int flags, long signCount, byte[] challenge, String origin)
      throws Exception {
    return sign(flags, signCount, challenge, origin, "webauthn.get", RP_ID);
  }

  private static Signed signEc(
      int flags, long signCount, byte[] challenge, String origin, String rpId) throws Exception {
    return sign(flags, signCount, challenge, origin, "webauthn.get", rpId);
  }

  private static Signed sign(
      int flags, long signCount, byte[] challenge, String origin, String type) throws Exception {
    return sign(flags, signCount, challenge, origin, type, RP_ID);
  }

  private static Signed sign(
      int flags, long signCount, byte[] challenge, String origin, String type, String rpId)
      throws Exception {
    var kpg = KeyPairGenerator.getInstance("EC");
    kpg.initialize(new ECGenParameterSpec("secp256r1"));
    var kp = kpg.generateKeyPair();
    var pub = (ECPublicKey) kp.getPublic();
    var cose = coseEc2(fixed32(pub.getW().getAffineX()), fixed32(pub.getW().getAffineY()));
    var clientData = clientJson(type, challenge, origin);
    var authData = cat(rpIdHash(rpId), new byte[] {(byte) flags}, be32(signCount));
    var signedOver = cat(authData, sha256(clientData));
    var signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(kp.getPrivate());
    signer.update(signedOver);
    return new Signed(cose, clientData, authData, signer.sign());
  }

  private static byte[] sha256(byte[] in) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(in);
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
