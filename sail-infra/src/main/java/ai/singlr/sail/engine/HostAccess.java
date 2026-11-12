/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Decision;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.api.Refusals;
import ai.singlr.sail.api.TokenAuth;
import ai.singlr.sail.authority.MessageAuthority;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.io.IOException;
import java.util.Objects;

/**
 * The terminal door: who a PTY session acts as, and where it may open. The caller of a unix
 * connection is the FDE a session token names, on the API lane as over HTTP, or with no token this
 * box's own person, on the CLI lane as at its root CLI. The holder of a ticket is the FDE the API
 * caller's credential names, a login session or an API token bound to an FDE and capped as minted,
 * found by its hash ({@link #holder}). {@link RoleRule} resolves each, its role and its scope, and
 * refuses a disabled FDE. A terminal opens only on a box its caller operates ({@link #box}), the
 * question attaching asks of a session's opener; a session is pinned only to a room the caller may
 * post in; which projects it opens in is the terminal facet's to say, through the project gate.
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
        roles
            .session(new AuthSessionStore(db), token, Actor.Lane.API)
            .orElseThrow(HostAccess::invalid);
    return new PtyIdentity.Person(
        session.principal().orElseThrow(() -> disabled(session.fde().handle())));
  }

  /**
   * The person behind the credential whose hash is {@code credentialHash}: a login session, or an
   * API token bound to an FDE, capped as it was minted, each on the API lane as over HTTP. A token
   * bound to no FDE is refused: the host's own token is the box's person, who exists only on the
   * unix socket, and a machine token acts for no one.
   */
  public PtyIdentity.Person holder(String credentialHash) throws IOException {
    var session =
        roles.session(new AuthSessionStore(db).validateHash(credentialHash), Actor.Lane.API);
    if (session.isPresent()) {
      return new PtyIdentity.Person(
          session.get().principal().orElseThrow(() -> disabled(session.get().fde().handle())));
    }
    var minted =
        new TokenStore(db)
            .validateHash(credentialHash)
            .orElseThrow(() -> new IOException("The credential is not valid or has expired."));
    if (minted.fdeHandle() == null) {
      throw new IOException(
          "Token '"
              + minted.name()
              + "' is bound to no FDE, so it is issued no ticket: the host's own token is the box's"
              + " person, who exists only on the unix socket, and a machine token acts for no one."
              + " Use a token of your own.");
    }
    return new PtyIdentity.Person(
        roles
            .principal(TokenAuth.credential(minted))
            .orElseThrow(() -> disabled(minted.fdeHandle())));
  }

  /**
   * Refuses {@code who} a terminal on this box unless it operates the box ({@link
   * Action#OPERATE_BOX}): its own person, or an admin. The same question an attach asks of the box
   * of the session's opener, here of this box's.
   */
  public void box(PtyIdentity.Person who) throws IOException {
    if (Access.decide(who.actor(), Action.OPERATE_BOX, Resources.box(roles.boxFde().orElse(null)))
        instanceof Decision.Refused(var refusal)) {
      throw Remedied.io(refusal.message(), refusal.fix());
    }
  }

  private static IOException invalid() {
    return new IOException("Session token is not valid or has expired.");
  }

  /**
   * Admits {@code who} into {@code roomId} when it may post in the room's conversation ({@link
   * MessageAuthority#post}). A room of a project outside the FDE's scope is not found, as one this
   * box does not hold, before anything names its project; then a session opened under another
   * project is told the room's; then a refusal is said as the pty host says one.
   */
  public void admit(String roomId, String project, PtyIdentity.Person who) throws IOException {
    var actor = who.actor();
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

  private static IOException disabled(String handle) {
    return Remedied.io(
        "FDE '" + handle + "' is disabled or not in this box's roster.",
        Remedy.of(
            "Ask an admin to re-enable it, or run '",
            Remedy.sail("sync"),
            "' on a node that has not synced its roster."));
  }
}
