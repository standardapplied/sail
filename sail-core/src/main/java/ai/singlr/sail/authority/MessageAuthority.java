/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.PrincipalHandle;
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
 * there is only ever a create to decide. Then the facts of posting ({@link #posting}), and as whom:
 * on this box's lanes the author is the actor's own handle — a run's principal on the agent and
 * room lanes. On {@link Actor.Lane#SYNC} the pusher posts as itself, as a principal of any run it
 * owns, or as the pipeline ({@link MessageStore#SAIL_AUTHOR}) where a run it owns is in the
 * conversation and it steers the agents there itself ({@link RoomStore#steering}), since the
 * pipeline's words steer them all: a box whose work was reassigned narrates there no more.
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
    if (next == null) {
      return Optional.empty();
    }
    var roomId = MessageStore.roomIdOf(next);
    var posting = posting(actor, roomId, changeLog.projectOf("message", id, next));
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

  /**
   * Why {@code actor} may not post in conversation {@code roomId}, filed under {@code project}:
   * {@link Access} refuses the post, or one of two facts holds that bind whoever does not overrule
   * owners ({@link Access#overrulesOwners}): a conversation this box does not hold has no owner
   * here to ask, so it takes no post; and a post is someone's, so a credential acting for no FDE
   * posts nothing. The journal asks it of every post, and the doors that admit a voice into a room
   * ask it before anything is said there: the terminal's room pin and a room session's report.
   */
  public Optional<Refusal> posting(Actor actor, String roomId, String project) {
    var conversation = Resources.conversation(roomId, project, rooms.owners(roomId));
    var refused = WriteAuthority.refused(actor, Taking.of(conversation, Action.POST));
    if (refused.isPresent() || Access.overrulesOwners(actor)) {
      return refused;
    }
    if (!rooms.holds(roomId, null)) {
      return Refusal.noConversation(roomId);
    }
    return Strings.isBlank(actor.actingFde())
        ? Refusal.of(
            Refusal.Kind.NOT_AUTHOR,
            "This credential acts for no FDE, so a post in '" + roomId + "' would be no one's.",
            "Post with an FDE's own credential.")
        : Optional.empty();
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
    return PrincipalHandle.matches(author)
        && attribution.principalOfOwnedRun(actor.handle(), author);
  }
}
