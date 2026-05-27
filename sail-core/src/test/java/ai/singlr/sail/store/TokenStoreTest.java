/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TokenStoreTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private TokenStore store;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    store = new TokenStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  @Test
  void createReturnsTokenWithSailPrefix() {
    var created = store.create("admin", "admin");
    assertEquals("admin", created.name());
    assertEquals("admin", created.role());
    assertTrue(created.token().startsWith("sail_"));
    assertEquals(69, created.token().length());
  }

  @Test
  void validateSucceedsWithCorrectToken() {
    var created = store.create("test-token", "member");

    var validated = store.validate(created.token());
    assertTrue(validated.isPresent());
    assertEquals("test-token", validated.get().name());
    assertEquals("member", validated.get().role());
  }

  @Test
  void validateFailsWithWrongToken() {
    store.create("real-token", "admin");

    var validated = store.validate("sail_wrong_token_value");
    assertTrue(validated.isEmpty());
  }

  @Test
  void validateUpdatesLastUsedAt() {
    var created = store.create("tracked", "member");

    var first = store.validate(created.token());
    assertTrue(first.isPresent());
    assertNull(first.get().lastUsedAt());

    var second = store.validate(created.token());
    assertTrue(second.isPresent());
    assertNotNull(second.get().lastUsedAt());
  }

  @Test
  void listReturnsAllTokensWithoutSecrets() {
    store.create("alpha", "admin");
    store.create("beta", "member");

    var tokens = store.list();
    assertEquals(2, tokens.size());
    assertEquals("alpha", tokens.get(0).name());
    assertEquals("admin", tokens.get(0).role());
    assertEquals("beta", tokens.get(1).name());
    assertEquals("member", tokens.get(1).role());
  }

  @Test
  void listReturnsEmptyWhenNoTokens() {
    assertTrue(store.list().isEmpty());
  }

  @Test
  void revokeRemovesToken() {
    var created = store.create("doomed", "member");
    assertTrue(store.revoke("doomed"));

    var validated = store.validate(created.token());
    assertTrue(validated.isEmpty());
    assertEquals(0, store.list().size());
  }

  @Test
  void revokeReturnsFalseForNonexistent() {
    assertFalse(store.revoke("ghost"));
  }

  @Test
  void multipleTokensAreIndependent() {
    var token1 = store.create("first", "admin");
    var token2 = store.create("second", "member");

    assertTrue(store.validate(token1.token()).isPresent());
    assertTrue(store.validate(token2.token()).isPresent());

    store.revoke("first");
    assertTrue(store.validate(token1.token()).isEmpty());
    assertTrue(store.validate(token2.token()).isPresent());
  }

  @Test
  void duplicateNameThrows() {
    store.create("unique", "admin");
    assertThrows(SqliteException.class, () -> store.create("unique", "member"));
  }

  @Test
  void sha256IsDeterministic() {
    var hash1 = TokenStore.sha256("test-input");
    var hash2 = TokenStore.sha256("test-input");
    assertEquals(hash1, hash2);
    assertEquals(64, hash1.length());
  }

  @Test
  void sha256DifferentInputsProduceDifferentHashes() {
    var hash1 = TokenStore.sha256("input-a");
    var hash2 = TokenStore.sha256("input-b");
    assertNotEquals(hash1, hash2);
  }
}
