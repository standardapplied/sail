/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.Sqlite;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Who may post a message, and as whom. Where is the posting rule's ({@link PostingRule}) over the
 * conversation's owners ({@link RoomStore#owners}). As whom: on this box's lanes the author is the
 * actor's own handle — a run's principal on the agent and room lanes. On {@link Actor.Lane#SYNC}
 * the pusher posts as itself, as a principal of any run it owns, or as the pipeline ({@link
 * MessageStore#SAIL_AUTHOR}) where a run it owns is in the conversation. Messages never change, so
 * there is only ever a create to decide.
 */
public final class MessageAuthority implements WriteAuthority {

  private final RoomStore rooms;
  private final MessageStore messages;
  private final Attribution attribution;

  /** The rule, deciding on {@code db}'s copy. */
  public MessageAuthority(Sqlite db) {
    this.rooms = new RoomStore(db);
    this.messages = new MessageStore(db);
    this.attribution = new Attribution(db);
  }

  @Override
  public Optional<Refusal> decide(
      Actor actor, String id, Map<String, Object> held, Map<String, Object> next) {
    if (WriteAuthority.decided(actor) || next == null) {
      return Optional.empty();
    }
    var roomId = MessageStore.roomIdOf(next);
    var posting = PostingRule.decide(actor, roomId, rooms.owners(roomId));
    if (posting.isPresent()) {
      return posting;
    }
    var author = Snapshots.text(next, "author");
    if (!mayPostAs(actor, author, roomId)) {
      return Refusal.of(
          Refusal.Kind.NOT_AUTHOR,
          "'" + actor.handle() + "' may not post as '" + author + "' in this room.",
          "Post as yourself.");
    }
    return Optional.empty();
  }

  private boolean mayPostAs(Actor actor, String author, String roomId) {
    if (Objects.equals(author, actor.handle())) {
      return true;
    }
    if (actor.lane() != Actor.Lane.SYNC || author == null) {
      return false;
    }
    if (MessageStore.SAIL_AUTHOR.equals(author)) {
      return messages.ranInConversation(actor.handle(), roomId);
    }
    return RunStore.isPrincipalHandle(author)
        && attribution.principalOfOwnedRun(actor.handle(), author);
  }
}
