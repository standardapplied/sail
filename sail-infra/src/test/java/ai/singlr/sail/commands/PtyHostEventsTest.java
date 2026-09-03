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

  private Path drops() {
    return dir.resolve(PtyEventDrops.FILE_NAME);
  }

  private PtyHostEvents events(Path dbPath) {
    return new PtyHostEvents(dbPath, drops());
  }

  private static List<EventStore.EventRow> recent(Path path) {
    try (var db = Sqlite.open(path)) {
      return new EventStore(db).recent(10);
    }
  }

  @Test
  void theThreeSessionFactsBecomeRecordClassRows() {
    var path = migrated();
    var events = events(path);
    var origin =
        new PtySession.Origin("lounge", "inst-lounge-1", "uday", "acme", "", List.of("bash", "-l"));

    events.sessionStarted(origin);
    events.sessionAttached(origin, "mady");
    events.sessionEnded(origin, "exited(0)");

    var recent = recent(path);
    assertEquals(3, recent.size());
    for (var row : recent) {
      assertEquals(
          "inst-lounge-1",
          YamlUtil.parseMap(row.data()).get("instance_id"),
          row.type()
              + " names the incarnation, so a reader can tell this life of the name from the"
              + " next one");
    }
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
  void aRoomBoundSessionsRowsCarryTheRoomAndTheStartNamesOnlyTheExecutable() {
    var path = migrated();
    var events = events(path);
    var origin =
        new PtySession.Origin(
            "brainstorm",
            "inst-brainstorm-1",
            "uday",
            "acme",
            "design-talk",
            List.of("claude", "--api-key", "sk-live-secret"));

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
    assertEquals("claude", YamlUtil.parseMap(started.data()).get("executable"));
    assertFalse(
        recent.stream().anyMatch(e -> e.data().contains("sk-live-secret")),
        "arguments never reach durable, room-readable history");
    assertFalse(
        recent.stream()
            .filter(e -> !e.type().equals("pty_session_started"))
            .anyMatch(e -> e.data().contains("executable")),
        "only the start fact narrates the executable");
  }

  @Test
  void aCleanRunLeavesNoDropMeter() {
    var events = events(migrated());
    var origin =
        new PtySession.Origin("clean", "inst-clean-1", "uday", "acme", "", List.of("bash", "-l"));

    events.sessionStarted(origin);

    assertFalse(java.nio.file.Files.exists(drops()), "a delivered event bumps nothing");
  }

  @Test
  void anInducedInsertFailureIsMeasuredNotThrown() {
    var unmigrated = dir.resolve("unmigrated.db");
    var events = events(unmigrated);
    var origin =
        new PtySession.Origin("lounge", "inst-lounge-2", "uday", "acme", "", List.of("bash", "-l"));
    var stderr = new java.io.ByteArrayOutputStream();
    var original = System.err;
    System.setErr(new java.io.PrintStream(stderr, true, java.nio.charset.StandardCharsets.UTF_8));
    try {
      events.sessionStarted(origin);
      events.sessionAttached(origin, "mady");
      events.sessionEnded(origin, "exited(0)");
    } finally {
      System.setErr(original);
    }

    var meter = PtyEventDrops.read(drops());
    assertEquals(3, meter.count());
    assertEquals("pty_session_ended", meter.lastType());
    assertTrue(meter.lastCause() != null && !meter.lastCause().isBlank(), "the cause is named");
    assertTrue(meter.lastAt() != null && !meter.lastAt().isBlank(), "the drop is timestamped");

    var lines =
        stderr
            .toString(java.nio.charset.StandardCharsets.UTF_8)
            .lines()
            .filter(line -> line.startsWith("pty-events: dropped "))
            .toList();
    assertEquals(3, lines.size(), "one structured line per drop, nothing else");
    assertTrue(
        lines.getFirst().contains("pty_session_started")
            && lines.getFirst().contains("session=lounge")
            && lines.getFirst().contains("project=acme")
            && lines.getFirst().contains("cause="),
        "the line names the event type, the session, and the cause: " + lines.getFirst());
  }

  @Test
  void theDropMeterSurvivesACorruptFileAndAnUnwritablePath() throws Exception {
    var corrupt = drops();
    java.nio.file.Files.writeString(corrupt, "not json at all {{{");
    assertEquals(0, PtyEventDrops.read(corrupt).count(), "a corrupt meter reads as zero");

    PtyEventDrops.record(corrupt, "pty_session_started", "boom");
    assertEquals(1, PtyEventDrops.read(corrupt).count(), "recording over a corrupt meter restarts");

    var unwritable = dir.resolve("nope").resolve(PtyEventDrops.FILE_NAME);
    PtyEventDrops.record(unwritable, "pty_session_started", "boom");
    assertEquals(
        0, PtyEventDrops.read(unwritable).count(), "an unwritable meter is a silent no-op");
  }
}
