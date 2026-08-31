/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.AccessDecision;
import ai.singlr.sail.api.Actor;
import ai.singlr.sail.api.Role;
import ai.singlr.sail.api.SpecPolicy;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyRooms;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The production {@link PtyRooms}: the room is read from the control plane, must sit in the
 * session's project, and the caller — the authenticated FDE, with the role the roster gives them,
 * never a client claim — must pass the room's post right ({@link SpecPolicy#post}), exactly the
 * gate a room message passes. Only then may the room id enter the child's environment or the room's
 * event history.
 */
final class PtyHostRooms implements PtyRooms {

  private final Path dbPath;

  PtyHostRooms() {
    this(SailPaths.controlPlaneDb());
  }

  PtyHostRooms(Path dbPath) {
    this.dbPath = dbPath;
  }

  @Override
  public void admit(String roomId, String project, PtyIdentity who) throws IOException {
    try (var db = Sqlite.open(dbPath)) {
      var room =
          new RoomStore(db)
              .findById(roomId)
              .orElseThrow(() -> new IOException("Room '" + roomId + "' was not found."));
      if (!Objects.equals(room.project(), project)) {
        throw new IOException(
            "Room '"
                + roomId
                + "' belongs to project '"
                + room.project()
                + "'; open the session with --project "
                + room.project()
                + ".");
      }
      var role =
          new FdeStore(db)
              .byHandle(who.fde())
              .map(fde -> Role.fromAttribute(fde.role()))
              .orElse(Role.VIEWER);
      var actor = new Actor(who.fde(), role, Actor.Lane.API);
      if (SpecPolicy.post(actor, room.id(), room.assignee(), room.createdBy())
          instanceof AccessDecision.Refused refused) {
        throw new IOException(refused.message() + " " + refused.fix());
      }
    }
  }
}
