/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.auth;

import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.PendingChallengeStore;
import ai.singlr.sail.store.WebauthnCredentialStore;
import ai.singlr.sail.webauthn.RegisteredCredential;
import ai.singlr.sail.webauthn.RelyingParty;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Drives the passkey register and login ceremonies by wiring the pure {@link RelyingParty} verifier
 * to the credential, session, FDE, and challenge stores. The verifier handles the cryptography and
 * W3C ceremony checks; this class owns identity (which FDE), persistence (saving credentials,
 * advancing the clone counter, minting sessions), and the single-use challenge lifecycle. It holds
 * no mutable state, so one instance is shared across all requests.
 */
public final class PasskeyService implements PasskeyCeremonies {

  static final String REGISTER = "register";
  static final String ASSERT = "assert";
  private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
  private static final Duration SESSION_TTL = Duration.ofHours(12);

  private final RelyingParty relyingParty;
  private final FdeStore fdes;
  private final WebauthnCredentialStore credentials;
  private final AuthSessionStore sessions;
  private final PendingChallengeStore challenges;

  public PasskeyService(
      RelyingParty relyingParty,
      FdeStore fdes,
      WebauthnCredentialStore credentials,
      AuthSessionStore sessions,
      PendingChallengeStore challenges) {
    this.relyingParty = Objects.requireNonNull(relyingParty, "relyingParty");
    this.fdes = Objects.requireNonNull(fdes, "fdes");
    this.credentials = Objects.requireNonNull(credentials, "credentials");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.challenges = Objects.requireNonNull(challenges, "challenges");
  }

  @Override
  public Ceremony startRegistration(String fdeHandle) {
    var fde =
        fdes.byHandle(fdeHandle)
            .orElseThrow(
                () ->
                    new PasskeyException(
                        PasskeyException.Kind.NOT_FOUND, "Unknown FDE: " + fdeHandle));
    var existing =
        credentials.listForFde(fde.id()).stream()
            .map(WebauthnCredentialStore.Credential::credentialId)
            .toList();
    var options =
        relyingParty.startRegistration(
            fde.id().getBytes(StandardCharsets.UTF_8), fde.handle(), displayName(fde), existing);
    var challengeId = challenges.issue(REGISTER, options.challenge(), fde.id(), CHALLENGE_TTL);
    return new Ceremony(challengeId, options.publicKey());
  }

  @Override
  public Registration finishRegistration(
      String challengeId, byte[] clientDataJson, byte[] attestationObject, String label) {
    var pending =
        challenges
            .consume(challengeId, REGISTER)
            .orElseThrow(
                () ->
                    new PasskeyException(
                        PasskeyException.Kind.BAD_REQUEST,
                        "Registration challenge is unknown or expired."));
    RegisteredCredential credential;
    try {
      credential =
          relyingParty.finishRegistration(clientDataJson, attestationObject, pending.challenge());
    } catch (IllegalArgumentException e) {
      throw new PasskeyException(PasskeyException.Kind.BAD_REQUEST, e.getMessage());
    }
    if (credentials.findByCredentialId(credential.credentialId()).isPresent()) {
      throw new PasskeyException(
          PasskeyException.Kind.BAD_REQUEST, "This passkey is already registered.");
    }
    credentials.save(credential, pending.fdeId(), label);
    return new Registration(handleOf(pending.fdeId()), credential.credentialId());
  }

  @Override
  public Ceremony startLogin() {
    var options = relyingParty.startAssertion(List.of());
    var challengeId = challenges.issue(ASSERT, options.challenge(), null, CHALLENGE_TTL);
    return new Ceremony(challengeId, options.publicKey());
  }

  @Override
  public LoginResult finishLogin(
      String challengeId,
      byte[] credentialId,
      byte[] clientDataJson,
      byte[] authenticatorData,
      byte[] signature,
      byte[] userHandle) {
    var pending = challenges.consume(challengeId, ASSERT).orElseThrow(PasskeyService::loginFailed);
    var credential =
        credentials.findByCredentialId(credentialId).orElseThrow(PasskeyService::loginFailed);
    if (!userHandleMatches(userHandle, credential.fdeId())) {
      throw loginFailed();
    }
    long newSignCount;
    try {
      newSignCount =
          relyingParty
              .finishAssertion(
                  credential.publicKeyCose(),
                  credential.signCount(),
                  clientDataJson,
                  authenticatorData,
                  signature,
                  pending.challenge())
              .signCount();
    } catch (IllegalArgumentException e) {
      throw loginFailed();
    }
    credentials.recordUse(credentialId, newSignCount);
    var session = sessions.create(credential.fdeId(), SESSION_TTL);
    return new LoginResult(session.token(), handleOf(credential.fdeId()), session.expiresAt());
  }

  /**
   * Defense in depth (WebAuthn §7.2): a discoverable credential's assertion carries the user handle
   * the authenticator holds. When present it must equal the owner of the credential we resolved by
   * id — registration set the handle to {@code fde.id} bytes — so a credential can never be
   * replayed against a different account. Absent (a non-discoverable authenticator) leaves the
   * already-proven credential-id + signature as the binding.
   */
  private static boolean userHandleMatches(byte[] userHandle, String fdeId) {
    if (userHandle == null || userHandle.length == 0) {
      return true;
    }
    return Arrays.equals(userHandle, fdeId.getBytes(StandardCharsets.UTF_8));
  }

  private static PasskeyException loginFailed() {
    return new PasskeyException(
        PasskeyException.Kind.UNAUTHORIZED, "Passkey authentication failed.");
  }

  private String handleOf(String fdeId) {
    return fdes.byId(fdeId).map(FdeStore.Fde::handle).orElse(null);
  }

  private static String displayName(FdeStore.Fde fde) {
    return fde.displayName() == null || fde.displayName().isBlank()
        ? fde.handle()
        : fde.displayName();
  }
}
