/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Ownership;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Who may write a spec. Any writer creates one; a spec born in another room ({@code room_id} not
 * its own id) needs the right to post there ({@link PostingRule}), and a named assignee must pass
 * the claim rule into that room. A spec's id is reserved for its own room, so a create whose id
 * names a room someone else owns is refused: it would take that room over; so is one born in
 * another room, whoever asks: the room on its id would stop answering to it. Changing the assignee
 * of a live spec is decided by the claim rule alone: an admin reassigns, and anyone else may only
 * claim an unassigned spec for the FDE they act as — and, for a spec born in a room, only where
 * they may already post, since its owner gains a voice there. Any other revision, a tombstone or a
 * restore is its owner's ({@link Ownership#ownerOf}, read from {@code held}) or an admin's, and a
 * restore that changes the assignee must pass the claim rule too. {@code room_id} never changes
 * after create.
 */
public final class SpecAuthority implements WriteAuthority {

  private static final String ASSIGNEE = "assignee";
  private static final String ROOM_ID = "room_id";

  private final RoomStore rooms;
  private final SpecStore specs;
  private final Attribution attribution;

  /** The rule, deciding on {@code db}'s copy. */
  public SpecAuthority(Sqlite db) {
    this.rooms = new RoomStore(db);
    this.specs = new SpecStore(db);
    this.attribution = new Attribution(db);
  }

  @Override
  public Optional<Refusal> decide(
      Actor actor, String id, Map<String, Object> held, Map<String, Object> next) {
    var writer = writer(actor);
    if (writer.isPresent() || WriteAuthority.decided(actor)) {
      return writer;
    }
    var attributed = attribution.decide(actor, held, next, Snapshots.CREATOR, null);
    if (attributed.isPresent()) {
      return attributed;
    }
    if (held == null) {
      return next == null ? Optional.empty() : birth(actor, id, next);
    }
    if (next != null && !roomOf(id, held).equals(roomOf(id, next))) {
      return Refusal.fixed("spec", id, ROOM_ID);
    }
    var assignee = assigneeOf(held);
    var creator = Snapshots.text(held, Snapshots.CREATOR);
    if (next == null || Objects.equals(assignee, assigneeOf(next))) {
      return owner(actor, id, assignee, creator);
    }
    if (restoring(id)) {
      var refused = owner(actor, id, assignee, creator);
      if (refused.isPresent()) {
        return refused;
      }
    }
    return claim(actor, id, held, assigneeOf(next), bornIn(id, held));
  }

  /**
   * Why {@code actor} may not write specs at all: a read-only role. Asked first, before what is
   * being written is looked at, so a read-only credential learns nothing else about it.
   */
  private static Optional<Refusal> writer(Actor actor) {
    return WriteAuthority.decided(actor) || actor.canWrite()
        ? Optional.empty()
        : Refusal.readOnly("change specs");
  }

  /**
   * Whether spec {@code id} is being restored from its deletion, read from the journal's head: the
   * row is already back while its revision is being recorded.
   */
  private boolean restoring(String id) {
    return specs.head(id).filter(head -> head.kind() == ChangeLog.Kind.TOMBSTONE).isPresent();
  }

  private Optional<Refusal> birth(Actor actor, String id, Map<String, Object> next) {
    var taken = takenRoom(actor, id, next);
    if (taken.isPresent()) {
      return taken;
    }
    var bornIn = bornIn(id, next);
    if (bornIn == null) {
      return Optional.empty();
    }
    var posting = PostingRule.decide(actor, bornIn, rooms.owners(bornIn, id, null));
    if (posting.isPresent()) {
      return posting;
    }
    var assignee = assigneeOf(next);
    return assignee == null ? Optional.empty() : claim(actor, id, null, assignee, bornIn);
  }

