/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.Roster;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.NameValidator;
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
  private static final String RAJESH_ACME = PersonalRooms.idOf("rajesh", "acme");

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
  void theIdIsDeterministicReadableAndValidInTheSharedNamespace() {
    assertEquals(RAJESH_ACME, PersonalRooms.idOf("rajesh", "acme"));
    assertTrue(RAJESH_ACME.startsWith("fde-rajesh-acme-"), RAJESH_ACME);
    assertTrue(PersonalRooms.idOf("M.Day", "acme").startsWith("fde-m-day-acme-"));
    assertDoesNotThrow(() -> NameValidator.requireValidSpecId(RAJESH_ACME));
  }

  @Test
  void distinctPairsWithOneSlugMintDistinctIds() {
    assertNotEquals(PersonalRooms.idOf("M.Day", "acme"), PersonalRooms.idOf("m-day", "acme"));
    assertNotEquals(PersonalRooms.idOf("m-day", "acme"), PersonalRooms.idOf("m", "day-acme"));
  }

  @Test
  void theLongestValidPairStillMintsAValidId() {
    var handle = "H" + "x".repeat(62);
    var project = "p" + "y".repeat(62);

    var id = PersonalRooms.idOf(handle, project);

    assertDoesNotThrow(() -> NameValidator.requireValidSpecId(id));
    assertEquals(80, id.length());
    assertNotEquals(id, PersonalRooms.idOf(handle, "p" + "y".repeat(61) + "z"));
  }

  @Test
  void anInvalidHandleOrProjectFailsLoud() {
    assertThrows(IllegalArgumentException.class, () -> PersonalRooms.idOf("../x", "acme"));
    assertThrows(IllegalArgumentException.class, () -> PersonalRooms.idOf("rajesh", "Acme"));
  }

  @Test
  void ownerOfIsTheCreatorOnlyForTheCreatorsOwnPersonalRoom() {
    assertTrue(PersonalRooms.ensure(rooms, null, rajesh("t0"), acme(DEFINITION)));
    var personal = rooms.findById(RAJESH_ACME).orElseThrow();
    var other =
        new RoomStore.RoomRow(
            "design-talk", "acme", "Design", null, null, null, "rajesh", "t0", "t0", "rajesh");
    var forged =
        new RoomStore.RoomRow(
            RAJESH_ACME, "acme", "rajesh", null, null, null, "uday", "t0", "t0", "uday");
    var anonymous =
        new RoomStore.RoomRow(
            RAJESH_ACME, "acme", "rajesh", null, null, null, null, "t0", "t0", null);
    var agentMade =
        new RoomStore.RoomRow(
            "fde-notes",
            "acme",
            "Notes",
            null,
            null,
            null,
            "codex/run-1",
            "t0",
            "t0",
            "codex/run-1");

    assertEquals("rajesh", PersonalRooms.ownerOf(personal));
    assertNull(PersonalRooms.ownerOf(other));
    assertNull(PersonalRooms.ownerOf(forged), "another creator's room is not this handle's");
    assertNull(PersonalRooms.ownerOf(anonymous));
    assertNull(PersonalRooms.ownerOf(agentMade), "a non-handle creator never throws");
  }

  @Test
  void mintsOneRoomTitledByTheHandleWithTheDefaultAgentSeated() {
    assertTrue(PersonalRooms.ensure(rooms, null, rajesh("2026-08-01T00:00:00Z"), acme(DEFINITION)));

    var room = rooms.findById(RAJESH_ACME).orElseThrow();
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
    assertNull(rooms.findById(RAJESH_ACME).orElseThrow().roster());
  }

  @Test
  void aSpecOwningTheIdRefusesTheMint() {
    var specs = new SpecStore(db);
    specs.create(
        new SpecStore.SpecRow(
            RAJESH_ACME,
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
    assertTrue(rooms.delete(RAJESH_ACME));

    assertFalse(PersonalRooms.ensure(rooms, null, fde, acme(DEFINITION)));
    assertTrue(rooms.findById(RAJESH_ACME).isEmpty(), "the tombstone wins");
  }

  @Test
  void twoBoxesMintIdenticalRevsSoTheFirstSyncMovesNothing() {
    try (var node = open("node.db")) {
      var nodeRooms = new RoomStore(node);
      var fde = rajesh("2026-08-01T00:00:00Z");
      assertTrue(PersonalRooms.ensure(rooms, null, fde, acme(DEFINITION)));
      assertTrue(PersonalRooms.ensure(nodeRooms, null, fde, acme(DEFINITION)));

      assertEquals(
          rooms.latestRev(RAJESH_ACME),
          nodeRooms.latestRev(RAJESH_ACME),
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
