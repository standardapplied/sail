/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PtyHostIdentityTest {

  @TempDir Path dir;

  private Path db() {
    var path = dir.resolve("cp.db");
    try (var db = Sqlite.open(path)) {
      new SchemaManager(db).migrate();
    }
    return path;
  }

  @Test
  void aGatewayTokenResolvesToItsFdeWithItsRole() throws Exception {
    var path = db();
    String token;
    try (var db = Sqlite.open(path)) {
      var fde = new FdeStore(db).add("mady", "Mady", null, "admin");
      token = new AuthSessionStore(db).create(fde.id(), Duration.ofMinutes(5)).token();
    }

    var identity = new PtyHostIdentity(() -> "uday", path).resolve(token);

    assertEquals("mady", identity.fde());
    assertTrue(identity.admin());
  }

  @Test
  void aBlankTokenIsTheBoxOwnerWithTheRosterRole() throws Exception {
    var path = db();
    try (var db = Sqlite.open(path)) {
      new FdeStore(db).add("uday", "Uday", null, "member");
    }

    var identity = new PtyHostIdentity(() -> "uday", path).resolve("");

    assertEquals("uday", identity.fde());
    assertFalse(identity.admin());
  }

  @Test
  void forgedTokensAndOwnerlessBoxesRefuseWithActionableMessages() {
    var path = db();

    var forged =
        assertThrows(
            IOException.class, () -> new PtyHostIdentity(() -> "uday", path).resolve("nope"));
    assertTrue(forged.getMessage().contains("not valid"), forged.getMessage());

    var ownerless =
        assertThrows(IOException.class, () -> new PtyHostIdentity(() -> null, path).resolve(""));
    assertTrue(ownerless.getMessage().contains("sync-handle"), ownerless.getMessage());
  }
}
