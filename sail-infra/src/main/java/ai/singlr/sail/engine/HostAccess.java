/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.Scoped;
import ai.singlr.sail.authority.PostingRule;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.util.Objects;

/**
 * The terminal door: who a PTY session acts as, and whether it may pin a room. The caller is the
 * FDE a session token names, or with no token this box's FDE, and acts with the role {@link
 * RoleRule} gives it; a disabled FDE is refused.
 */
public record HostAccess(Sqlite db, RoleRule roles, OperatorCommand operator) {
  public PtyIdentity identity(String token, String boxHandle) throws IOException {
    if (Strings.isBlank(token)) {
      if (Strings.isBlank(boxHandle)) {
        throw new IOException(
            "This box has no FDE identity. Set one with '"
                + operator.sail("host config set sync-handle <handle>")
                + "' or connect through the gateway.");
      }
      return new PtyIdentity(boxHandle, roleOf(boxHandle) == Role.ADMIN);
    }
    var session =
        new AuthSessionStore(db)
            .validate(token)
            .orElseThrow(() -> new IOException("Session token is not valid or has expired."));
    var fde =
        new FdeStore(db)
            .byId(session.fdeId())
            .orElseThrow(() -> new IOException("The session's FDE no longer exists."));
    return new PtyIdentity(fde.handle(), roleOf(fde.handle()) == Role.ADMIN);
  }

  /**
   * Admits {@code who} into {@code roomId}. A room of a project outside the FDE's scope is refused
   * as one that does not exist, before anything names its project.
   */
  public void admit(String roomId, String project, PtyIdentity who) throws IOException {
    var actor = actorOf(who.fde());
    var room =
        Scoped.row(actor, new RoomStore(db).findById(roomId), RoomStore.RoomRow::project)
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
    var refused = PostingRule.decide(actor, room.id(), new RoomStore(db).owners(room.id()));
    if (refused.isPresent()) {
      throw new IOException(refused.get().message() + " " + refused.get().fix());
    }
  }

  private Role roleOf(String handle) throws IOException {
    return actorOf(handle).role();
  }

  private Actor actorOf(String handle) throws IOException {
    var actor = roles.actorOf(handle, Role.ADMIN, Actor.Lane.API);
    if (actor.isEmpty()) {
      throw new IOException(
          "FDE '"
              + handle
              + "' is disabled or not in this box's roster. Ask an admin to re-enable it, or run '"
              + operator.sail("sync")
              + "' on a node that has not synced its roster.");
    }
    return actor.get();
  }
}
