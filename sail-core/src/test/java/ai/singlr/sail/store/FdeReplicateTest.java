/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Main-authoritative identity replication: mirroring main's roster inserts new handles, updates the
 * role/status/display of existing ones while preserving their local id, and refuses a malformed
 * authorization rather than writing it.
 */
class FdeReplicateTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private FdeStore fdes;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    fdes = new FdeStore(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void replicateInsertsANewIdentity() {
    fdes.replicate("ada", "Ada", "ada@x.dev", "member", "active", "2026-01-01T00:00:00Z");

    var ada = fdes.byHandle("ada").orElseThrow();
    assertEquals("Ada", ada.displayName());
    assertEquals("member", ada.role());
    assertEquals("active", ada.status());
  }

  @Test
  void replicateUpdatesRoleAndStatusButKeepsTheLocalId() {
    var local = fdes.add("ada", "Ada", "ada@x.dev", "member");

    fdes.replicate("ada", "Ada Lovelace", "ada@x.dev", "admin", "disabled", null);

    var ada = fdes.byHandle("ada").orElseThrow();
    assertEquals(local.id(), ada.id(), "tokens and keys that reference the local id stay valid");
    assertEquals("Ada Lovelace", ada.displayName());
    assertEquals("admin", ada.role());
    assertEquals("disabled", ada.status());
  }

  @Test
  void replicateRejectsAnInvalidRole() {
    assertThrows(
        IllegalArgumentException.class,
        () -> fdes.replicate("ada", "Ada", null, "superuser", "active", null));
    assertTrue(fdes.byHandle("ada").isEmpty(), "nothing is written with a bad role");
  }

  @Test
  void replicateRejectsAnInvalidStatus() {
    assertThrows(
        IllegalArgumentException.class,
        () -> fdes.replicate("ada", "Ada", null, "member", "banned", null));
    assertTrue(fdes.byHandle("ada").isEmpty());
  }

  @Test
  void replicateRejectsAMalformedHandle() {
    assertThrows(
        IllegalArgumentException.class,
        () -> fdes.replicate("Bad Handle!", "x", null, "member", "active", null));
  }
}
