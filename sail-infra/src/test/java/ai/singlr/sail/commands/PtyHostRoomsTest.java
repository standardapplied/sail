/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.store.FdeStore;
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
    path = dir.resolve("cp.db");
    try (var db = Sqlite.open(path)) {
      new SchemaManager(db).migrate();
      var fdes = new FdeStore(db);
      fdes.add("ada", "Ada", null, "member");
      fdes.add("mallory", "Mallory", null, "member");
      fdes.add("root", "Root", null, "admin");
      fdes.add("peek", "Peek", null, "viewer");
      new RoomStore(db)
          .create(
              new RoomStore.RoomRow(
                  "adas-room", "acme", "Ada's room", "ada", "on", null, "ada", null, null, "ada"));
    }
    rooms = new PtyHostRooms(path);
  }

  private String refusal(String room, String project, PtyIdentity who) {
    return assertThrows(IOException.class, () -> rooms.admit(room, project, who)).getMessage();
  }

  @Test
  void theRoomsAssigneeAndAnAdminMayPinASessionThere() {
    assertDoesNotThrow(() -> rooms.admit("adas-room", "acme", new PtyIdentity("ada", false)));
    assertDoesNotThrow(() -> rooms.admit("adas-room", "acme", new PtyIdentity("root", true)));
  }

  @Test
  void anotherMemberAViewerAndAStrangerAreRefusedByTheRoomsPostRight() {
    assertTrue(refusal("adas-room", "acme", new PtyIdentity("mallory", false)).contains("not you"));
    assertTrue(
        refusal("adas-room", "acme", new PtyIdentity("peek", false)).contains("read-only"),
        "a viewer's roster role, not the client's claim, decides");
    assertTrue(
        refusal("adas-room", "acme", new PtyIdentity("nobody", false)).contains("read-only"),
        "a handle absent from the roster fails closed");
    assertTrue(
        refusal("adas-room", "acme", new PtyIdentity("mallory", true)).contains("not you"),
        "the roster's role decides, never an admin bit riding the identity");
  }

  @Test
  void aMissingRoomOrTheWrongProjectIsRefusedBeforeAnyRightIsConsulted() {
    assertTrue(refusal("ghost", "acme", new PtyIdentity("root", true)).contains("not found"));
    var elsewhere = refusal("adas-room", "beta", new PtyIdentity("root", true));
    assertTrue(elsewhere.contains("--project acme"), elsewhere);
    var unprojected = refusal("adas-room", "", new PtyIdentity("root", true));
    assertTrue(unprojected.contains("--project acme"), unprojected);
  }
}
