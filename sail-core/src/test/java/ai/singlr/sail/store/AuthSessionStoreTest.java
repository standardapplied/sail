/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.webauthn.RegisteredCredential;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthSessionStoreTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private AuthSessionStore store;
  private String fdeId;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    fdeId = new FdeStore(db).add("uday", null, null).id();
    store = new AuthSessionStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  @Test
  void createsAndValidatesSession() {
    var created = store.create(fdeId, Duration.ofMinutes(30));
    assertTrue(created.token().startsWith("sess_"));
    assertEquals(fdeId, created.fdeId());

    var info = store.validate(created.token()).orElseThrow();
    assertEquals(fdeId, info.fdeId());
    assertEquals(created.expiresAt(), info.expiresAt());
  }

  @Test
  void validateReturnsEmptyForUnknownToken() {
    assertTrue(store.validate("sess_bogus").isEmpty());
  }

  @Test
  void expiredSessionIsRejectedAndPruned() {
    var created = store.create(fdeId, Duration.ofSeconds(-1)); // already expired
    assertTrue(store.validate(created.token()).isEmpty());
    // pruned: a second lookup is still empty and the row is gone
    assertTrue(store.validate(created.token()).isEmpty());
    assertFalse(store.revoke(created.token()));
  }

  @Test
  void revokeDeletesSession() {
    var created = store.create(fdeId, Duration.ofMinutes(30));
    assertTrue(store.revoke(created.token()));
    assertTrue(store.validate(created.token()).isEmpty());
    assertFalse(store.revoke("sess_unknown"));
  }

  @Test
  void revokeForFdeDeletesAll() {
    var a = store.create(fdeId, Duration.ofMinutes(30));
    var b = store.create(fdeId, Duration.ofMinutes(30));
    assertEquals(2, store.revokeForFde(fdeId));
    assertTrue(store.validate(a.token()).isEmpty());
    assertTrue(store.validate(b.token()).isEmpty());
  }

  /**
   * A session signed in with a passkey names it, as the stores write a credential id; one minted by
   * no passkey names none; ending the sessions a passkey minted ends those alone; and a passkey
   * already gone mints no session.
   */
  @Test
  void aSessionSignedInWithAPasskeyNamesItAndEndsWithIt() {
    var passkey = held(new byte[] {1, 2, 3});
    var other = held(new byte[] {4, 5, 6});
    var signedIn = store.createSignedInWith(fdeId, passkey, Duration.ofMinutes(30)).orElseThrow();
    var elsewhere = store.createSignedInWith(fdeId, other, Duration.ofMinutes(30)).orElseThrow();
    var plain = store.create(fdeId, Duration.ofMinutes(30));

    assertEquals(Optional.of("AQID"), store.passkeyOf(signedIn.token()));
    assertTrue(store.passkeyOf(plain.token()).isEmpty(), "a session no passkey minted");
    assertTrue(store.passkeyOf("sess_unknown").isEmpty(), "no session");

    assertEquals(1, store.revokeMintedBy(passkey));
    assertTrue(store.validate(signedIn.token()).isEmpty());
    assertTrue(store.validate(elsewhere.token()).isPresent());
    assertTrue(store.validate(plain.token()).isPresent());

    new WebauthnCredentialStore(db).delete(other);
    assertEquals(Optional.empty(), store.createSignedInWith(fdeId, other, Duration.ofMinutes(30)));
    assertEquals(
        Optional.empty(),
        store.createSignedInWith(fdeId, new byte[] {7}, Duration.ofMinutes(30)),
        "a passkey never held");
    assertEquals(
        2L,
        db.queryOne("SELECT COUNT(*) FROM sessions", row -> row.integer(0)).orElseThrow(),
        "the refused sign-ins minted nothing");
  }

  private byte[] held(byte[] credentialId) {
    new WebauthnCredentialStore(db)
        .save(
            new RegisteredCredential(credentialId, new byte[] {9}, -7, 0, null, false, false),
            fdeId,
            "test");
    return credentialId;
  }
}
