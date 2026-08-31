/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.pty.PtySession;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PtyHostEventsTest {

  @TempDir Path dir;

  private Path migrated() {
    var path = dir.resolve("cp.db");
    try (var db = Sqlite.open(path)) {
      new SchemaManager(db).migrate();
    }
    return path;
  }

  private static List<EventStore.EventRow> recent(Path path) {
    try (var db = Sqlite.open(path)) {
      return new EventStore(db).recent(10);
    }
  }

  @Test
  void theThreeSessionFactsBecomeRecordClassRows() {
    var path = migrated();
    var events = new PtyHostEvents(path);
    var origin = new PtySession.Origin("lounge", "uday", "acme", "", List.of("bash", "-l"));

    events.sessionStarted(origin);
    events.sessionAttached(origin, "mady");
    events.sessionEnded(origin, "exited(0)");

    var recent = recent(path);
    assertEquals(3, recent.size());
    assertTrue(
        recent.stream()
            .anyMatch(
                e ->
                    e.type().equals("pty_session_started")
                        && e.agent().equals("uday")
                        && e.project().equals("acme")
                        && e.data().contains("lounge")
                        && e.data().contains("bash")));
    assertTrue(
        recent.stream()
            .anyMatch(e -> e.type().equals("pty_session_attached") && e.agent().equals("mady")));
    assertTrue(
        recent.stream()
            .anyMatch(e -> e.type().equals("pty_session_ended") && e.data().contains("exited(0)")));
    assertTrue(
        recent.stream().noneMatch(e -> e.data().contains("room_id")),
        "an unbound session's rows name no room");
    assertTrue(
        recent.stream().allMatch(e -> e.specId() == null),
        "an unbound session's rows are scoped to no room");
  }

  @Test
  void aRoomBoundSessionsRowsCarryTheRoomAndTheStartNamesTheCommand() {
    var path = migrated();
    var events = new PtyHostEvents(path);
    var origin =
        new PtySession.Origin("brainstorm", "uday", "acme", "design-talk", List.of("claude"));

    events.sessionStarted(origin);
    events.sessionAttached(origin, "mady");
    events.sessionEnded(origin, "exited(0)");

    var recent = recent(path);
    assertEquals(3, recent.size());
    for (var row : recent) {
      var data = YamlUtil.parseMap(row.data());
      assertEquals("design-talk", data.get("room_id"), row.type() + " names its room");
      assertEquals(
          "design-talk",
          row.specId(),
          row.type() + " is scoped to its room, so the room's history query finds it");
      assertEquals("brainstorm", data.get("session"));
    }
    var started =
        recent.stream()
            .filter(e -> e.type().equals("pty_session_started"))
            .findFirst()
            .orElseThrow();
    assertEquals(List.of("claude"), YamlUtil.parseMap(started.data()).get("command"));
    assertFalse(
        recent.stream()
            .filter(e -> !e.type().equals("pty_session_started"))
            .anyMatch(e -> e.data().contains("command")),
        "only the start fact narrates the command");
  }
}
