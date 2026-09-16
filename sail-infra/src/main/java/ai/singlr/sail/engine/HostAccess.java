/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.AccessDecision;
import ai.singlr.sail.api.Actor;
import ai.singlr.sail.api.Role;
import ai.singlr.sail.api.SpecPolicy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.util.Objects;

public record HostAccess(Sqlite db) {
  public PtyIdentity identity(String token, String boxHandle) throws IOException {
      var fdes = new FdeStore(db);
      if (Strings.isBlank(token)) {
        var handle = boxHandle;
        if (Strings.isBlank(handle)) {
          throw new IOException(
              "This box has no FDE identity. Set one with 'sail host config set sync-handle' or"
                  + " connect through the gateway.");
        }
        return new PtyIdentity(handle, isAdmin(fdes, handle));
      }
      var session =
          new AuthSessionStore(db)
              .validate(token)
              .orElseThrow(() -> new IOException("Session token is not valid or has expired."));
      var fde =
          fdes.byId(session.fdeId())
              .orElseThrow(() -> new IOException("The session's FDE no longer exists."));
      return new PtyIdentity(fde.handle(), "admin".equals(fde.role()));
  }

  private static boolean isAdmin(FdeStore fdes, String handle) {
    return fdes.byHandle(handle).map(fde -> "admin".equals(fde.role())).orElse(false);
  }

  public void admit(String roomId, String project, PtyIdentity who) throws IOException {
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
