/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.identity.Actor;
import java.util.List;
import java.util.Optional;

/**
 * Who may post in a conversation, whose owners are {@code RoomStore.owners}: every lane but the
 * room lane posts under the write rule, a writer who is an admin or acts for an owner. A room
 * principal is read-only everywhere else, so the lane's one write carries its own rule: it may post
 * exactly when it acts for an owner, the FDE whose box woke it. The rule behind every post, a
 * born-in spec's birth and claim, the terminal door's pin, and main's decision on a synced message.
 */
public final class PostingRule {

  private PostingRule() {}

  /** Why {@code actor} may not post in {@code conversationId}, owned by {@code owners}. */
  public static Optional<Refusal> decide(Actor actor, String conversationId, List<String> owners) {
    if (!actor.roomLane()) {
      if (!actor.canWrite()) {
        return Refusal.readOnly("change specs");
      }
      if (actor.isAdmin()) {
        return Optional.empty();
      }
    }
    if (owners.stream().anyMatch(actor::actsFor)) {
      return Optional.empty();
    }
    if (owners.isEmpty()) {
      return Refusal.of(
          Refusal.Kind.NOT_OWNER,
          "No one owns '" + conversationId + "' yet, so only an admin may post there.",
          "Claim its spec first with --assignee <you>, or have an admin post.");
    }
    var named = String.join(" or ", owners);
    return Refusal.of(
        Refusal.Kind.NOT_OWNER,
        "'"
            + conversationId
            + "' belongs to "
            + named
            + ": only "
            + named
            + " or an admin may post there, not you.",
        "Ask " + named + " to post it, or have an admin do it.");
  }
}
