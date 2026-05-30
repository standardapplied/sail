/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.webauthn;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Verifies WebAuthn ceremonies for one Relying Party (one {@code rpId} and its allowed origins),
 * implementing the W3C WebAuthn §7.1 registration steps for passwordless passkeys. Attestation is
 * not verified (we request {@code none}); trust comes from the registration being an authenticated,
 * user-verifying ceremony, not from an attestation chain.
 */
public final class RelyingParty {

  private final Set<String> origins;
  private final byte[] rpIdHash;

  public RelyingParty(String rpId, Set<String> origins) {
    Objects.requireNonNull(rpId, "rpId");
    this.origins = Set.copyOf(origins);
    this.rpIdHash = Hashes.sha256(rpId.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Verifies a registration response and returns the credential to persist. Throws {@link
   * IllegalArgumentException} if any check fails.
   *
   * @param clientDataJson the raw {@code clientDataJSON} bytes
   * @param attestationObject the raw CBOR attestation object
   * @param expectedChallenge the challenge issued for this ceremony
   */
  public RegisteredCredential finishRegistration(
      byte[] clientDataJson, byte[] attestationObject, byte[] expectedChallenge) {
    var clientData = ClientData.parse(clientDataJson);
    if (!ClientData.TYPE_CREATE.equals(clientData.type())) {
      throw new IllegalArgumentException(
          "clientDataJSON type must be " + ClientData.TYPE_CREATE + ", got " + clientData.type());
    }
    if (!MessageDigest.isEqual(clientData.challenge(), expectedChallenge)) {
      throw new IllegalArgumentException("Registration challenge mismatch");
    }
    if (!origins.contains(clientData.origin())) {
      throw new IllegalArgumentException("Registration origin not allowed: " + clientData.origin());
    }

    if (!(Cbor.decode(attestationObject) instanceof Map<?, ?> attestation)) {
      throw new IllegalArgumentException("Attestation object is not a CBOR map");
    }
    if (!(attestation.get("authData") instanceof byte[] authDataBytes)) {
      throw new IllegalArgumentException("Attestation object missing authData");
    }
    var authData = AuthenticatorData.parse(authDataBytes);
    if (!MessageDigest.isEqual(authData.rpIdHash(), rpIdHash)) {
      throw new IllegalArgumentException("Registration rpIdHash mismatch");
    }
    if (!authData.userPresent()) {
      throw new IllegalArgumentException("User presence (UP) flag not set");
    }
    if (!authData.userVerified()) {
      throw new IllegalArgumentException("User verification (UV) flag not set");
    }
    var attested = authData.attestedCredential();
    if (attested == null) {
      throw new IllegalArgumentException("Registration has no attested credential data");
    }
    return new RegisteredCredential(
        attested.credentialId(),
        attested.publicKeyCose(),
        attested.publicKey().algorithm(),
        authData.signCount(),
        attested.aaguid(),
        authData.backupEligible(),
        authData.backupState());
  }

  /**
   * Verifies an assertion (W3C WebAuthn §7.2) against the credential the caller looked up by
   * credential id. Returns the new signature counter (to persist) or throws if any check fails.
   *
   * @param credentialPublicKeyCose the stored COSE public key (as returned at registration)
   * @param storedSignCount the signature counter persisted from the last ceremony
   * @param clientDataJson the raw {@code clientDataJSON} bytes
   * @param authenticatorData the raw authenticator data
   * @param signature the authenticator's signature over {@code authenticatorData ‖ clientDataHash}
   * @param expectedChallenge the challenge issued for this ceremony
   */
  public AssertionResult finishAssertion(
      byte[] credentialPublicKeyCose,
      long storedSignCount,
      byte[] clientDataJson,
      byte[] authenticatorData,
      byte[] signature,
      byte[] expectedChallenge) {
    var clientData = ClientData.parse(clientDataJson);
    if (!ClientData.TYPE_GET.equals(clientData.type())) {
      throw new IllegalArgumentException(
          "clientDataJSON type must be " + ClientData.TYPE_GET + ", got " + clientData.type());
    }
    if (!MessageDigest.isEqual(clientData.challenge(), expectedChallenge)) {
      throw new IllegalArgumentException("Assertion challenge mismatch");
    }
    if (!origins.contains(clientData.origin())) {
      throw new IllegalArgumentException("Assertion origin not allowed: " + clientData.origin());
    }

    var authData = AuthenticatorData.parse(authenticatorData);
    if (!MessageDigest.isEqual(authData.rpIdHash(), rpIdHash)) {
      throw new IllegalArgumentException("Assertion rpIdHash mismatch");
    }
    if (!authData.userPresent()) {
      throw new IllegalArgumentException("User presence (UP) flag not set");
    }
    if (!authData.userVerified()) {
      throw new IllegalArgumentException("User verification (UV) flag not set");
    }

    if (!(Cbor.decode(credentialPublicKeyCose) instanceof Map<?, ?> coseMap)) {
      throw new IllegalArgumentException("Stored credential public key is not a CBOR map");
    }
    var key = CoseKey.parse(coseMap);
    if (!verifySignature(key, concat(authenticatorData, clientData.hash()), signature)) {
      throw new IllegalArgumentException("Assertion signature is invalid");
    }

    var newSignCount = authData.signCount();
    if ((newSignCount != 0 || storedSignCount != 0) && newSignCount <= storedSignCount) {
      throw new IllegalArgumentException(
          "Signature counter did not increase (possible cloned authenticator)");
    }
    return new AssertionResult(newSignCount, authData.userVerified(), authData.backupState());
  }

  private static boolean verifySignature(CoseKey key, byte[] signedData, byte[] signature) {
    try {
      var verifier = Signature.getInstance(key.jdkSignatureAlgorithm());
      verifier.initVerify(key.publicKey());
      verifier.update(signedData);
      return verifier.verify(signature);
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("Could not verify assertion signature", e);
    }
  }

  private static byte[] concat(byte[] a, byte[] b) {
    var out = new ByteArrayOutputStream(a.length + b.length);
    out.writeBytes(a);
    out.writeBytes(b);
    return out.toByteArray();
  }
}
