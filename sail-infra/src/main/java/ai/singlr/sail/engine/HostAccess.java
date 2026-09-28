/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.AccessDecision;
import ai.singlr.sail.api.SpecPolicy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.util.Objects;

/**
 * The terminal door: who a PTY session acts as, and whether it may pin a room. The caller is the
 * FDE a session token names, or with no token this box's FDE, and acts with the role {@link
 * RoleRule} gives it; a disabled FDE is refused.
 */
public record HostAccess(Sqlite db, RoleRule roles) {
  public PtyIdentity identity(String token, String boxHandle) throws IOException {
    if (Strings.isBlank(token)) {
      if (Strings.isBlank(boxHandle)) {
        throw new IOException(
            "This box has no FDE identity. Set one with 'sail host config set sync-handle' or"
                + " connect through the gateway.");
      }
      return new PtyIdentity(boxHandle, roleOf(boxHandle) == Role.ADMIN);
    }
    var session =
        new AuthSessionStore(db)
            .validate(token)
            .orElseThrow(() -> new IOException("Session token is not valid or has expired."));
    var fde =
        roles
            .roster()
            .byId(session.fdeId())
            .orElseThrow(() -> new IOException("The session's FDE no longer exists."));
    return new PtyIdentity(fde.handle(), roleOf(fde.handle()) == Role.ADMIN);
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
    var actor = new Actor(who.fde(), roleOf(who.fde()), Actor.Lane.API);
    if (Actor.call(actor, () -> SpecPolicy.post(room.id(), room.assignee(), room.createdBy()))
        instanceof AccessDecision.Refused refused) {
      throw new IOException(refused.message() + " " + refused.fix());
    }
  }

  private Role roleOf(String handle) throws IOException {
    var role = roles.roleOf(handle, Role.ADMIN);
    if (role.isEmpty()) {
      throw new IOException(
          "FDE '"
              + handle
              + "' is disabled or not in this box's roster. Ask an admin to re-enable it, or run"
              + " 'sail sync' on a node that has not synced its roster.");
    }
    return role.get();
  }
}
