/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.webauthn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
}
