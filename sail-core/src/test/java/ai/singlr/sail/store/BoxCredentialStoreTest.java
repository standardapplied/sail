/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoxCredentialStoreTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private BoxCredentialStore store;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("box.db"));
    new SchemaManager(db).migrate();
    store = new BoxCredentialStore(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void replaceMintsAResolvableCredentialForTheHandle() {
    var credential = store.replace("uday");

    assertTrue(credential.startsWith("sailbox_"), credential);
    assertEquals(72, credential.length());
    assertEquals(Optional.of("uday"), store.resolve(credential));
  }

  @Test
  void replaceKillsThePreviousCredentialAtomically() {
    var first = store.replace("uday");
    var second = store.replace("uday");

    assertNotEquals(first, second);
    assertEquals(Optional.empty(), store.resolve(first));
    assertEquals(Optional.of("uday"), store.resolve(second));
    assertEquals(
        1, db.queryOne("SELECT COUNT(*) FROM box_credential", r -> r.integer(0)).orElseThrow());
  }

  @Test
  void replaceRebindsToANewHandle() {
    store.replace("uday");
    var rebound = store.replace("sumesh");

    assertEquals(Optional.of("sumesh"), store.resolve(rebound));
  }

  @Test
  void resolveRefusesUnknownBlankAndNullCredentials() {
    store.replace("uday");

    assertEquals(Optional.empty(), store.resolve("sailbox_" + "0".repeat(64)));
    assertEquals(Optional.empty(), store.resolve(" "));
    assertEquals(Optional.empty(), store.resolve(null));
  }

  @Test
  void replaceRequiresAHandle() {
    assertThrows(IllegalArgumentException.class, () -> store.replace(" "));
    assertThrows(IllegalArgumentException.class, () -> store.replace(null));
  }

  @Test
  void onlyTheHashIsAtRest() {
    var credential = store.replace("uday");

    var stored =
        db.queryOne("SELECT credential_hash FROM box_credential", r -> r.text(0)).orElseThrow();
    assertNotEquals(credential, stored);
    assertEquals(64, stored.length());
  }
}
