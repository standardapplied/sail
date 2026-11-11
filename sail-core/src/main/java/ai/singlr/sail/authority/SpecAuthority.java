/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.access.Resource;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
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
 * restore that moves it is a change as well. A move of its status, and a birth in any status but
 * draft, then take {@link Action#APPROVE} ({@link #approval}).
 *
 * <p>What this box does not hold is not there, said before anything is decided: a spec lands only
 * in a project this box holds ({@link ProjectStore#refusesInto}), and is born only into a
 * conversation it holds ({@link #intoAbsent}). The facts: a push names only its pusher ({@link
 * Attribution}); {@code room_id} never changes after create; and a spec's id is reserved for its
 * own room, so a create whose id names a room is refused when it is born in another room, whoever
 * asks — the room on its id would stop answering to it — and when it would hand that room to
 * another owner, unless the actor may assign the room ({@link Action#ASSIGN}).
 */
public final class SpecAuthority implements WriteAuthority {

  private static final String ASSIGNEE = "assignee";
  private static final String ROOM_ID = "room_id";
  private static final String STATUS = "status";

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
        WriteAuthority.refused(
                actor,
                taking(actor, id, held == null ? asBorn(id, next) : asHeld(id, held), held, next))
            .or(() -> moving(actor, id, held, next))
            .or(() -> approval(actor, id, held, next));
    return refused.isPresent() ? refused : facts(actor, id, held, next);
  }

  /**
   * Why {@code actor} may not move spec {@code id} from the project {@code held} files it under to
   * another, {@code next}'s: the spec is changed where it lands as well as where it was, so whoever
   * may change it in one project only, an agent in its work's, moves it nowhere.
   */
  private Optional<Refusal> moving(
      Actor actor, String id, Map<String, Object> held, Map<String, Object> next) {
    return held == null || next == null || Objects.equals(projectOf(held), projectOf(next))
        ? Optional.empty()
        : WriteAuthority.refused(
            actor,
            Taking.of(
                attribution.made(
                    Resources.spec(
                        id,
                        projectOf(next),
                        assigneeOf(held),
                        Snapshots.text(held, Snapshots.CREATOR))),
                Action.CHANGE));
  }

  /**
   * Why {@code actor} may not move a spec's status from {@code held} to {@code next}, or bear it in
   * any status but draft: that is {@link Action#APPROVE}, asked after what the revision takes
   * otherwise, so a refusal of that keeps its words, and asked of the role and lane alone ({@link
   * Access#atAll}). Whose the spec is, the revision's other actions asked already: a change of its
   * owner, a claim of the claimer, a birth of no one but its creator. So a person approves what
   * they may write, and an agent's lane refuses it.
   */
  private static Optional<Refusal> approval(
      Actor actor, String id, Map<String, Object> held, Map<String, Object> next) {
    return next != null && movesStatus(held, next)
        ? Access.atAll(actor, Action.APPROVE, Resources.spec(id, projectOf(next), null, null))
        : Optional.empty();
  }

  private static boolean movesStatus(Map<String, Object> held, Map<String, Object> next) {
    var status = Snapshots.text(next, STATUS);
    return held == null
        ? Strings.isNotBlank(status) && !SpecStatus.DRAFT.wire().equals(status)
        : !Objects.equals(Snapshots.text(held, STATUS), status);
  }

  /**
   * Why spec {@code id} may not be born as {@code next} into the room it names: this box holds no
   * conversation there ({@link RoomStore#holds}), so no owner of it is here to ask, and what no one
   * here can own is an admin's to write ({@link Access#unowned}).
   */
  private Optional<Refusal> intoAbsent(Actor actor, String id, Map<String, Object> next) {
    var bornIn = bornIn(id, next);
    return bornIn == null || rooms.holds(bornIn, id)
        ? Optional.empty()
        : Access.unowned(actor, Refusal.noConversation(bornIn));
  }

  /**
   * What writing {@code next} over {@code held} takes, on {@code spec}: the actions, each of which
   * must be allowed.
   */
  private Taking taking(
      Actor actor, String id, Resource spec, Map<String, Object> held, Map<String, Object> next) {
    if (held == null) {
      var assignee = assigneeOf(next);
      return assignee == null || bornIn(id, next) == null
          ? Taking.of(spec, Action.CREATE)
          : Taking.of(spec, Action.CREATE, movement(actor, null, assignee));
    }
    var assignee = assigneeOf(held);
    if (next == null || Objects.equals(assignee, assigneeOf(next))) {
      return Taking.of(spec, Action.CHANGE);
    }
    var moved = movement(actor, assignee, assigneeOf(next));
    return restoring(id) ? Taking.of(spec, Action.CHANGE, moved) : Taking.of(spec, moved);
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
   * Spec {@code id} as {@code next} would bear it, before it has an assignee to move: filed under
   * the project it names, born into the conversation it names, as that conversation's owners stand
   * without it. One this box does not hold was answered already ({@link #intoAbsent}).
   */
  private Resource asBorn(String id, Map<String, Object> next) {
    var spec = Resources.spec(id, projectOf(next), null, null);
    var bornIn = bornIn(id, next);
    return bornIn == null ? spec : Resources.bornIn(spec, conversation(bornIn, spec, null));
  }

  /**
   * Spec {@code id} as {@code held} has it: its project and owner, who made it ({@link
   * Attribution#made}), and the conversation it lives in, whose owners count it as held, never as
   * the revision would leave it.
   */
  private Resource asHeld(String id, Map<String, Object> held) {
    var spec =
        attribution.made(
            Resources.spec(
                id, projectOf(held), assigneeOf(held), Snapshots.text(held, Snapshots.CREATOR)));
    var bornIn = bornIn(id, held);
    return bornIn == null ? spec : Resources.bornIn(spec, conversation(bornIn, spec, held));
  }

  private Resource conversation(String roomId, Resource spec, Map<String, Object> held) {
    return Resources.conversation(roomId, null, rooms.owners(roomId, spec.id(), held));
  }

  /**
   * Why spec {@code id} may not be born over the room already holding its id, live or deleted: the
   * room, and the conversation in it, would become the spec's. It may when it adopts that room and
   * that moves no ownership — the room is already its owner's, as when a node's own identity room
   * reaches main before its spec does — or when the actor may assign the room to another owner
   * ({@link Action#ASSIGN}). A spec born in another room never may, whoever asks: a conversation is
   * addressed spec-first, so the room on its id would answer as the room the spec lives in. The
   * room's owner is read from the room alone: until this birth it was no spec's, and the spec being
   * born never names it.
   */
  private Optional<Refusal> takenRoom(Actor actor, String id, Map<String, Object> next) {
    var taken = rooms.held(id);
    if (taken == null) {
      return Optional.empty();
    }
    var room = Resources.room(id, null, RoomStore.ownerOf(taken));
    var spec = Resources.spec(id, null, assigneeOf(next), creatorOf(actor, next));
    if (bornIn(id, next) == null
        && (spec.owners().equals(room.owners())
            || WriteAuthority.refused(actor, Taking.of(room, Action.ASSIGN)).isEmpty())) {
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
