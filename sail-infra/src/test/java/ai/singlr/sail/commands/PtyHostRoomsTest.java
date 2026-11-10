/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PtyHostRoomsTest {

  @TempDir Path dir;
  private Path path;
  private PtyHostRooms rooms;

  @BeforeEach
  void seed() {
    Acting.system(
        () -> {
          path = dir.resolve("cp.db");
          try (var db = Sqlite.open(path)) {
            new SchemaManager(db).migrate();
            HeldProjects.hold(db, "acme");
            var fdes = new FdeStore(db);
            fdes.add("ada", "Ada", null, "member");
            fdes.add("mallory", "Mallory", null, "member");
            fdes.add("root", "Root", null, "admin");
            fdes.add("peek", "Peek", null, "viewer");
            new RoomStore(db)
                .create(
                    new RoomStore.RoomRow(
                        "adas-room",
                        "acme",
                        "Ada's room",
                        "ada",
                        "on",
                        null,
                        "ada",
                        null,
                        null,
                        "ada"));
            Acting.as(
                "ada",
                () ->
                    new RoomStore(db)
                        .create(
                            new RoomStore.RoomRow(
                                "open-room",
                                "acme",
                                "Unassigned",
                                null,
                                "on",
                                null,
                                null,
                                null,
                                null,
                                null)));
          }
          rooms = new PtyHostRooms(path);
        });
  }

  private String refusal(String room, String project, PtyIdentity who) {
    return assertThrows(IOException.class, () -> rooms.admit(room, project, who)).getMessage();
  }

  @Test
  void theRoomsAssigneeAndAnAdminMayPinASessionThere() {
    assertDoesNotThrow(
        () ->
            rooms.admit(
                "adas-room",
                "acme",
                new PtyIdentity.Person(new Actor("ada", Role.MEMBER, Actor.Lane.API))));
    assertDoesNotThrow(
        () ->
            rooms.admit(
                "adas-room",
                "acme",
                new PtyIdentity.Person(new Actor("root", Role.ADMIN, Actor.Lane.API))));
  }

  @Test
  void anotherMemberPinsButAViewerAndAStrangerAreRefusedByTheRoomsPostRight() {
    assertDoesNotThrow(
        () ->
            rooms.admit(
                "adas-room",
                "acme",
                new PtyIdentity.Person(new Actor("mallory", Role.MEMBER, Actor.Lane.API))),
        "every writer of the project may speak in its rooms");
    assertTrue(
        refusal(
                "adas-room",
                "acme",
                new PtyIdentity.Person(new Actor("peek", Role.MEMBER, Actor.Lane.API)))
            .contains("read-only"),
        "a viewer's roster role, not the client's claim, decides");
    assertTrue(
        refusal(
                "adas-room",
                "acme",
                new PtyIdentity.Person(new Actor("nobody", Role.MEMBER, Actor.Lane.API)))
            .contains("not in this box's roster"),
        "a handle absent from the roster is refused");
    assertTrue(
        refusal(
                "adas-room",
                "acme",
                new PtyIdentity.Person(new Actor("peek", Role.ADMIN, Actor.Lane.API)))
            .contains("read-only"),
        "the roster's role decides, never an admin bit riding the identity");
  }

  @Test
  void aDisabledFdeIsRefused() throws Exception {
    try (var db = Sqlite.open(path)) {
      db.execute("UPDATE fdes SET status = 'disabled' WHERE handle = 'ada'");
    }

    assertTrue(
        refusal(
                "adas-room",
                "acme",
                new PtyIdentity.Person(new Actor("ada", Role.MEMBER, Actor.Lane.API)))
            .contains("disabled"));
  }

  @Test
  void anUnassignedRoomIsPinnedByEveryWriterOfItsProject() {
    assertDoesNotThrow(
        () ->
            rooms.admit(
                "open-room",
                "acme",
                new PtyIdentity.Person(new Actor("ada", Role.MEMBER, Actor.Lane.API))));
    assertDoesNotThrow(
        () ->
            rooms.admit(
                "open-room",
                "acme",
                new PtyIdentity.Person(new Actor("mallory", Role.MEMBER, Actor.Lane.API))));
  }

  @Test
  void aMissingRoomOrTheWrongProjectIsRefusedBeforeAnyRightIsConsulted() {
    assertTrue(
        refusal(
                "ghost",
                "acme",
                new PtyIdentity.Person(new Actor("root", Role.ADMIN, Actor.Lane.API)))
            .contains("not found"));
    var elsewhere =
        refusal(
            "adas-room",
            "beta",
            new PtyIdentity.Person(new Actor("root", Role.ADMIN, Actor.Lane.API)));
    assertTrue(elsewhere.contains("--project acme"), elsewhere);
    var unprojected =
        refusal(
            "adas-room", "", new PtyIdentity.Person(new Actor("root", Role.ADMIN, Actor.Lane.API)));
    assertTrue(unprojected.contains("--project acme"), unprojected);
  }
}
