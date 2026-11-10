/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PtyHostAdmissionTest {

  @TempDir Path dir;
  private Path path;
  private PtyHostAdmission admission;

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
          admission = new PtyHostAdmission(path);
        });
  }

  private String refusal(String room, String project, PtyIdentity who) {
    return assertThrows(IOException.class, () -> admission.room(room, project, who)).getMessage();
  }

  @Test
  void theRoomsAssigneeAndAnAdminMayPinASessionThere() {
    assertDoesNotThrow(
        () ->
            admission.room(
                "adas-room",
                "acme",
                new PtyIdentity.Person(new Actor("ada", Role.MEMBER, Actor.Lane.API))));
    assertDoesNotThrow(
        () ->
            admission.room(
                "adas-room",
                "acme",
                new PtyIdentity.Person(new Actor("root", Role.ADMIN, Actor.Lane.API))));
  }

  @Test
  void anotherMemberPinsButAViewerIsRefusedByTheRoomsPostRight() {
    assertDoesNotThrow(
        () ->
            admission.room(
                "adas-room",
                "acme",
                new PtyIdentity.Person(new Actor("mallory", Role.MEMBER, Actor.Lane.API))),
        "every writer of the project may speak in its rooms");
    assertEquals(
        "Your credential is read-only and cannot post in rooms. Ask an admin for a member or admin"
            + " credential.",
        refusal(
            "adas-room",
            "acme",
            new PtyIdentity.Person(new Actor("peek", Role.VIEWER, Actor.Lane.API))),
        "the role the host resolved for the person decides");
  }

  @Test
  void aTerminalOpensInAProjectItsPersonHoldsOnThisBoxAndNoOther() {
    assertDoesNotThrow(
        () ->
            admission.project(
                "acme", new PtyIdentity.Person(new Actor("peek", Role.VIEWER, Actor.Lane.API))),
        "a viewer's terminal stays allowed");
    assertEquals(
        "Project 'acme' is not in the catalog. Create it with 'sail project create acme', or sync"
            + " it from main with 'sail sync'.",
        assertThrows(
                IOException.class,
                () ->
                    admission.project(
                        "acme",
                        new PtyIdentity.Person(
                            new Actor("mallory", Role.MEMBER, Actor.Lane.API)
                                .withScope(ProjectScope.of(List.of("beta"))))))
            .getMessage());
  }

  @Test
  void anUnassignedRoomIsPinnedByEveryWriterOfItsProject() {
    assertDoesNotThrow(
        () ->
            admission.room(
                "open-room",
                "acme",
                new PtyIdentity.Person(new Actor("ada", Role.MEMBER, Actor.Lane.API))));
    assertDoesNotThrow(
        () ->
            admission.room(
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
