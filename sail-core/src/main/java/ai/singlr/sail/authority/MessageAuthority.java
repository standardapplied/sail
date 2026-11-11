/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Decision;
import ai.singlr.sail.access.ReadFilter;
import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.access.Resource;
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
 * there is only ever a create to decide. Then the facts of posting, and as whom: on this box's
 * lanes the author is the actor's own handle — a run's principal on the agent and room lanes. On
 * {@link Actor.Lane#SYNC} the pusher posts as itself, as a principal of any run it owns, or as the
 * pipeline ({@link MessageStore#SAIL_AUTHOR}) where a run it owns is in the conversation and it
 * steers the agents there itself ({@link RoomStore#steering}), since the pipeline's words steer
 * them all: a box whose work was reassigned narrates there no more.
 */
public final class MessageAuthority implements WriteAuthority {

  private final RoomStore rooms;
  private final RunStore runs;
  private final ChangeLog journal;
  private final Attribution attribution;

  /** The rule, deciding on {@code db}'s copy. */
  public MessageAuthority(Sqlite db) {
    this.rooms = new RoomStore(db);
    this.runs = new RunStore(db);
    this.journal = new ChangeLog(db);
    this.attribution = new Attribution(db);
  }

  @Override
  public Optional<Refusal> decide(
      Actor actor, String id, Map<String, Object> held, Map<String, Object> next) {
    if (next == null) {
      return Optional.empty();
    }
    var roomId = MessageStore.roomIdOf(next);
    var posting = posting(actor, roomId);
    if (posting.isPresent()) {
      return posting;
    }
    var author = Snapshots.text(next, "author");
    if (Strings.isBlank(author)) {
      return Optional.of(noOne(roomId));
    }
    if (!mayPostAs(actor, author, roomId)) {
      return Refusal.of(
          Refusal.Kind.NOT_AUTHOR,
          "'" + actor.handle() + "' may not post as '" + author + "' in this room.",
          "Post as yourself.");
    }
    return Optional.empty();
  }

  /**
   * Why {@code actor} may not post in conversation {@code roomId}, whose audience was decided. A
   * conversation this box does not hold is not here, which is said first, as for every row this box
   * does not hold; then {@link Access} decides the post; and last the fact that a post is
   * someone's, so a credential acting for no FDE posts nothing. Both facts are of what no one here
   * can own, which is an admin's to write ({@link Access#unowned}), as on main. The conversation is
   * filed under its project, and made by whoever made the spec whose own room it is.
   */
  private Optional<Refusal> posting(Actor actor, String roomId) {
    var unheld =
        rooms.holds(roomId, null)
            ? Optional.<Refusal>empty()
            : Access.unowned(actor, Refusal.noConversation(roomId));
    if (unheld.isPresent()) {
      return unheld;
    }
    return posted(
        actor,
        Resources.madeBy(
            Resources.conversation(
                roomId, journal.conversationProject(roomId), rooms.owners(roomId)),
            journal.bornBy("spec", roomId).orElse(null)));
  }

  /**
   * Whether {@code actor} may post in {@code conversation}, a room this box holds, as a door asks
   * it before the post it admits — the one decision of a write rule a door asks, since a post with
   * no message to journal (a terminal's room pin, a room session's report) still answers to the
   * rule's own fact that a post is someone's. It is this rule's decision of a post, the audience
   * asked here as the scope rule asks it of a message.
   */
  public static Decision post(Actor actor, Resource conversation) {
    return ReadFilter.reads(actor, conversation::project)
        ? Decision.of(posted(actor, conversation))
        : new Decision.NotFound();
  }

  /**
   * Why {@code actor} may not post in {@code conversation}, whose audience was decided: {@link
   * Access} decides the post, then a post is someone's ({@link #noOnes}).
   */
  private static Optional<Refusal> posted(Actor actor, Resource conversation) {
    return WriteAuthority.refused(actor, Taking.of(conversation, Action.POST))
        .or(() -> noOnes(actor, conversation.id()));
  }

  /**
   * Why a post by {@code actor} in {@code roomId} would be no one's: its credential acts for no
   * FDE. A door asking before a post with no message (a terminal's room pin) leaves what no one
   * here can own to an admin ({@link Access#unowned}); a message itself is refused it whoever
   * writes it, since a message with no author is no one's ({@link #decide}).
   */
  private static Optional<Refusal> noOnes(Actor actor, String roomId) {
    return Strings.isNotBlank(actor.actingFde())
        ? Optional.empty()
        : Access.unowned(actor, noOne(roomId));
  }

  private static Refusal noOne(String roomId) {
    return new Refusal(
        Refusal.Kind.NOT_AUTHOR,
        "This credential acts for no FDE, so a post in '" + roomId + "' would be no one's.",
        "Post with an FDE's own credential.");
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
