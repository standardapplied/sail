/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.access.Decision;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.api.Refusals;
import ai.singlr.sail.authority.MessageAuthority;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.util.Objects;

/**
 * The terminal door: who a PTY session acts as, and where it may open. The caller is the FDE a
 * session token names, on the API lane as over HTTP, or with no token this box's own person, on the
 * CLI lane as at its root CLI; {@link RoleRule} resolves either, its role and its scope, and
 * refuses a disabled FDE. A session is pinned only to a room the caller may post in; which projects
 * it opens in is the terminal facet's to say, through the project gate.
 */
public record HostAccess(Sqlite db, RoleRule roles) {
  public PtyIdentity.Person identity(String token) throws IOException {
    if (Strings.isBlank(token)) {
      var handle =
          roles
              .boxFde()
              .orElseThrow(
                  () ->
                      Remedied.io(
                          "This box has no FDE identity.",
                          Remedy.of(
                              "Set one with '",
                              Remedy.sail("host config set sync-handle", "<handle>"),
                              "' or connect through the gateway.")));
      return new PtyIdentity.Person(
          roles.principal(RoleRule.Credential.box()).orElseThrow(() -> disabled(handle)));
    }
    var session =
        new AuthSessionStore(db)
            .validate(token)
            .orElseThrow(() -> new IOException("Session token is not valid or has expired."));
    var fde =
        new FdeStore(db)
            .byId(session.fdeId())
            .orElseThrow(() -> new IOException("The session's FDE no longer exists."));
    return new PtyIdentity.Person(
        roles
            .principal(RoleRule.Credential.person(fde.handle(), Actor.Lane.API))
            .orElseThrow(() -> disabled(fde.handle())));
  }

  /**
   * Admits {@code who} into {@code roomId} when it may post in the room's conversation ({@link
   * MessageAuthority#post}). A room of a project outside the FDE's scope is not found, as one this
   * box does not hold, before anything names its project; then a session opened under another
   * project is told the room's; then a refusal is said as the pty host says one.
   */
  public void admit(String roomId, String project, PtyIdentity who) throws IOException {
    var actor = actorOf(who);
    var rooms = new RoomStore(db);
    var notFound = new IOException(Refusals.roomMissing(roomId));
    var room = rooms.findById(roomId).orElseThrow(() -> notFound);
    var decision =
        MessageAuthority.post(
            actor, Resources.conversation(room.id(), room.project(), rooms.owners(room.id())));
    if (decision instanceof Decision.NotFound) {
      throw notFound;
    }
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
    if (decision instanceof Decision.Refused(var refusal)) {
      throw Remedied.io(refusal.message(), refusal.fix());
    }
  }

  /** The actor a terminal acts as: a person's; the host's dispatch authority opens none. */
  public static Actor actorOf(PtyIdentity who) throws IOException {
    if (who instanceof PtyIdentity.Person(var actor)) {
      return actor;
    }
    throw new IOException("The host's dispatch authority opens no terminal.");
  }

  private static IOException disabled(String handle) {
    return Remedied.io(
        "FDE '" + handle + "' is disabled or not in this box's roster.",
        Remedy.of(
            "Ask an admin to re-enable it, or run '",
            Remedy.sail("sync"),
            "' on a node that has not synced its roster."));
  }
}
