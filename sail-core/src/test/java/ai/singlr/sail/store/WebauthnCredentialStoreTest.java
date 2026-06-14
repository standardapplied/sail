/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.webauthn.CoseKey;
import ai.singlr.sail.webauthn.RegisteredCredential;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebauthnCredentialStoreTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private WebauthnCredentialStore store;
  private String fdeId;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    fdeId = new FdeStore(db).add("uday", null, null).id();
    store = new WebauthnCredentialStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private static RegisteredCredential cred(byte[] credId, long signCount, byte[] aaguid) {
    return new RegisteredCredential(
        credId, new byte[] {1, 2, 3, 4}, CoseKey.ES256, signCount, aaguid, true, false);
  }

  @Test
  void savesAndFindsByCredentialId() {
    var credId = new byte[] {10, 20, 30};
    var aaguid = new byte[16];
    store.save(cred(credId, 7, aaguid), fdeId, "uday's laptop");

    var found = store.findByCredentialId(credId).orElseThrow();
    assertArrayEquals(credId, found.credentialId());
    assertEquals(fdeId, found.fdeId());
    assertArrayEquals(new byte[] {1, 2, 3, 4}, found.publicKeyCose());
    assertEquals(CoseKey.ES256, found.coseAlgorithm());
    assertEquals(7, found.signCount());
    assertArrayEquals(aaguid, found.aaguid());
    assertTrue(found.backupEligible());
    assertFalse(found.backupState());
    assertEquals("uday's laptop", found.label());
    assertNotNull(found.createdAt());
    assertNull(found.lastUsedAt());
  }

  @Test
  void backupFlagsRoundTripBothCombinations() {
    store.save(
        new RegisteredCredential(
            new byte[] {7}, new byte[] {1}, CoseKey.ES256, 0, null, false, true),
        fdeId,
        null);
    var found = store.findByCredentialId(new byte[] {7}).orElseThrow();
    assertFalse(found.backupEligible());
    assertTrue(found.backupState());
  }

  @Test
  void findReturnsEmptyForUnknownCredential() {
    assertTrue(store.findByCredentialId(new byte[] {9, 9, 9}).isEmpty());
  }

  @Test
  void nullAaguidRoundTrips() {
    store.save(cred(new byte[] {1}, 0, null), fdeId, null);
    var found = store.findByCredentialId(new byte[] {1}).orElseThrow();
    assertNull(found.aaguid());
    assertNull(found.label());
  }

  @Test
  void listsCredentialsForFde() {
    store.save(cred(new byte[] {1}, 0, null), fdeId, "a");
    store.save(cred(new byte[] {2}, 0, null), fdeId, "b");
    assertEquals(2, store.listForFde(fdeId).size());
    assertTrue(store.listForFde("nobody").isEmpty());
  }

  @Test
  void recordUseAdvancesSignCountAndStampsLastUsed() {
    var credId = new byte[] {5};
    store.save(cred(credId, 5, null), fdeId, null);
    store.recordUse(credId, 9);

    var found = store.findByCredentialId(credId).orElseThrow();
    assertEquals(9, found.signCount());
    assertNotNull(found.lastUsedAt());
  }
}
