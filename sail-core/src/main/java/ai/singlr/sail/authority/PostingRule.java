/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import java.util.List;
import java.util.Optional;

/**
 * Who may post in a conversation, whose owners are {@code RoomStore.owners}: on every lane but the
 * room lane, any writer who acts for an FDE, whoever owns the conversation, since every FDE who may
 * write in a project may speak in it; which projects those are is {@link ScopeRule}'s to decide,
 * ahead of this rule. A room principal is read-only everywhere else, so the lane's one write
 * carries its own rule: it may post exactly when it acts for an owner, the FDE whose box woke it.
 * The rule behind every post, a born-in spec's birth and claim, the terminal door's pin, and main's
 * decision on a synced message.
 */
public final class PostingRule {

  private PostingRule() {}

  /**
   * Why {@code actor} may not post in {@code conversationId}, which this box {@code holds} and
   * {@code owners} own.
   */
  public static Optional<Refusal> decide(
      Actor actor, String conversationId, boolean holds, List<String> owners) {
    if (!actor.roomLane()) {
      if (!actor.canWrite()) {
        return Refusal.readOnly("change specs");
      }
      if (actor.isAdmin()) {
        return Optional.empty();
      }
      if (!holds) {
        return Refusal.of(
            Refusal.Kind.NO_CONVERSATION,
            "No conversation '"
                + conversationId
                + "' is on this box: it was deleted, or never here.",
            "Check the spec or room id.");
      }
      if (Strings.isNotBlank(actor.actingFde())) {
        return Optional.empty();
      }
      return Refusal.of(
          Refusal.Kind.NOT_AUTHOR,
          "This credential acts for no FDE, so a post in '"
              + conversationId
              + "' would be no one's.",
          "Post with an FDE's own credential.");
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
