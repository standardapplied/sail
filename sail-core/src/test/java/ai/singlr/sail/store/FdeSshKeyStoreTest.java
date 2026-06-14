/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.ssh.SshPublicKey;
import ai.singlr.sail.ssh.TestSshKeys;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FdeSshKeyStoreTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private FdeSshKeyStore store;
  private String fdeId;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    fdeId = new FdeStore(db).add("uday", null, null, "admin").id();
    store = new FdeSshKeyStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private SshPublicKey key(String seed, String comment) {
    return SshPublicKey.parse(TestSshKeys.ed25519(seed, comment));
  }

  @Test
  void addsAndResolvesByFingerprintToHandle() {
    var key = key("uday", "uday@laptop");
    store.add(fdeId, key);

    var found = store.findByFingerprint(key.fingerprint()).orElseThrow();
    assertEquals("uday", found.fdeHandle());
    assertEquals(fdeId, found.fdeId());
    assertEquals("uday@laptop", found.comment());
    assertEquals(key.line(), found.publicKey());
  }

  @Test
  void findByUnknownFingerprintIsEmpty() {
    assertTrue(store.findByFingerprint("SHA256:nope").isEmpty());
  }

  @Test
  void listsAllAndPerFde() {
    var other = new FdeStore(db).add("nova", null, null).id();
    store.add(fdeId, key("uday", "a"));
    store.add(fdeId, key("uday2", "b"));
    store.add(other, key("nova", "c"));

    assertEquals(3, store.list().size());
    assertEquals(2, store.listForFde(fdeId).size());
  }

  @Test
  void removeRevokesAndIsIdempotent() {
    var key = key("uday", null);
    store.add(fdeId, key);
    assertTrue(store.remove(key.fingerprint()));
    assertFalse(store.remove(key.fingerprint()));
    assertTrue(store.findByFingerprint(key.fingerprint()).isEmpty());
  }

  @Test
  void duplicateFingerprintIsRejected() {
    var key = key("uday", "first");
    store.add(fdeId, key);
    assertThrows(SqliteException.class, () -> store.add(fdeId, key("uday", "second")));
  }
}