  /**
   * Why spec {@code id} may not be born over the room already holding its id, live or deleted: the
   * room, and the conversation in it, would become the spec's. It may when it adopts that room and
   * that moves no ownership — the room is already its owner's, as when a node's own identity room
   * reaches main before its spec does — or an admin asks. A spec born in another room never may,
   * whoever asks: a conversation is addressed spec-first, so the room on its id would answer as the
   * room the spec lives in. The room's owner is read from the room alone: until this birth it was
   * no spec's, and the spec being born never names it.
   */
  private Optional<Refusal> takenRoom(Actor actor, String id, Map<String, Object> next) {
    var room = rooms.held(id);
    if (room == null || (bornIn(id, next) == null && movesNoOwnership(actor, room, next))) {
      return Optional.empty();
    }
    return Refusal.of(Refusal.Kind.NOT_OWNER, reservedRoom(id), "Pick another spec id.");
  }

  private static boolean movesNoOwnership(
      Actor actor, Map<String, Object> room, Map<String, Object> next) {
    return actor.isAdmin()
        || RoomStore.ownerOf(room)
            .equals(Ownership.ownerOf(assigneeOf(next), creatorOf(actor, next)));
  }

  /** The refusal text of a spec born over room {@code id}, which holds the spec's reserved id. */
  private static String reservedRoom(String id) {
    return "Room '" + id + "' already exists, and a spec's id is reserved for its own room.";
  }

  /**
   * The claim rule: an admin assigns anyone; anyone else may only claim a spec that is unassigned,
   * for the FDE they act as ({@link Actor#actingFde}), and one born in {@code bornIn} only where
   * they may already post — the spec itself counted as {@code held}, never as the claim would leave
   * it.
   */
  private Optional<Refusal> claim(
      Actor actor, String id, Map<String, Object> held, String requested, String bornIn) {
    if (actor.isAdmin()) {
      return Optional.empty();
    }
    var current = held == null ? null : assigneeOf(held);
    var claimant = actor.actingFde();
    if (current != null || Strings.isBlank(claimant) || !claimant.equals(requested)) {
      return Refusal.of(
          Refusal.Kind.ADMIN_ONLY,
          "Reassigning spec '"
              + id
              + "' moves work between FDEs and is an admin-only action"
              + (current != null ? " (currently '" + current + "')" : "")
              + ".",
          "Ask an admin to reassign it. You may grab a spec only while it is unassigned.");
    }
    if (bornIn != null
        && PostingRule.decide(actor, bornIn, rooms.owners(bornIn, id, held)).isPresent()) {
      return Refusal.of(
          Refusal.Kind.ADMIN_ONLY,
          "Spec '"
              + id
              + "' lives in '"
              + bornIn
              + "', where you may not post, and claiming it would give you a voice there.",
          "Ask an admin to assign it to you.");
    }
    return Optional.empty();
  }

  private static Optional<Refusal> owner(
      Actor actor, String id, String assignee, String createdBy) {
    if (actor.isAdmin() || actor.actsFor(Ownership.ownerOf(assignee, createdBy))) {
      return Optional.empty();
    }
    return notOwner(id, assignee, createdBy);
  }

  /** The refusal of a spec's change by someone other than its owner or an admin. */
  static Optional<Refusal> notOwner(String id, String assignee, String createdBy) {
    if (Strings.isNotBlank(assignee)) {
      return Refusal.of(
          Refusal.Kind.NOT_OWNER,
          "Spec '" + id + "' is assigned to '" + assignee + "', not you.",
          "Ask " + assignee + " to make this change, or have an admin do it.");
    }
    var creator = Strings.isNotBlank(createdBy) ? createdBy : "its creator";
    return Refusal.of(
        Refusal.Kind.NOT_OWNER,
        "Spec '" + id + "' is unassigned; only " + creator + " or an admin may change it.",
        "Have an admin change it, or claim it first with --assignee <you>.");
  }

  private static String creatorOf(Actor actor, Map<String, Object> next) {
    var creator = Snapshots.text(next, Snapshots.CREATOR);
    return Strings.isBlank(creator) ? actor.actingFde() : creator;
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
