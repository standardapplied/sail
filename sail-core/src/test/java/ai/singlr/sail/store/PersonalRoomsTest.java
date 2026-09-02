/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.Roster;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.sync.StoreReplica;
import ai.singlr.sail.sync.SyncEngine;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersonalRoomsTest {

  private static final String DEFINITION = "name: acme\nagent:\n  type: claude-code\n";

  @TempDir Path tempDir;
  private Sqlite db;
  private RoomStore rooms;
  private FdeStore fdes;
  private ProjectStore projects;

  @BeforeEach
  void setUp() {
    db = open("main.db");
    rooms = new RoomStore(db);
    fdes = new FdeStore(db);
    projects = new ProjectStore(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private Sqlite open(String name) {
    var opened = Sqlite.open(tempDir.resolve(name));
    new SchemaManager(opened).migrate();
    return opened;
  }

  private static FdeStore.Fde rajesh(String createdAt) {
    return new FdeStore.Fde("id-1", "rajesh", "Rajesh", null, "member", "active", createdAt);
  }

  private static ProjectStore.ProjectRow acme(String definition) {
    return new ProjectStore.ProjectRow("acme", definition, "uday", "t0", "uday", "t0");
  }

  @Test
  void theIdIsDeterministicAndValidInTheSharedNamespace() {
    assertEquals("fde-rajesh-acme", PersonalRooms.idOf("rajesh", "acme"));
    assertEquals("fde-m-day-acme", PersonalRooms.idOf("M.Day", "acme"));
    assertTrue(PersonalRooms.isPersonal("fde-rajesh-acme", "rajesh", "acme"));
    assertFalse(PersonalRooms.isPersonal("fde-rajesh-acme", "uday", "acme"));
    assertFalse(PersonalRooms.isPersonal(null, "rajesh", "acme"));
  }

  @Test
  void mintsOneRoomTitledByTheHandleWithTheDefaultAgentSeated() {
    assertTrue(PersonalRooms.ensure(rooms, null, rajesh("2026-08-01T00:00:00Z"), acme(DEFINITION)));

    var room = rooms.findById("fde-rajesh-acme").orElseThrow();
    assertEquals("rajesh", room.title());
    assertEquals("rajesh", room.assignee());
    assertEquals("acme", room.project());
    assertNull(room.wake(), "wake stays unset so the roster derives it");
    var seated = Roster.fromJson(room.roster()).standing();
    assertEquals("claude-code", seated.agent());
    assertEquals("full", seated.mode());
    assertEquals("2026-08-01T00:00:00Z", seated.engagedAt());
    assertEquals("2026-08-01T00:00:00Z", room.createdAt());
    assertEquals("rajesh", room.createdBy());
  }

  @Test
  void mintingTwiceIsANoOp() {
    var fde = rajesh("2026-08-01T00:00:00Z");
    assertTrue(PersonalRooms.ensure(rooms, null, fde, acme(DEFINITION)));
    assertFalse(PersonalRooms.ensure(rooms, null, fde, acme(DEFINITION)));
    assertEquals(1, rooms.list("acme").size());
  }

  @Test
  void aProjectWithoutAnAgentBlockMintsAnEmptyRoster() {
    assertTrue(PersonalRooms.ensure(rooms, null, rajesh("t0"), acme("name: acme\n")));
    assertNull(rooms.findById("fde-rajesh-acme").orElseThrow().roster());
  }

  @Test
  void aSpecOwningTheIdRefusesTheMint() {
    var specs = new SpecStore(db);
    specs.create(
        new SpecStore.SpecRow(
            "fde-rajesh-acme",
            "acme",
            "Impostor",
            SpecStatus.DRAFT,
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            "",
            "",
            null,
            List.of(),
            List.of()));
    assertFalse(PersonalRooms.ensure(rooms, specs, rajesh("t0"), acme(DEFINITION)));
  }

  @Test
  void aDeletedPersonalRoomStaysDeleted() {
    var fde = rajesh("2026-08-01T00:00:00Z");
    PersonalRooms.ensure(rooms, null, fde, acme(DEFINITION));
    assertTrue(rooms.delete("fde-rajesh-acme"));

    assertFalse(PersonalRooms.ensure(rooms, null, fde, acme(DEFINITION)));
    assertTrue(rooms.findById("fde-rajesh-acme").isEmpty(), "the tombstone wins");
  }

  @Test
  void twoBoxesMintIdenticalRevsSoTheFirstSyncMovesNothing() {
    try (var node = open("node.db")) {
      var nodeRooms = new RoomStore(node);
      var fde = rajesh("2026-08-01T00:00:00Z");
      assertTrue(PersonalRooms.ensure(rooms, null, fde, acme(DEFINITION)));
      assertTrue(PersonalRooms.ensure(nodeRooms, null, fde, acme(DEFINITION)));

      assertEquals(
          rooms.latestRev("fde-rajesh-acme"),
          nodeRooms.latestRev("fde-rajesh-acme"),
          "identical inputs mint an identical content-hash rev on every box");

      var report =
          new SyncEngine()
              .reconcile(
                  new StoreReplica(
                      "node",
                      nodeRooms,
                      new ChangeLog(node),
                      new SyncConflicts(node),
                      new SyncState(node)),
                  new StoreReplica(
                      "main", rooms, new ChangeLog(db), new SyncConflicts(db), new SyncState(db)));

      assertEquals(0, report.total(), "no duplicate room, no conflict");
      assertEquals(1, rooms.list("acme").size());
      assertEquals(1, nodeRooms.list("acme").size());
    }
  }
}
