/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Resource;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Who may write a spec: the actions a revision takes, each decided by {@link Access}, then the
 * facts only this rule knows. A birth is a {@link Action#CREATE}, one born in another room ({@code
 * room_id} not its own id) of that room's conversation ({@link RoomStore#owners}), and a named
 * assignee is a claim into that room too; a create in its own room may name anyone. Any other
 * revision, a tombstone or a restore is a {@link Action#CHANGE} of the spec as {@code held} has it.
 * Moving the assignee of a live spec is a {@link Action#CLAIM} when it takes an unassigned spec for
 * the FDE the actor acts as ({@link Actor#actingFde}), and otherwise an {@link Action#ASSIGN}; a
 * restore that moves it is a change as well.
 *
 * <p>What this box does not hold is not there, said before anything is decided: a spec lands only
 * in a project this box holds ({@link ProjectStore#refusesInto}), and is born only into a
 * conversation it holds ({@link #intoAbsent}). The facts: a push names only its pusher ({@link
 * Attribution}); {@code room_id} never changes after create; and a spec's id is reserved for its
 * own room, so a create whose id names a room is refused when it is born in another room, whoever
 * asks — the room on its id would stop answering to it — and when it would hand that room to
 * another owner, unless the actor overrules owners.
 */
public final class SpecAuthority implements WriteAuthority {

  private static final String ASSIGNEE = "assignee";
  private static final String ROOM_ID = "room_id";

  private final RoomStore rooms;
  private final SpecStore specs;
  private final ProjectStore projects;
  private final Attribution attribution;

  /** The rule, deciding on {@code db}'s copy. */
  public SpecAuthority(Sqlite db) {
    this.rooms = new RoomStore(db);
    this.specs = new SpecStore(db);
    this.projects = new ProjectStore(db);
    this.attribution = new Attribution(db);
  }

  @Override
  public Optional<Refusal> decide(
      Actor actor, String id, Map<String, Object> held, Map<String, Object> next) {
    var absent =
        projects
            .refusesInto(actor, specs.liveProject(id).orElse(null), projectOf(next), false)
            .or(() -> held == null ? intoAbsent(actor, id, next) : Optional.empty());
    if (absent.isPresent()) {
      return absent;
    }
    var refused =
        WriteAuthority.namesNothing(held, next)
            ? WriteAuthority.unnamed(actor, Resource.Kind.SPEC)
            : WriteAuthority.refused(
                actor,
                new Taking(
                    held == null ? asBorn(id, next) : asHeld(id, held),
                    actions(actor, id, held, next)));
    return refused.isPresent() ? refused : facts(actor, id, held, next);
  }

  /**
   * Why spec {@code id} may not be born as {@code next} into the room it names: this box holds no
   * conversation there ({@link RoomStore#holds}), so it has no owner here to ask, unless the actor
   * overrules owners ({@link Access#overrulesOwners}).
   */
  private Optional<Refusal> intoAbsent(Actor actor, String id, Map<String, Object> next) {
    var bornIn = bornIn(id, next);
    return bornIn == null || Access.overrulesOwners(actor) || rooms.holds(bornIn, id)
        ? Optional.empty()
        : Refusal.noConversation(bornIn);
  }

  /** The actions writing {@code next} over {@code held} takes, each of which must be allowed. */
  private List<Action> actions(
      Actor actor, String id, Map<String, Object> held, Map<String, Object> next) {
    if (held == null) {
      var assignee = assigneeOf(next);
      return assignee == null || bornIn(id, next) == null
          ? List.of(Action.CREATE)
          : List.of(Action.CREATE, movement(actor, null, assignee));
    }
    var assignee = assigneeOf(held);
    if (next == null || Objects.equals(assignee, assigneeOf(next))) {
      return List.of(Action.CHANGE);
    }
    var moved = movement(actor, assignee, assigneeOf(next));
    return restoring(id) ? List.of(Action.CHANGE, moved) : List.of(moved);
  }

  /**
   * How moving a spec's assignee from {@code current} to {@code requested} is decided: a claim when
   * it takes an unassigned spec for the FDE the actor acts as, an assignment otherwise.
   */
  private static Action movement(Actor actor, String current, String requested) {
    var claimant = actor.actingFde();
    return current == null && Strings.isNotBlank(claimant) && claimant.equals(requested)
        ? Action.CLAIM
        : Action.ASSIGN;
  }

  private Optional<Refusal> facts(
      Actor actor, String id, Map<String, Object> held, Map<String, Object> next) {
    var attributed = attribution.decide(actor, held, next, Snapshots.CREATOR, null);
    if (attributed.isPresent() || next == null) {
      return attributed;
    }
    if (held == null) {
      return takenRoom(actor, id, next);
    }
    return roomOf(id, held).equals(roomOf(id, next))
        ? Optional.empty()
        : Refusal.fixed("spec", id, ROOM_ID);
  }

  /**
   * Whether spec {@code id} is being restored from its deletion, read from the journal's head: the
   * row is already back while its revision is being recorded.
   */
  private boolean restoring(String id) {
    return specs.head(id).filter(head -> head.kind() == ChangeLog.Kind.TOMBSTONE).isPresent();
  }

  /**
   * Spec {@code id} as {@code next} would bear it, before it has an assignee to move: in its
   * project, and born into the conversation it names where this box holds one, as that
   * conversation's owners stand without it.
   */
  private Resource asBorn(String id, Map<String, Object> next) {
    var spec = Resources.spec(id, projectOf(next), null, null);
    var bornIn = bornIn(id, next);
    return bornIn == null || !rooms.holds(bornIn, id)
        ? spec
        : spec.withParent(conversation(bornIn, spec, null));
  }

  /**
   * Spec {@code id} as {@code held} has it: its owner, and the conversation it lives in, whose
   * owners count it as held, never as the revision would leave it.
   */
  private Resource asHeld(String id, Map<String, Object> held) {
    var spec =
        Resources.spec(
            id, projectOf(held), assigneeOf(held), Snapshots.text(held, Snapshots.CREATOR));
    var bornIn = bornIn(id, held);
    return bornIn == null ? spec : spec.withParent(conversation(bornIn, spec, held));
  }

  private Resource conversation(String roomId, Resource spec, Map<String, Object> held) {
    return Resources.conversation(roomId, spec.project(), rooms.owners(roomId, spec.id(), held));
  }

  /**
   * Why spec {@code id} may not be born over the room already holding its id, live or deleted: the
   * room, and the conversation in it, would become the spec's. It may when it adopts that room and
   * that moves no ownership — the room is already its owner's, as when a node's own identity room
   * reaches main before its spec does — or when the actor overrules owners ({@link
   * Access#overrulesOwners}). A spec born in another room never may, whoever asks: a conversation
   * is addressed spec-first, so the room on its id would answer as the room the spec lives in. The
   * room's owner is read from the room alone: until this birth it was no spec's, and the spec being
   * born never names it.
   */
  private Optional<Refusal> takenRoom(Actor actor, String id, Map<String, Object> next) {
    var taken = rooms.held(id);
    if (taken == null) {
      return Optional.empty();
    }
    var room = Resources.room(id, projectOf(taken), RoomStore.ownerOf(taken));
    var spec = Resources.spec(id, projectOf(next), assigneeOf(next), creatorOf(actor, next));
    if (bornIn(id, next) == null
        && (spec.owners().equals(room.owners()) || Access.overrulesOwners(actor))) {
      return Optional.empty();
    }
    return Refusal.of(
        Refusal.Kind.NOT_OWNER,
        "Room '" + id + "' already exists, and a spec's id is reserved for its own room.",
        "Pick another spec id.");
  }

  private static String creatorOf(Actor actor, Map<String, Object> next) {
    var creator = Snapshots.text(next, Snapshots.CREATOR);
    return Strings.isBlank(creator) ? actor.actingFde() : creator;
  }

  private static String projectOf(Map<String, Object> projection) {
    return Snapshots.text(projection, "project");
  }

  private static String assigneeOf(Map<String, Object> projection) {
    var assignee = Snapshots.text(projection, ASSIGNEE);
    return Strings.isBlank(assignee) ? null : assignee;
  }

  private static String roomOf(String id, Map<String, Object> projection) {
    var room = Snapshots.text(projection, ROOM_ID);
    return Strings.isBlank(room) ? id : room;
  }

  private static String bornIn(String id, Map<String, Object> projection) {
    var room = roomOf(id, projection);
    return room.equals(id) ? null : room;
  }
}
