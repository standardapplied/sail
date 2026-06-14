/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.auth;

import java.util.Map;

/**
 * The four WebAuthn ceremony steps the API boundary drives: enrolling a passkey for an FDE
 * (register) and authenticating with one (login). Each ceremony is two calls — a {@code start} that
 * issues a challenge and the browser-facing options, and a {@code finish} that verifies the
 * authenticator's response. The API handler depends on this abstraction, not the concrete {@link
 * PasskeyService}, so its parsing and error mapping can be tested without WebAuthn crypto.
 */
public interface PasskeyCeremonies {

  /**
   * Challenge options handed to {@code navigator.credentials}, plus the handle to echo at finish.
   */
  record Ceremony(String challengeId, Map<String, Object> publicKey) {}

  /** The outcome of enrolling a passkey: the FDE it now belongs to and the new credential id. */
  record Registration(String fdeHandle, byte[] credentialId) {}

  /** A minted login session: the plaintext session token (shown once) and the FDE it represents. */
  record LoginResult(String sessionToken, String fdeHandle, String expiresAt) {}

  /**
   * Begins enrolling a passkey for the FDE identified by {@code fdeHandle}. Throws {@link
   * PasskeyException.Kind#NOT_FOUND} when no such FDE exists.
   */
  Ceremony startRegistration(String fdeHandle);

  /** Verifies a registration response and persists the credential against the challenge's FDE. */
  Registration finishRegistration(
      String challengeId, byte[] clientDataJson, byte[] attestationObject, String label);

  /** Begins a discoverable-credential login: issues an assertion challenge and request options. */
  Ceremony startLogin();

  /**
   * Verifies an assertion and, on success, issues a session for the credential's owner. {@code
   * userHandle} is the user id the authenticator returned (may be {@code null} when absent); when
   * present it must identify the same owner as the credential, per WebAuthn §7.2.
   */
  LoginResult finishLogin(
      String challengeId,
      byte[] credentialId,
      byte[] clientDataJson,
      byte[] authenticatorData,
      byte[] signature,
      byte[] userHandle);
}
