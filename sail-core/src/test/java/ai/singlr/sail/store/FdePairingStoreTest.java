/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FdePairingStoreTest {

  private Sqlite db;
  private FdePairingStore pairings;
  private String ada;

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    pairings = new FdePairingStore(db);
    ada = new FdeStore(db).add("ada", null, null, "member").id();
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void anFdeHoldsOnePairingUntilItIsRemoved() {
    assertTrue(pairings.find(ada).isEmpty());

    pairings.put(ada, "ssh-ed25519 AAAA sail-mast:ada", "mast-ada");

    var pairing = pairings.find(ada).orElseThrow();
    assertEquals(
        new FdePairingStore.Pairing(
            ada, "ssh-ed25519 AAAA sail-mast:ada", "mast-ada", pairing.createdAt()),
        pairing);
    assertThrows(
        SqliteException.class,
        () -> pairings.put(ada, "ssh-ed25519 BBBB sail-mast:ada", "mast-ada"));

    assertTrue(pairings.remove(ada));
    assertTrue(pairings.find(ada).isEmpty());
    assertFalse(pairings.remove(ada));
  }

  @Test
  void aPairingNamesAnFdeThisBoxHolds() {
    assertThrows(
        SqliteException.class,
        () -> pairings.put("fde_nobody", "ssh-ed25519 AAAA sail-mast:x", "mast-x"));
  }
}
