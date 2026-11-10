/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Decision;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.Sqlite;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Who may post a message, and as whom. A message is a {@link Action#POST} in its conversation,
 * whose owners are {@link RoomStore#owners}, decided by {@link Access}; messages never change, so
 * there is only ever a create to decide. Then the facts. A conversation this box does not hold has
 * no owner here, so a post in it is refused unless the actor may change what no one owns. A post is
 * someone's: a credential acting for no FDE posts nothing. As whom: on this box's lanes the author
 * is the actor's own handle — a run's principal on the agent and room lanes. On {@link
 * Actor.Lane#SYNC} the pusher posts as itself, as a principal of any run it owns, or as the
 * pipeline ({@link MessageStore#SAIL_AUTHOR}) where a run it owns is in the conversation and it
 * steers the agents there itself ({@link RoomStore#steering}), since the pipeline's words steer
 * them all: a box whose work was reassigned narrates there no more.
 */
public final class MessageAuthority implements WriteAuthority {

  private final RoomStore rooms;
  private final RunStore runs;
  private final ChangeLog changeLog;
  private final Attribution attribution;

  /** The rule, deciding on {@code db}'s copy. */
  public MessageAuthority(Sqlite db) {
    this.rooms = new RoomStore(db);
    this.runs = new RunStore(db);
    this.changeLog = new ChangeLog(db);
    this.attribution = new Attribution(db);
  }

  @Override
  public Optional<Refusal> decide(
      Actor actor, String id, Map<String, Object> held, Map<String, Object> next) {
    if (WriteAuthority.decided(actor) || next == null) {
      return Optional.empty();
    }
    var roomId = MessageStore.roomIdOf(next);
    var conversation =
        Resources.conversation(
            roomId, changeLog.projectOf("message", id, next), rooms.owners(roomId));
    var posting = Refusal.of(Access.decide(actor, Action.POST, conversation), conversation);
    if (posting.isPresent()) {
      return posting;
    }
    if (!rooms.holds(roomId, null)
        && !(Access.decide(actor, Action.CHANGE, conversation) instanceof Decision.Allowed)) {
      return Refusal.noConversation(roomId);
    }
    if (Strings.isBlank(actor.actingFde())) {
      return Refusal.postedByNoOne(roomId);
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
      return runs.ranInConversation(actor.handle(), roomId) && rooms.steers(roomId, actor.handle());
    }
    return RunStore.isPrincipalHandle(author)
        && attribution.principalOfOwnedRun(actor.handle(), author);
  }
}
