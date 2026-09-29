/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.sync.SyncBox;
import ai.singlr.sail.sync.SyncedEntities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one-time erasure of every personal room, over a real session: main erases each with its
 * messages and runs and every node adopts it on its next round, a node removes the ones only it
 * ever held without them reaching main, a room with work going on or a spec in it is left and
 * reported, every other room is untouched, and a run killed partway resumes, erasing nothing twice.
 *
 * <p>The ids are the personal-room ids an older release derived, written out: the migration alone
 * derives them.
 */
@ActingAs
class PersonalRoomErasureTest {

  private static final String UDAY = "fde-uday-acme-dd44ad396452ce74";
  private static final String RAJESH = "fde-rajesh-acme-c138f4e4682e7fda";
  private static final String M_DAY = "fde-m-day-acme-e88b3a8b481e6a91";
  private static final String NODES = "fde-node-acme-ab3bbea6eecdc1ba";
  private static final String WRONG_FINGERPRINT = "fde-uday-acme-0000000000000000";

  private static final Actor NODE = Actor.sync("node", Role.MEMBER);

  @TempDir Path dir;
  private SyncBox main;
  private SyncBox node;

  @BeforeEach
  void setUp() {
    main = new SyncBox(dir, "main");
    node = new SyncBox(dir, "node");
    new FdeStore(main.db).add("uday", null, null, "admin");
    new FdeStore(main.db).add("rajesh", null, null, "member");
    new FdeStore(main.db).add("node", null, null, "member");
  }

  @AfterEach
  void tearDown() {
    node.db.close();
    main.db.close();
  }

  @Test
  void mainErasesEveryPersonalRoomWithItsMessagesAndRunsAndANodeAdoptsItOnItsNextRound()
      throws IOException {
    var udays = personalRoom(main.db, UDAY, "uday", true);
    var rajeshs = personalRoom(main.db, RAJESH, "rajesh", true);
    ordinaryRooms(main.db, "uday");
    round();
    assertEquals(2, count(node.db, "SELECT count(*) FROM rooms WHERE id IN (?, ?)", UDAY, RAJESH));

    var report = new PersonalRoomErasure(() -> true).apply(main.db, null, null);
    round();

    assertEquals(2, report.applied());
    assertEquals(List.of("Erased 2 personal rooms: 4 messages, 2 runs"), report.notes());
    for (var box : List.of(main, node)) {
      for (var erased : List.of(udays, rajeshs)) {
        assertGone(box.db, erased);
      }
      assertOrdinaryRoomsUntouched(box.db);
    }
    for (var erased : List.of(udays, rajeshs)) {
      for (var id : erased.ids()) {
        assertEquals(
            erasureRev(main.db, id), erasureRev(node.db, id), "the node adopted main's erasure");
      }
    }
    var erasures = count(main.db, "SELECT count(*) FROM change_log WHERE kind = 'erasure'");
    assertEquals(0, new PersonalRoomErasure(() -> true).apply(main.db, null, null).applied());
    assertEquals(
        erasures,
        count(main.db, "SELECT count(*) FROM change_log WHERE kind = 'erasure'"),
        "a second run erases nothing twice");
  }

  @Test
  void aNodeRemovesThePersonalRoomOnlyItHeldWhichNeverReachesMainAndLeavesMainsToMain()
      throws IOException {
    var mains = personalRoom(main.db, UDAY, "uday", true);
    round();
    var nodes = personalRoom(node.db, NODES, "node", false);
    ordinaryRooms(node.db, "node");

    var report = new PersonalRoomErasure(() -> false).apply(node.db, null, null);

    assertEquals(1, report.applied());
    assertEquals(
        List.of(
            "Removed 1 personal rooms this box alone held: 1 messages, 0 runs",
            "1 personal rooms are main's to erase; this node adopts its erasures"),
        report.notes());
    assertGone(node.db, nodes);
    assertEquals(
        0,
        count(node.db, "SELECT count(*) FROM change_log WHERE kind = 'erasure'"),
        "what only this box held leaves no erasure to adopt");
    assertTrue(new RoomStore(node.db).findById(UDAY).isPresent(), "main's room is main's");

    round();
    assertGone(main.db, nodes);
    assertEquals(
        0, count(main.db, "SELECT count(*) FROM change_log WHERE entity_id = ?", nodes.room()));

    new PersonalRoomErasure(() -> true).apply(main.db, null, null);
    round();
    assertGone(node.db, mains);
    assertOrdinaryRoomsUntouched(node.db);
  }

