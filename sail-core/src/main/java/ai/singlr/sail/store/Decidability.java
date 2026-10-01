/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.Strings;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The one rule for whether an offer can be decided now: what each type of offer depends on, and how
 * those dependencies stand against the box asked ({@link Holdings}). Read in three places, never
 * re-derived: main's commit refuses an offer whose dependency is pending and denies one whose
 * dependency is gone; a node's replica withholds an offer whose dependency is pending; and a node
 * settles an offer whose dependency is gone.
 *
 * <p>The dependencies: a spec on the room it is born in; a review on its spec; a message on its
 * conversation, on the message it replies to, and on the run of an agent author — or, for a
 * platform post, on a run of its owner in the conversation; any revision on the run its {@code
 * _actor} principal names, bar a run's own session reports; and every type on the project it
 * belongs to, which {@link ChangeLog#append} refuses to write under once erased.
 */
public final class Decidability {

  private final Holdings holdings;

  /** The rule read against {@code holdings}, one box's view of what main holds. */
  public Decidability(Holdings holdings) {
    this.holdings = Objects.requireNonNull(holdings, "holdings");
  }

  /** Main's reading of its own database. */
  public static Decidability onMain(Sqlite db) {
    return new Decidability(Holdings.main(db));
  }

  /** A node's reading of its copy. */
  public static Decidability onNode(Sqlite db) {
    return new Decidability(Holdings.node(db));
  }

  /**
   * How the offer of {@code snapshot} as {@code id} of {@code type} stands: held to decide it,
   * pending to await a dependency, gone to give it up — the reason naming which. {@code handle} is
   * the FDE whose runs a platform post rests on: the pusher on main, the box's own FDE on a node.
   */
  public Standing standing(String type, String id, Map<String, Object> snapshot, String handle) {
    return standing(type, id, snapshot, handle, new LinkedHashSet<>());
  }

  /**
   * The ids among {@code candidates} of {@code type} a node withholds because a dependency is
   * pending, each read from the row it holds; {@code boxHandle} is this box's FDE.
   */
  public Set<String> withheld(
      String type, Set<String> candidates, SyncedStore store, String boxHandle) {
    var withheld = new LinkedHashSet<String>();
    for (var id : candidates) {
      if (standing(type, id, store.currentForSync(id), boxHandle) instanceof Standing.Pending) {
        withheld.add(id);
      }
    }
    return withheld;
  }

  /**
   * Whether a spec born in a room lost only that room — its project and its author's run still
   * stand — so it can be re-homed into its own room rather than withdrawn.
   */
  public boolean onlyRoomGone(String type, String id, Map<String, Object> snapshot) {
    if (!Erasure.SPEC.equals(type) || snapshot == null) {
      return false;
    }
    var others = project(type, snapshot).worse(actorRun(type, id, snapshot));
    return !(others instanceof Standing.Gone) && bornIn(id, snapshot) instanceof Standing.Gone;
  }

  private Standing standing(
      String type, String id, Map<String, Object> snapshot, String handle, Set<String> visited) {
    if (snapshot == null) {
      return Standing.HELD;
    }
    var standing = project(type, snapshot).worse(actorRun(type, id, snapshot));
    return switch (type) {
      case Erasure.SPEC -> standing.worse(bornIn(id, snapshot));
      case Erasure.REVIEW -> standing.worse(specOf(id, snapshot));
      case Erasure.MESSAGE -> standing.worse(message(snapshot, handle, visited));
      default -> standing;
    };
  }

  private Standing project(String type, Map<String, Object> snapshot) {
    var standing = Standing.HELD;
    for (var owner : Erasure.ownersOf(type)) {
      var project = Snapshots.text(snapshot, owner.column());
      if (Erasure.PROJECT.equals(owner.type()) && Strings.isNotBlank(project)) {
        standing = standing.worse(holdings.project(project));
      }
    }
    return standing;
  }

  private Standing actorRun(String type, String id, Map<String, Object> snapshot) {
    var actor = Snapshots.text(snapshot, Snapshots.ACTOR);
    var run = RunStore.runOf(actor);
    if (run.isEmpty() || (Erasure.RUN.equals(type) && run.get().equals(id))) {
      return Standing.HELD;
    }
    return holdings.run(run.get(), actor);
  }

  private Standing bornIn(String id, Map<String, Object> snapshot) {
    var room = Snapshots.text(snapshot, "room_id");
    if (Strings.isBlank(room) || room.equals(id)) {
      return Standing.HELD;
    }
    return holdings.bornInRoom(room, id, holdings.holdsDependent(Erasure.SPEC, id));
  }

  private Standing specOf(String id, Map<String, Object> snapshot) {
    var spec = Snapshots.text(snapshot, "spec_id");
    if (Strings.isBlank(spec)) {
      return Standing.HELD;
    }
    return holdings.specOf(spec, id, holdings.holdsDependent(Erasure.REVIEW, id));
  }

  /**
   * A message stands on its conversation, on the message it replies to — inheriting, on a node, the
   * standing of a parent main has not taken yet, so a reply is withheld or settled exactly with the
   * post it answers — and on the run its author rests on.
   */
  private Standing message(Map<String, Object> snapshot, String handle, Set<String> visited) {
    var room = MessageStore.roomIdOf(snapshot);
    var standing = Strings.isBlank(room) ? Standing.HELD : holdings.conversation(room);
    var reply = Snapshots.text(snapshot, "reply_to");
    if (Strings.isNotBlank(reply) && visited.add(reply)) {
      var unsynced = holdings.unsyncedParent(reply);
      standing =
          standing.worse(
              unsynced.isPresent()
                  ? message(unsynced.get(), handle, visited)
                  : holdings.parent(reply));
    }
    var author = Snapshots.text(snapshot, "author");
    if (MessageStore.SAIL_AUTHOR.equals(author)) {
      return Strings.isBlank(handle) ? standing : standing.worse(holdings.ownerRun(handle, room));
    }
    var run = RunStore.runOf(author);
    return run.isEmpty() ? standing : standing.worse(holdings.authorRun(run.get(), author));
  }
}
