/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The login doors on a box that keeps less than a whole control plane. */
class LoginOperationsTest {

  private static final Actor UDAY = new Actor("uday", Role.MEMBER, Actor.Lane.CLI);

  @TempDir Path tempDir;

  @Test
  void aBoxWithNoRosterKeepsNoLoginsAndSaysSo() {
    var doors = new LoginOperations(null, null, event -> {});

    var refused = assertThrows(ApiException.class, () -> Actor.call(UDAY, () -> doors.list(null)));

    assertEquals(ErrorCode.COMMAND_FAILED, refused.failure().errorCode());
    assertEquals("This box keeps no roster, so it keeps no logins.", refused.getMessage());
  }

  @Test
  void aBoxWithNoRunAggregateStoresAndSaysNothingOfLiveRuns() {
    try (var db = Sqlite.open(tempDir.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
      new FdeStore(db).add("uday", null, null, "member");
      var events = new ArrayList<Event>();
      var doors = new LoginOperations(new FdeStore(db), null, events::add);

      var stored = Actor.call(UDAY, () -> doors.store("codex", null, null, "sk-proj-1"));
      var removed = Actor.call(UDAY, () -> doors.remove("codex", "default", null));
      var again = Actor.call(UDAY, () -> doors.remove("codex", "default", null));

      assertEquals("default", stored.login().name());
      assertNull(stored.note());
      assertNull(removed.note());
      assertNull(again.login());
      assertEquals(
          "No Codex CLI login named 'default' is stored for uday; nothing was removed.",
          again.note());
      assertEquals(2, events.size(), "removing nothing announces nothing");
    }
  }

  @Test
  void aBoxWhoseDatabaseIsInMemoryRefusesToStore() {
    try (var db = Sqlite.openMemory()) {
      new SchemaManager(db).migrate();
      new FdeStore(db).add("uday", null, null, "member");
      var doors = new LoginOperations(new FdeStore(db), null, event -> {});

      var refused =
          assertThrows(
              ApiException.class,
              () -> Actor.call(UDAY, () -> doors.store("codex", null, null, "sk-proj-1")));

      assertEquals(ErrorCode.COMMAND_FAILED, refused.failure().errorCode());
      assertEquals(
          "This box keeps its database in memory, so it keeps no logins.", refused.getMessage());
    }
  }
}