  @Test
  void aPersonalRoomDeletedBeforeTheUpgradeIsErasedWithWhatItsDeletionKeptOnEveryBox()
      throws IOException {
    var deleted = personalRoom(main.db, UDAY, "uday", true);
    new RoomStore(main.db).delete(UDAY);
    room(main.db, M_DAY, "uday", "uday");
    new MessageStore(main.db).append(M_DAY, "uday", "not M.Day's", null);
    new RoomStore(main.db).delete(M_DAY);
    round();
    var nodes = personalRoom(node.db, NODES, "node", false);
    new RoomStore(node.db).delete(NODES);

    var local = new PersonalRoomErasure(() -> false).apply(node.db, null, null);
    var report = new PersonalRoomErasure(() -> true).apply(main.db, null, null);
    round();

    assertEquals(
        List.of("Removed 1 personal rooms this box alone held: 1 messages, 0 runs"), local.notes());
    assertEquals(List.of("Erased 1 personal rooms: 2 messages, 1 runs"), report.notes());
    for (var box : List.of(main, node)) {
      assertGone(box.db, deleted);
      assertGone(box.db, nodes);
      assertEquals(1, count(box.db, "SELECT count(*) FROM room_messages WHERE room_id = ?", M_DAY));
      assertEquals(
          0,
          count(
              box.db,
              "SELECT count(*) FROM change_log WHERE entity_id = ? AND kind = 'erasure'",
              M_DAY),
          "a deleted room another FDE made under M.Day's id is untouched");
    }
    assertEquals(
        0, count(main.db, "SELECT count(*) FROM change_log WHERE entity_id = ?", nodes.room()));
  }

  @Test
  void aPersonalRoomWithAnUnfinishedRunOrASpecInItIsLeftAsAnOrdinaryRoomAndReported() {
    var working = personalRoom(main.db, UDAY, "uday", false);
    var running = run(main.db, UDAY, false);
    var housing = personalRoom(main.db, RAJESH, "rajesh", true);
    main.specs.create(SyncBox.spec("born-there", "Born there", "pending"));
    main.db.execute("UPDATE specs SET room_id = ? WHERE id = 'born-there'", RAJESH);

    var report = new PersonalRoomErasure(() -> true).apply(main.db, null, null);

    assertEquals(0, report.applied());
    assertEquals(2, report.skipped());
    assertEquals(
        List.of(
            "Erased 0 personal rooms: 0 messages, 0 runs",
            "Left personal room '"
                + UDAY
                + "' as an ordinary room: run '"
                + running
                + "' has not finished",
            "Left personal room '"
                + RAJESH
                + "' as an ordinary room: spec 'born-there' converses in it"),
        report.notes());
    for (var kept : List.of(working, housing)) {
      for (var id : kept.ids()) {
        assertEquals(
            0,
            count(
                main.db,
                "SELECT count(*) FROM change_log WHERE entity_id = ? AND kind = 'erasure'",
                id));
      }
      assertTrue(new RoomStore(main.db).findById(kept.room()).isPresent());
      assertEquals(
          kept.messages().size(),
          count(main.db, "SELECT count(*) FROM room_messages WHERE room_id = ?", kept.room()));
    }
    assertEquals(1, count(main.db, "SELECT count(*) FROM runs WHERE id = ?", running));
  }

  @Test
  void aMigrationKilledAfterTwoRoomsIsFinishedByASecondProcessErasingNothingTwice()
      throws Exception {
    var path = dir.resolve("killed.db");
    try (var db = Sqlite.open(path)) {
      new SchemaManager(db).migrate();
      for (var room :
          List.of(
              List.of(UDAY, "uday"),
              List.of(RAJESH, "rajesh"),
              List.of(M_DAY, "M.Day"),
              List.of(NODES, "node"))) {
        personalRoom(db, room.getFirst(), room.getLast(), false);
      }
      db.execute(
          """
          CREATE TRIGGER killed AFTER INSERT ON change_log
          WHEN NEW.kind = 'erasure' AND NEW.entity_type = 'room'
              AND (SELECT count(*) FROM change_log
                  WHERE kind = 'erasure' AND entity_type = 'room') > 2
          BEGIN SELECT RAISE(ABORT, 'killed'); END""");

      assertThrows(
          SqliteException.class, () -> new PersonalRoomErasure(() -> true).apply(db, null, null));
      assertEquals(2, roomErasures(db), "the two rooms before the kill stay erased");
      assertEquals(2, count(db, "SELECT count(*) FROM rooms"));
      db.execute("DROP TRIGGER killed");
    }

    var log = dir.resolve("second.log");
    var second =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                SecondProcess.class.getName(),
                path.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    assertTrue(second.waitFor(60, TimeUnit.SECONDS));
    assertEquals(0, second.exitValue(), () -> read(log));

    try (var db = Sqlite.open(path)) {
      assertEquals(4, roomErasures(db));
      assertEquals(0, count(db, "SELECT count(*) FROM rooms"));
      assertEquals(0, count(db, "SELECT count(*) FROM room_messages"));
      assertEquals(
          4,
          count(
              db,
              "SELECT count(DISTINCT entity_id) FROM change_log"
                  + " WHERE kind = 'erasure' AND entity_type = 'room'"),
          "no room was erased twice");
      assertEquals(
          1,
          count(
              db, "SELECT count(*) FROM data_migrations WHERE name = ?", PersonalRoomErasure.NAME));
    }
  }

