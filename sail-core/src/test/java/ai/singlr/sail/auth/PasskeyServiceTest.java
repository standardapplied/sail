/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.auth;

import static org.junit.jupiter.api.Assertions.*;

import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.PendingChallengeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.WebauthnCredentialStore;
import ai.singlr.sail.webauthn.RelyingParty;
import ai.singlr.sail.webauthn.TestAuthenticator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PasskeyServiceTest {

  private static final String RP_ID = "sail.acme.dev";
  private static final String ORIGIN = "https://sail.acme.dev";

  @TempDir Path tempDir;
  private Sqlite db;
  private FdeStore fdes;
  private WebauthnCredentialStore credentials;
  private AuthSessionStore sessions;
  private PasskeyService service;
  private TestAuthenticator authenticator;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    fdes = new FdeStore(db);
    credentials = new WebauthnCredentialStore(db);
    sessions = new AuthSessionStore(db);
    fdes.add("uday", null, null);
    service =
        new PasskeyService(
            new RelyingParty(RP_ID, "Sail", Set.of(ORIGIN)),
            fdes,
            credentials,
            sessions,
            new PendingChallengeStore(db));
    authenticator = new TestAuthenticator(RP_ID);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private static byte[] challengeOf(PasskeyCeremonies.Ceremony ceremony) {
    return Base64.getUrlDecoder().decode((String) ceremony.publicKey().get("challenge"));
  }

  private PasskeyCeremonies.Registration enroll(String handle) {
    var ceremony = service.startRegistration(handle);
    var response = authenticator.register(challengeOf(ceremony), ORIGIN);
    return service.finishRegistration(
        ceremony.challengeId(), response.clientDataJson(), response.attestationObject(), "laptop");
  }

  @Test
  void registerThenLoginRoundTrip() {
    var registration = enroll("uday");
    assertEquals("uday", registration.fdeHandle());
    assertArrayEquals(authenticator.credentialId(), registration.credentialId());

    var login = service.startLogin();
    var assertion = authenticator.assertResponse(challengeOf(login), ORIGIN);
    var result =
        service.finishLogin(
            login.challengeId(),
            assertion.credentialId(),
            assertion.clientDataJson(),
            assertion.authenticatorData(),
            assertion.signature(),
            null);

    assertEquals("uday", result.fdeHandle());
    assertTrue(result.sessionToken().startsWith("sess_"));
    assertTrue(sessions.validate(result.sessionToken()).isPresent());
  }

  @Test
  void twoLoginsAdvanceSignCounter() {
    enroll("uday");
    for (var i = 0; i < 2; i++) {
      var login = service.startLogin();
      var assertion = authenticator.assertResponse(challengeOf(login), ORIGIN);
      service.finishLogin(
          login.challengeId(),
          assertion.credentialId(),
          assertion.clientDataJson(),
          assertion.authenticatorData(),
          assertion.signature(),
          null);
    }
    assertEquals(
        2, credentials.findByCredentialId(authenticator.credentialId()).orElseThrow().signCount());
  }

  @Test
  void startRegistrationRejectsUnknownFde() {
    var e = assertThrows(PasskeyException.class, () -> service.startRegistration("ghost"));
    assertEquals(PasskeyException.Kind.NOT_FOUND, e.kind());
  }

  @Test
  void startRegistrationUsesDisplayNameThenHandle() {
    fdes.add("alice", "Alice Liddell", null);
    var withName = service.startRegistration("alice");
    assertEquals(
        "Alice Liddell", ((Map<?, ?>) withName.publicKey().get("user")).get("displayName"));
    var withoutName = service.startRegistration("uday");
    assertEquals("uday", ((Map<?, ?>) withoutName.publicKey().get("user")).get("displayName"));
  }

  @Test
  void secondRegistrationExcludesEnrolledCredential() {
    enroll("uday");
    var ceremony = service.startRegistration("uday");
    assertTrue(ceremony.publicKey().containsKey("excludeCredentials"));
  }

  @Test
  void finishRegistrationRejectsUnknownChallenge() {
    var response = authenticator.register(new byte[32], ORIGIN);
    var e =
        assertThrows(
            PasskeyException.class,
            () ->
                service.finishRegistration(
                    "wac_missing", response.clientDataJson(), response.attestationObject(), null));
    assertEquals(PasskeyException.Kind.BAD_REQUEST, e.kind());
  }

  @Test
  void finishRegistrationRejectsTamperedResponse() {
    var ceremony = service.startRegistration("uday");
    var response = authenticator.register(challengeOf(ceremony), "https://evil.com");
    var e =
        assertThrows(
            PasskeyException.class,
            () ->
                service.finishRegistration(
                    ceremony.challengeId(),
                    response.clientDataJson(),
                    response.attestationObject(),
                    null));
    assertEquals(PasskeyException.Kind.BAD_REQUEST, e.kind());
  }

  @Test
  void finishRegistrationRejectsAlreadyRegisteredCredential() {
    enroll("uday");
    var ceremony = service.startRegistration("uday");
    var response = authenticator.register(challengeOf(ceremony), ORIGIN);
    var e =
        assertThrows(
            PasskeyException.class,
            () ->
                service.finishRegistration(
                    ceremony.challengeId(),
                    response.clientDataJson(),
                    response.attestationObject(),
                    null));
    assertEquals(PasskeyException.Kind.BAD_REQUEST, e.kind());
  }

  @Test
  void finishLoginRejectsUnknownChallenge() {
    enroll("uday");
    var assertion = authenticator.assertResponse(new byte[32], ORIGIN);
    var e =
        assertThrows(
            PasskeyException.class,
            () ->
                service.finishLogin(
                    "wac_missing",
                    assertion.credentialId(),
                    assertion.clientDataJson(),
                    assertion.authenticatorData(),
                    assertion.signature(),
                    null));
    assertEquals(PasskeyException.Kind.UNAUTHORIZED, e.kind());
  }

  @Test
  void finishLoginRejectsUnknownCredential() {
    var login = service.startLogin();
    var stranger = new TestAuthenticator(RP_ID).assertResponse(challengeOf(login), ORIGIN);
    var e =
        assertThrows(
            PasskeyException.class,
            () ->
                service.finishLogin(
                    login.challengeId(),
                    stranger.credentialId(),
                    stranger.clientDataJson(),
                    stranger.authenticatorData(),
                    stranger.signature(),
                    null));
    assertEquals(PasskeyException.Kind.UNAUTHORIZED, e.kind());
  }

  @Test
  void finishLoginRejectsBadSignature() {
    enroll("uday");
    var login = service.startLogin();
    var assertion = authenticator.assertResponse(challengeOf(login), ORIGIN);
    var tampered =
        new String(assertion.clientDataJson(), StandardCharsets.UTF_8)
            .replace("{", "{ ")
            .getBytes(StandardCharsets.UTF_8);
    var e =
        assertThrows(
            PasskeyException.class,
            () ->
                service.finishLogin(
                    login.challengeId(),
                    assertion.credentialId(),
                    tampered,
                    assertion.authenticatorData(),
                    assertion.signature(),
                    null));
    assertEquals(PasskeyException.Kind.UNAUTHORIZED, e.kind());
  }

  @Test
  void finishLoginAcceptsAMatchingUserHandle() {
    enroll("uday");
    var login = service.startLogin();
    var assertion = authenticator.assertResponse(challengeOf(login), ORIGIN);
    var fdeId = credentials.findByCredentialId(authenticator.credentialId()).orElseThrow().fdeId();

    var result =
        service.finishLogin(
            login.challengeId(),
            assertion.credentialId(),
            assertion.clientDataJson(),
            assertion.authenticatorData(),
            assertion.signature(),
            fdeId.getBytes(StandardCharsets.UTF_8));

    assertEquals("uday", result.fdeHandle());
  }

  @Test
  void finishLoginRejectsAUserHandleForAnotherAccount() {
    enroll("uday");
    var login = service.startLogin();
    var assertion = authenticator.assertResponse(challengeOf(login), ORIGIN);

    var e =
        assertThrows(
            PasskeyException.class,
            () ->
                service.finishLogin(
                    login.challengeId(),
                    assertion.credentialId(),
                    assertion.clientDataJson(),
                    assertion.authenticatorData(),
                    assertion.signature(),
                    "some-other-fde-id".getBytes(StandardCharsets.UTF_8)));
    assertEquals(PasskeyException.Kind.UNAUTHORIZED, e.kind());
  }
}
