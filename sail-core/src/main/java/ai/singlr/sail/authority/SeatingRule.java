/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.identity.Actor;
import java.util.List;
import java.util.Optional;

/**
 * Who may start work in a room: be born in it, or claim a spec born there. Work seated in a room
 * makes its owner one of the room's conversation owners ({@code RoomStore.owners}), whose room
 * agent speaks there and who drives the room's events, so it stays with those owners and an admin,
 * however wide posting is ({@link PostingRule}).
 */
public final class SeatingRule {

  private SeatingRule() {}

  /**
   * Why {@code actor} may not seat work in room {@code roomId}, which this box {@code holds} and
   * {@code owners} own.
   */
  public static Optional<Refusal> decide(
      Actor actor, String roomId, boolean holds, List<String> owners) {
    if (!actor.canWrite()) {
      return Refusal.readOnly("change specs");
    }
    if (actor.isAdmin()) {
      return Optional.empty();
    }
    if (!holds) {
      return Refusal.of(
          Refusal.Kind.NO_CONVERSATION,
          "No conversation '" + roomId + "' is on this box: it was deleted, or never here.",
          "Check the spec or room id.");
    }
    if (owners.stream().anyMatch(actor::actsFor)) {
      return Optional.empty();
    }
    if (owners.isEmpty()) {
      return Refusal.of(
          Refusal.Kind.NOT_OWNER,
          "No one owns '" + roomId + "' yet, so only an admin may start work there.",
          "Have an admin create or assign the spec.");
    }
    var named = String.join(" or ", owners);
    return Refusal.of(
        Refusal.Kind.NOT_OWNER,
        "'"
            + roomId
            + "' belongs to "
            + named
            + ": only "
            + named
            + " or an admin may start work there, not you.",
        "Ask " + named + " to create it there, or have an admin do it.");
  }
}
