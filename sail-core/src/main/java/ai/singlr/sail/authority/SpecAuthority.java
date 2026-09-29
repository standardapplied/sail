/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Ownership;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.Sqlite;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Who may write a spec. Any writer creates one; a spec born in another room ({@code room_id} not
 * its own id) needs the right to post there ({@link PostingRule}), and a named assignee must pass
 * the claim rule into that room. Changing the assignee is decided by the claim rule alone: an admin
 * reassigns, and anyone else may only claim an unassigned spec for the FDE they act as — and, for a
 * spec born in a room, only where they may already post, since its owner gains a voice there. Any
 * other revision, a tombstone or a restore is its owner's ({@link Ownership#ownerOf}, read from
 * {@code held}) or an admin's. {@code room_id} never changes after create.
 */
public final class SpecAuthority implements WriteAuthority {

  private static final String ASSIGNEE = "assignee";
  private static final String ROOM_ID = "room_id";

  private final RoomStore rooms;
  private final Attribution attribution;

  public SpecAuthority(Sqlite db) {
    this.rooms = new RoomStore(db);
    this.attribution = new Attribution(db);
  }

  @Override
  public Optional<Refusal> decide(
      Actor actor, String id, Map<String, Object> held, Map<String, Object> next) {
    if (WriteAuthority.decided(actor)) {
      return Optional.empty();
    }
    if (!actor.canWrite()) {
      return Refusal.readOnly("change specs");
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
    if (next != null && !Objects.equals(assignee, assigneeOf(next))) {
      return claim(actor, id, assignee, assigneeOf(next), bornIn(id, held));
    }
    return owner(actor, id, assignee, Snapshots.text(held, Snapshots.CREATOR));
  }

  private Optional<Refusal> birth(Actor actor, String id, Map<String, Object> next) {
    var bornIn = bornIn(id, next);
    if (bornIn == null) {
      return Optional.empty();
    }
    var posting = PostingRule.decide(actor, bornIn, rooms.owners(bornIn));
    if (posting.isPresent()) {
      return posting;
    }
    var assignee = assigneeOf(next);
    return assignee == null ? Optional.empty() : claim(actor, id, null, assignee, bornIn);
  }

  /**
   * The claim rule: an admin assigns anyone; anyone else may only claim a spec that is unassigned,
   * for the FDE they act as ({@link Actor#actingFde}), and one born in {@code bornIn} only where
   * they may already post.
   */
  private Optional<Refusal> claim(
      Actor actor, String id, String current, String requested, String bornIn) {
    if (actor.isAdmin()) {
      return Optional.empty();
    }
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
    if (bornIn != null && PostingRule.decide(actor, bornIn, rooms.owners(bornIn)).isPresent()) {
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
