/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.auth;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
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

  private final RelyingParty relyingParty;
  private final FdeStore fdes;
  private final WebauthnCredentialStore credentials;
  private final AuthSessionStore sessions;
  private final PendingChallengeStore challenges;
  private final Duration sessionTtl;

  public PasskeyService(
      RelyingParty relyingParty,
      FdeStore fdes,
      WebauthnCredentialStore credentials,
      AuthSessionStore sessions,
      PendingChallengeStore challenges,
      Duration sessionTtl) {
    this.relyingParty = Objects.requireNonNull(relyingParty, "relyingParty");
    this.fdes = Objects.requireNonNull(fdes, "fdes");
    this.credentials = Objects.requireNonNull(credentials, "credentials");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.challenges = Objects.requireNonNull(challenges, "challenges");
    this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
    if (sessionTtl.isZero() || sessionTtl.isNegative()) {
      throw new IllegalArgumentException("sessionTtl must be positive, got " + sessionTtl);
    }
  }

  @Override
  public Ceremony startRegistration(String fdeHandle) {
    var fde = ActivePerson.named(fdes, fdeHandle);
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
      String challengeId,
      String enrolling,
      byte[] clientDataJson,
      byte[] attestationObject,
      String label) {
    var pending =
        challenges.consume(challengeId, REGISTER).orElseThrow(PasskeyService::registrationUnknown);
    var fde = registering(pending.fdeId(), enrolling);
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
    credentials.save(credential, fde.id(), effectiveLabel(label, credential.aaguid()));
    return new Registration(fde.handle(), credential.credentialId());
  }

  /**
   * The FDE a registration challenge enrolls, while it may act: a ticket finishes only the
   * challenge of the FDE it enrolls ({@code enrolling}), and an admin's registration, with none,
   * any FDE's.
   */
  private FdeStore.Fde registering(String fdeId, String enrolling) {
    var fde = fdes.byId(fdeId).orElseThrow(PasskeyService::registrationUnknown);
    if (enrolling != null && !enrolling.equals(fde.handle())) {
      throw new PasskeyException(
          PasskeyException.Kind.BAD_REQUEST,
          "Registration challenge is not for the FDE this enrollment ticket enrolls.");
    }
    return ActivePerson.of(fde);
  }

  private static PasskeyException registrationUnknown() {
    return new PasskeyException(
        PasskeyException.Kind.BAD_REQUEST, "Registration challenge is unknown or expired.");
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
    var fde =
        ActivePerson.of(fdes.byId(credential.fdeId()).orElseThrow(PasskeyService::loginFailed));
    credentials.recordUse(credentialId, newSignCount);
    var session = sessions.create(fde.id(), sessionTtl);
    return new LoginResult(session.token(), fde.handle(), session.expiresAt());
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

  /**
   * A skipped label prompt falls back to a device-descriptive default (authenticator name plus
   * enrollment date) so {@code sail fde passkey list} can still tell the FDE's credentials apart.
   */
  private static String effectiveLabel(String label, byte[] aaguid) {
    return Strings.isBlank(label) ? Passkeys.defaultLabel(aaguid, DateTimeUtils.now()) : label;
  }

  private static PasskeyException loginFailed() {
    return new PasskeyException(
        PasskeyException.Kind.UNAUTHORIZED, "Passkey authentication failed.");
  }

  private static String displayName(FdeStore.Fde fde) {
    return fde.displayName() == null || fde.displayName().isBlank()
        ? fde.handle()
        : fde.displayName();
  }
}
