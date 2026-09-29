/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.Sqlite;
import java.util.Map;
import java.util.Optional;

/**
 * Who may write a room. Any writer creates one; every later revision and its tombstone — roster,
 * wake, title and assignee alike — is its owner's ({@link RoomStore#ownerOf}) or an admin's. Owning
 * a spec born in a room gives a voice there ({@link PostingRule}), never its settings.
 */
public final class RoomAuthority implements WriteAuthority {

  private final RoomStore rooms;
  private final Attribution attribution;

  public RoomAuthority(Sqlite db) {
    this.rooms = new RoomStore(db);
    this.attribution = new Attribution(db);
  }

  @Override
  public Optional<Refusal> decide(
      Actor actor, String id, Map<String, Object> held, Map<String, Object> next) {
    if (WriteAuthority.decided(actor)) {
      return Optional.empty();
    }
    if (!actor.canWrite()) {
      return Refusal.readOnly(held == null ? "create rooms" : "change rooms");
    }
    var attributed = attribution.decide(actor, held, next, "created_by", null);
    if (attributed.isPresent() || held == null) {
      return attributed;
    }
    var owner = rooms.ownerOf(id, held);
    if (actor.isAdmin() || actor.actsFor(owner)) {
      return Optional.empty();
    }
    if (Strings.isBlank(owner)) {
      return Refusal.of(
          Refusal.Kind.NOT_OWNER,
          "Room '" + id + "' has no owner, so only an admin may change it.",
          "Have an admin change it.");
    }
    return Refusal.of(
        Refusal.Kind.NOT_OWNER,
        "Room '" + id + "' belongs to '" + owner + "', not you.",
        "Ask " + owner + " to change it, or have an admin do it.");
  }
}