  /** The second process: the migration runner, resuming what the first left unfinished. */
  public static final class SecondProcess {
    public static void main(String[] args) {
      try (var db = Sqlite.open(Path.of(args[0]))) {
        MigrationRunner.applyAll(
            db,
            List.of(new PersonalRoomErasure(() -> true)),
            DataMigration.Prompter.NON_INTERACTIVE);
      }
    }
  }

  private record Seeded(String room, List<String> messages, List<String> runs) {
    List<String> ids() {
      var ids = new ArrayList<String>(messages);
      ids.addAll(runs);
      ids.add(room);
      return ids;
    }
  }

  /**
   * {@code handle}'s personal room as an older release minted it, with a thread and, when {@code
   * withRun}, a finished room run.
   */
  private static Seeded personalRoom(Sqlite db, String id, String handle, boolean withRun) {
    room(db, id, handle, handle);
    var messages = new MessageStore(db);
    var first = messages.append(id, handle, "hello", null);
    var ids = new ArrayList<>(List.of(first.id()));
    if (withRun) {
      ids.add(messages.append(id, handle, "and again", first.id()).id());
    }
    return new Seeded(id, ids, withRun ? List.of(run(db, id, true)) : List.of());
  }

  /**
   * Rooms {@code creator} made that are not personal rooms: a lobby, one that only shares the
   * prefix, one with a personal room's shape but a wrong fingerprint, and one whose id is M.Day's
   * personal room but which another FDE created.
   */
  private static void ordinaryRooms(Sqlite db, String creator) {
    for (var room : ordinaryRoomIds()) {
      room(db, room, creator, creator);
      new MessageStore(db).append(room, creator, "talk in " + room, null);
    }
  }

  private static List<String> ordinaryRoomIds() {
    return List.of("lobby", "fde-notes", WRONG_FINGERPRINT, M_DAY);
  }

  private static void assertOrdinaryRoomsUntouched(Sqlite db) {
    for (var room : ordinaryRoomIds()) {
      assertTrue(new RoomStore(db).findById(room).isPresent(), room + " was touched");
      assertEquals(1, count(db, "SELECT count(*) FROM room_messages WHERE room_id = ?", room));
      assertEquals(
          0,
          count(
              db,
              "SELECT count(*) FROM change_log WHERE entity_id = ? AND kind = 'erasure'",
              room));
    }
  }

  private static void room(Sqlite db, String id, String creator, String title) {
    new RoomStore(db)
        .createJournaled(
            new RoomStore.RoomRow(
                id, "acme", title, creator, null, null, creator, "t0", "t0", creator));
  }

  private static String run(Sqlite db, String room, boolean finished) {
    var runs = new RunStore(db);
    var id = DateTimeUtils.newId().toString();
    runs.create(
        id, "acme", null, "main", "main", "build", "claude", "b", "t", null, null, "/l", "u");
    db.execute("UPDATE runs SET room_id = ? WHERE id = ?", room, id);
    if (finished) {
      runs.complete(id, "completed", 0);
    }
    return id;
  }

  private void round() throws IOException {
    var replicas = SyncedEntities.replicas(node.db, "node", "node");
    try (var link = SyncBox.connect(main.server(NODE), node)) {
      for (var entity : SyncedEntities.all()) {
        var report = link.reconcile(entity.type(), replicas.get(entity.type()));
        assertNull(report.failure(), entity.type() + " round failed");
      }
    }
  }

  private static void assertGone(Sqlite db, Seeded seeded) {
    assertFalse(new RoomStore(db).findById(seeded.room()).isPresent(), seeded.room());
    assertEquals(
        0, count(db, "SELECT count(*) FROM room_messages WHERE room_id = ?", seeded.room()));
    for (var id : seeded.ids()) {
      assertEquals(0, count(db, "SELECT count(*) FROM runs WHERE id = ?", id));
      assertEquals(
          0,
          count(
              db, "SELECT count(*) FROM change_log WHERE entity_id = ? AND kind <> 'erasure'", id),
          id + " left history behind");
    }
  }

  private static String erasureRev(Sqlite db, String id) {
    return db.queryOne(
            "SELECT rev FROM change_log WHERE entity_id = ? AND kind = 'erasure'",
            row -> row.text(0),
            id)
        .orElseThrow(() -> new AssertionError("no erasure row for " + id));
  }

  private static long roomErasures(Sqlite db) {
    return count(
        db, "SELECT count(*) FROM change_log WHERE kind = 'erasure' AND entity_type = 'room'");
  }

  private static long count(Sqlite db, String sql, Object... args) {
    return db.queryOne(sql, row -> row.integer(0), args).orElseThrow();
  }

  private static String read(Path log) {
    try {
      return Files.readString(log);
    } catch (IOException e) {
      return e.getMessage();
    }
  }
}
