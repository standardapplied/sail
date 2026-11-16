/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The login doors on a box that keeps less than a whole control plane. */
class LoginOperationsTest {

  private static final Actor UDAY = new Actor("uday", Role.MEMBER, Actor.Lane.CLI);

  @TempDir Path tempDir;

  @Test
  void aBoxWithNoRosterKeepsNoLoginsAndSaysSo() {
    var doors = new LoginOperations(null, null, event -> {}, handle -> List.of());

    var refused = assertThrows(ApiException.class, () -> Actor.call(UDAY, () -> doors.list(null)));

    assertEquals(ErrorCode.COMMAND_FAILED, refused.failure().errorCode());
    assertEquals("This box keeps no roster, so it keeps no logins.", refused.getMessage());
  }

  /** High 1: a person a node keeps as disabled stores and uses no login on it. */
  @Test
  void aDisabledPersonStoresAndUsesNoLogin() {
    try (var db = Sqlite.open(tempDir.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
      var fdes = new FdeStore(db);
      fdes.add("uday", null, null, "member");
      fdes.add("ada", null, null, "admin");
      fdes.disableUnlisted(List.of("ada"), null);
      var doors = new LoginOperations(fdes, null, event -> {}, handle -> List.of());

      var store =
          assertThrows(
              ApiException.class,
              () ->
                  Actor.call(
                      UDAY,
                      () -> doors.store("git", "github.com", "token", "ghp_x_0123456789ab", null)));
      var use =
          assertThrows(
              ApiException.class, () -> Actor.call(UDAY, () -> doors.use("codex", "work")));

      for (var refused : List.of(store, use)) {
        assertEquals(ErrorCode.FORBIDDEN, refused.failure().errorCode());
        assertEquals(
            "uday is disabled on this box, so they keep no logins here.", refused.getMessage());
      }
      assertEquals(Map.of(), fdes.logins().gitTokens(fdes.byHandle("uday").orElseThrow().id()));
    }
  }

  @Test
  void aBoxWithNoRunAggregateStoresAndSaysNothingOfLiveRuns() {
    try (var db = Sqlite.open(tempDir.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
      new FdeStore(db).add("uday", null, null, "member");
      var events = new ArrayList<Event>();
      var doors = new LoginOperations(new FdeStore(db), null, events::add, handle -> List.of());

      var stored = Actor.call(UDAY, () -> doors.store("codex", null, null, "sk-proj-1", null));
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
      var doors = new LoginOperations(new FdeStore(db), null, event -> {}, handle -> List.of());

      var refused =
          assertThrows(
              ApiException.class,
              () -> Actor.call(UDAY, () -> doors.store("codex", null, null, "sk-proj-1", null)));

      assertEquals(ErrorCode.COMMAND_FAILED, refused.failure().errorCode());
      assertEquals(
          "This box keeps its database in memory, so it keeps no logins.", refused.getMessage());
    }
  }

  /**
   * A stored, replaced or removed git token reaches the person's running workspaces at once; one it
   * could not reach is said in the answer, and so is a box whose workspaces cannot be read.
   */
  @Test
  void aGitTokenChangeReachesThePersonsWorkspacesAndSaysEachItDidNot() {
    try (var db = Sqlite.open(tempDir.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
      new FdeStore(db).add("uday", null, "uday@x", "member");
      var reached = new ArrayList<String>();
      var doors =
          new LoginOperations(
              new FdeStore(db),
              null,
              event -> {},
              handle -> {
                reached.add(handle);
                return List.of("acme-a: Error: not running");
              });
      var unreadable =
          new LoginOperations(
              new FdeStore(db),
              null,
              event -> {},
              handle -> {
                throw new IOException("incus list failed: denied");
              });

      var stored =
          Actor.call(
              UDAY, () -> doors.store("git", "github.com", "token", "ghp_one_0123456789ab", null));
      var removed = Actor.call(UDAY, () -> doors.remove("git", "github.com", null));
      var blind =
          Actor.call(
              UDAY,
              () -> unreadable.store("git", "github.com", "token", "ghp_two_0123456789ab", null));
      var interrupting =
          new LoginOperations(
              new FdeStore(db),
              null,
              event -> {},
              handle -> {
                throw new InterruptedException("stopped");
              });
      Actor.call(
          UDAY,
          () -> interrupting.store("git", "github.com", "token", "ghp_three_0123456789ab", null));
      assertTrue(Thread.interrupted(), "the interrupt is kept");

      assertEquals(List.of("uday", "uday"), reached);
      assertEquals(
          "These workspaces were not given all of it, and are given it again at their next start:"
              + " acme-a: Error: not running.",
          stored.note());
      assertEquals(stored.note(), stored.toMap().get("note"));
      assertEquals(
          "These workspaces were not given all of it, and are given it again at their next start:"
              + " acme-a: Error: not running.",
          removed.note());
      assertEquals(
          "This box's workspaces could not be read, so each is given it at its next start: incus"
              + " list failed: denied",
          blind.note());
    }
  }
}
