/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
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
   * A session signed in with a passkey names it, one minted by no passkey names none, and ending
   * the sessions a passkey minted ends those alone.
   */
  @Test
  void aSessionSignedInWithAPasskeyNamesItAndEndsWithIt() {
    var passkey = new byte[] {1, 2, 3};
    var other = new byte[] {4, 5, 6};
    var signedIn = store.createSignedInWith(fdeId, passkey, Duration.ofMinutes(30));
    var elsewhere = store.createSignedInWith(fdeId, other, Duration.ofMinutes(30));
    var plain = store.create(fdeId, Duration.ofMinutes(30));

    assertArrayEquals(passkey, store.passkeyOf(signedIn.token()).orElseThrow());
    assertTrue(store.passkeyOf(plain.token()).isEmpty(), "a session no passkey minted");
    assertTrue(store.passkeyOf("sess_unknown").isEmpty(), "no session");

    assertEquals(1, store.revokeMintedBy(passkey));
    assertTrue(store.validate(signedIn.token()).isEmpty());
    assertTrue(store.validate(elsewhere.token()).isPresent());
    assertTrue(store.validate(plain.token()).isPresent());
  }
}
