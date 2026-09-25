/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.ErrorCode;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliOperatorTest {

  private static final SyncConfig NODE = new SyncConfig("node", "sail@main", "mady", "mady-box");

  @TempDir Path dir;

  @Test
  void mainAndAStandaloneBoxAreOperatedByAnAdminOnTheCliLane() {
    var main = new SyncConfig("main", null, "uday", "uday-box");
    var standalone = new SyncConfig(null, null, "raj", null);

    assertEquals(Actor.cliOperator("uday"), CliOperator.of(main, () -> null));
    assertEquals(Actor.cliOperator("raj"), CliOperator.of(standalone, () -> null));
  }

  @Test
  void aNodeIsOperatedByItsFdeWithTheRoleTheSyncedRosterGivesIt() {
    try (var db = roster()) {
      new FdeStore(db).add("mady", null, null, "member");

      var operator = CliOperator.of(NODE, () -> new FdeStore(db));

      assertEquals(new Actor("mady", Role.MEMBER, Actor.Lane.CLI), operator);
    }
  }

  @Test
  void aNodeWhoseRosterHasNotSyncedRefusesToGuessARole() {
    try (var db = roster()) {
      var refused =
          assertThrows(ApiException.class, () -> CliOperator.of(NODE, () -> new FdeStore(db)));

      assertEquals(ErrorCode.CONFLICT, refused.failure().errorCode());
      assertTrue(refused.getMessage().contains("does not know its FDE's role"));
      assertTrue(refused.failure().action().contains("sail sync"));
    }
  }

  @Test
  void aNodeWithNoHandleIsRefusedWithoutReadingTheRoster() {
    var anonymous = new SyncConfig("node", "sail@main", null, "box");

    assertThrows(
        ApiException.class,
        () ->
            CliOperator.of(
                anonymous,
                () -> {
                  throw new AssertionError("the roster is never read without a handle");
                }));
  }

  @Test
  void theCurrentOperatorOfANodeIsReadFromTheControlPlaneRoster() {
    var path = dir.resolve("sail.db");
    try (var db = Sqlite.open(path)) {
      new SchemaManager(db).migrate();
      new FdeStore(db).add("mady", null, null, "viewer");
    }

    assertEquals(new Actor("mady", Role.VIEWER, Actor.Lane.CLI), CliOperator.current(NODE, path));
    assertEquals(
        Actor.cliOperator("uday"),
        CliOperator.current(new SyncConfig("main", null, "uday", null), dir.resolve("absent.db")));
  }

  private Sqlite roster() {
    var db = Sqlite.open(dir.resolve("roster.db"));
    new SchemaManager(db).migrate();
    return db;
  }
}
