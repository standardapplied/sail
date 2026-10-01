/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.sync.SyncedEntities;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The one rule for whether an offer can be decided now: what each type of offer depends on, whether
 * main holds those dependencies, and what becomes of an offer whose dependency can never arrive.
 *
 * <p>Read in three places, never re-derived: main's commit refuses an offer whose dependency is
 * {@link Status#PENDING} and denies one whose dependency is {@link Status#GONE} ({@link #forMain});
 * a node's {@code dirtyIds} withholds an offer whose dependency is pending ({@link #pending}); and
 * a node settles what can never be decided ({@link #settle}) — a born-in spec whose room is gone is
 * re-homed into its own identity room, anything else is withdrawn as a denial is settled.
 *
 * <p>A dependency is held, pending or gone against one box's database. On the node, {@code
 * base_rev} is the reading: a row main has taken carries one, so its dependents may be offered; a
 * row main has not taken yet carries none and is still to arrive by sync order; a deleted, denied
 * or erased row is gone. On main — where every held row is authoritative — a dependency is held
 * while main has any live or tombstoned entry for it, pending while main has none, and gone once
 * main has erased it.
 *
 * <p>The dependencies: a spec on the room it is born in; a review on its spec; a message on its
 * conversation (a room, or the spec living in it), its {@code reply_to} parent, and the run of an
 * agent author — or, for a platform post, a run of its owner in the conversation; any revision on
 * the run its {@code _actor} principal names, bar a run's own session reports; and every type on
 * the project it belongs to, which {@link ChangeLog#append} refuses to write under once erased.
 */
public final class Decidability {

  /** How an offer's dependency stands: decidable, awaited, or never coming. */
  public enum Status {
    HELD,
    PENDING,
    GONE
  }

  /** A dependency's standing, and — when it blocks — why, for main's refusal or denial. */
  public record Finding(Status status, String reason) {
    static final Finding HELD = new Finding(Status.HELD, null);

    static Finding pending(String reason) {
      return new Finding(Status.PENDING, reason);
    }

    static Finding gone(String reason) {
      return new Finding(Status.GONE, reason);
    }

    Finding worse(Finding other) {
      return other.status.ordinal() > status.ordinal() ? other : this;
    }
  }

  private final Sqlite db;
  private final ChangeLog changeLog;

  public Decidability(Sqlite db) {
    this.db = db;
    this.changeLog = new ChangeLog(db);
  }

  /**
   * Main's verdict on committing {@code snapshot} as the pusher {@code pusher}: held to decide it
   * on authority, pending to refuse it while a dependency arrives, gone to deny it because a
   * dependency has been erased — the reason naming which.
   */
  public Finding forMain(String type, String id, Map<String, Object> snapshot, String pusher) {
    return evaluate(type, id, snapshot, pusher, true);
  }

  /**
   * The node's verdict on its own offer of {@code id}, read from the row it holds: held to offer
   * it, pending to withhold it, gone to settle it. {@code boxHandle} is this box's FDE handle, the
   * owner of its runs.
   */
  public Status forNode(String type, String id, String boxHandle) {
    return evaluate(type, id, currentSnapshot(type, id), boxHandle, false).status();
  }

  /**
   * The ids among {@code candidates} of {@code type} this node withholds because a dependency is
   * pending; the hold-back every store's {@code dirtyIds} applies, as {@code boxHandle}.
   */
  public Set<String> pending(String type, Set<String> candidates, String boxHandle) {
    var held = new LinkedHashSet<String>();
    for (var id : candidates) {
      if (forNode(type, id, boxHandle) == Status.PENDING) {
        held.add(id);
      }
    }
    return held;
  }

  /**
   * Settles on this box, as the FDE {@code boxHandle}, every unacknowledged offer whose dependency
   * is gone: a born-in spec whose room is gone is re-homed into its own identity room; anything
   * else is withdrawn as a denial is settled, kept in the change log. Work still live here ({@link
   * SyncedStore#live}) is left to its pipeline, as a denial leaves it, and settles once it has
   * finished. Runs locally, needing no session, at the start of every round, so what a round's
   * denials and erasures leave undecidable is settled by the next. Returns what it settled.
   *
   * <p>{@code mainFileMax} is main's file ceiling (0 when unknown): an offer of a file above it is
   * settled once here, never made, so an oversized file never breaks the channel mid-upload.
   *
   * <p>One write transaction covers choosing and settling: another writer (an upload from the pty
   * host or a CLI) waits its turn, so what is settled is exactly what was judged, never a
   * replacement that landed in between and would have passed.
   */
  public List<String> settle(String boxHandle, long mainFileMax) {
    return db.transaction(
        () -> {
          var settled = new ArrayList<String>();
          for (var entity : SyncedEntities.all()) {
            var store = entity.store(db);
            for (var id : List.copyOf(store.dirtyIds())) {
              if (!store.live(id, boxHandle)
                  && forNode(entity.type(), id, boxHandle) == Status.GONE) {
                settleGone(entity.type(), id);
                settled.add(entity.type() + " " + id);
              }
            }
          }
          for (var id : filesAbove(mainFileMax)) {
            settleGone(Erasure.FILE, id);
            settled.add(Erasure.FILE + " " + id);
          }
          return settled;
        });
  }

  /**
   * The files this box offers that main's ceiling would refuse: only an outstanding offer is an
   * offer, so a file main already holds above a ceiling it lowered since is left as it is, and an
   * oversized edit of one is reverted to the version main holds rather than withdrawn.
   */
  private List<String> filesAbove(long mainFileMax) {
    if (mainFileMax <= 0) {
      return List.of();
    }
    var dirty = SyncedEntities.require(Erasure.FILE).store(db).dirtyIds();
    return db
        .query("SELECT id FROM project_files WHERE size > ?", r -> r.text(0), mainFileMax)
        .stream()
        .filter(dirty::contains)
        .toList();
  }

  private Finding evaluate(
      String type, String id, Map<String, Object> snapshot, String handle, boolean asMain) {
    if (snapshot == null) {
      return Finding.HELD;
    }
    var finding = ownersFinding(type, snapshot);
    finding = finding.worse(actorRunFinding(type, id, snapshot, asMain));
    finding =
        switch (type) {
          case Erasure.SPEC -> finding.worse(bornInFinding(id, snapshot, asMain));
          case Erasure.REVIEW -> finding.worse(reviewFinding(id, snapshot, asMain));
          case Erasure.MESSAGE -> finding.worse(messageFinding(snapshot, handle, asMain));
          default -> finding;
        };
    return finding;
  }

  private Finding ownersFinding(String type, Map<String, Object> snapshot) {
    for (var owner : Erasure.ownersOf(type)) {
      if (!Erasure.PROJECT.equals(owner.type())) {
        continue;
      }
      var project = Snapshots.text(snapshot, owner.column());
      if (Strings.isNotBlank(project) && changeLog.isErased(Erasure.PROJECT, project)) {
        return Finding.gone("project '" + project + "' was pruned");
      }
    }
    return Finding.HELD;
  }

  private Finding actorRunFinding(
      String type, String id, Map<String, Object> snapshot, boolean asMain) {
    var actor = Snapshots.text(snapshot, Snapshots.ACTOR);
    var run = RunStore.runOf(actor);
    if (run.isEmpty() || (Erasure.RUN.equals(type) && run.get().equals(id))) {
      return Finding.HELD;
    }
    return entity(
        Erasure.RUN,
        run.get(),
        asMain,
        false,
        "main does not hold run '"
            + run.get()
            + "', which '"
            + actor
            + "' names, yet; runs sync first, so the next round settles this",
        "run '" + run.get() + "', which '" + actor + "' names, was withdrawn");
  }

  private Finding bornInFinding(String id, Map<String, Object> snapshot, boolean asMain) {
    var room = Snapshots.text(snapshot, "room_id");
    if (Strings.isBlank(room) || room.equals(id)) {
      return Finding.HELD;
    }
    return roomDependency(room, id, asMain);
  }

  private Finding reviewFinding(String id, Map<String, Object> snapshot, boolean asMain) {
    var spec = Snapshots.text(snapshot, "spec_id");
    return entity(
        Erasure.SPEC,
        spec,
        asMain,
        false,
        "main does not hold spec '"
            + spec
            + "', which review '"
            + id
            + "' is for, yet; specs sync before reviews, so the next round settles this",
        "spec '" + spec + "', which review '" + id + "' is for, was pruned");
  }

  private Finding messageFinding(Map<String, Object> snapshot, String handle, boolean asMain) {
    return messageFinding(snapshot, handle, asMain, new LinkedHashSet<>());
  }

  /**
   * A message's standing: its conversation, the run of an agent author — or, for a platform post, a
   * run of its owner in the conversation — and, on the node, a {@code reply_to} parent main has not
   * taken yet, whose standing it inherits so a reply is held or settled exactly with the post it
   * answers, never merely because that post has not reached main yet (they sync in one round,
   * parent first). A parent main holds passed its own admission, under its own author's run, and is
   * never asked again as the replier.
   */
  private Finding messageFinding(
      Map<String, Object> snapshot, String handle, boolean asMain, Set<String> visited) {
    var room = MessageStore.roomIdOf(snapshot);
    var finding = conversationFinding(room, asMain);
    var reply = Snapshots.text(snapshot, "reply_to");
    if (!asMain && Strings.isNotBlank(reply) && visited.add(reply)) {
      var messages = new MessageStore(db);
      var parent = messages.currentForSync(reply);
      if (parent != null && Strings.isBlank(messages.baseRevOf(reply))) {
        finding = finding.worse(messageFinding(parent, handle, false, visited));
      }
    }
    var author = Snapshots.text(snapshot, "author");
    if (MessageStore.SAIL_AUTHOR.equals(author)) {
      return finding.worse(sailRunFinding(handle, room, asMain));
    }
    var run = RunStore.runOf(author);
    return run.isEmpty() ? finding : finding.worse(runFinding(run.get(), author, asMain));
  }

  /**
   * A room a born-in spec names: held while main holds it, pending while the node holds it
   * unsynced, gone once it is erased or deleted here; a room this box never recorded is offered for
   * main to decide, and awaited by main until the room's own page arrives.
   */
  private Finding roomDependency(String room, String specId, boolean asMain) {
    if (changeLog.isErased(Erasure.ROOM, room)) {
      return Finding.gone(
          "room '" + room + "', which spec '" + specId + "' is born in, was pruned");
    }
    var liveBase = liveRowBase(Erasure.ROOM, room);
    if (liveBase.isPresent()) {
      return asMain || Strings.isNotBlank(liveBase.get())
          ? Finding.HELD
          : pendingRoom(room, specId);
    }
    if (anchoredByOtherSpec(room, specId, asMain)) {
      return Finding.HELD;
    }
    var head = changeLog.head(Erasure.ROOM, room);
    if (head.isPresent() && head.get().kind() == ChangeLog.Kind.TOMBSTONE) {
      return asMain ? Finding.HELD : Finding.gone("room '" + room + "' was deleted here");
    }
    return asMain ? pendingRoom(room, specId) : Finding.HELD;
  }

  /**
   * The synced base of a live row of {@code id} in {@code type}'s table, blank when main has not
   * taken it; empty when no live row — a lightweight read of {@code base_rev} that, unlike a
   * comparable snapshot, never touches content a raw row may lack.
   */
  private Optional<String> liveRowBase(String type, String id) {
    var table =
        switch (type) {
          case Erasure.SPEC -> "specs";
          case Erasure.ROOM -> "rooms";
          case Erasure.MESSAGE -> "room_messages";
          case Erasure.RUN -> "runs";
          case Erasure.REVIEW -> "reviews";
          default -> null;
        };
    return table == null
        ? Optional.empty()
        : db.queryOne(
            "SELECT coalesce(base_rev, '') FROM " + table + " WHERE id = ?", r -> r.text(0), id);
  }

  /**
   * Whether a spec other than {@code specId} lives in {@code room}, anchoring its conversation.
   * Main trusts every spec it holds; the node counts only a spec main has acknowledged, so two
   * unpublished siblings of a denied room never anchor each other and each is settled on its own.
   */
  private boolean anchoredByOtherSpec(String room, String specId, boolean asMain) {
    return db.queryOne(
            "SELECT 1 FROM specs WHERE (id = ?1 OR room_id = ?1) AND id <> ?2"
                + " AND (?3 OR coalesce(base_rev, '') <> '') LIMIT 1",
            r -> true,
            room,
            specId,
            asMain ? 1 : 0)
        .orElse(false);
  }

  private static Finding pendingRoom(String room, String specId) {
    return Finding.pending(
        "room '"
            + room
            + "', which spec '"
            + specId
            + "' is born in, is not held here yet; it syncs in its own page, so the next round"
            + " settles this");
  }

  /**
   * The conversation of a message: a room, or the spec living in it ({@link
   * RoomStore#holdsConversation}). Unlike a born-in spec, a message is not withheld on the node
   * while its room is unsynced — the room syncs before it and main refuses the post until it holds
   * the room — so a conversation held live reads held here; one erased, or deleted here with no
   * spec left conversing in it, is gone; and on main one it has never held is pending until it
   * arrives.
   */
  private Finding conversationFinding(String room, boolean asMain) {
    if (Strings.isBlank(room)) {
      return Finding.HELD;
    }
    if (new RoomStore(db).holdsLiveConversation(room)) {
      return Finding.HELD;
    }
    if (changeLog.isErased(Erasure.ROOM, room)) {
      return Finding.gone("room '" + room + "' was pruned");
    }
    if (!asMain) {
      return Finding.gone("room '" + room + "' is gone here");
    }
    return new RoomStore(db).holdsConversation(room)
        ? Finding.HELD
        : Finding.pending(
            "main does not hold room '"
                + room
                + "' yet; rooms sync before their messages, so the next round settles this");
  }

  private Finding entity(
      String type,
      String id,
      boolean asMain,
      boolean absentHeld,
      String pendingReason,
      String goneReason) {
    if (Strings.isBlank(id)) {
      return Finding.HELD;
    }
    if (changeLog.isErased(type, id)) {
      return Finding.gone(goneReason);
    }
    var liveBase = liveRowBase(type, id);
    if (liveBase.isPresent()) {
      return asMain || Strings.isNotBlank(liveBase.get())
          ? Finding.HELD
          : Finding.pending(pendingReason);
    }
    if (changeLog.head(type, id).isPresent()) {
      return asMain ? Finding.HELD : Finding.gone(goneReason);
    }
    return asMain
        ? Finding.pending(pendingReason)
        : absentHeld ? Finding.HELD : Finding.gone(goneReason);
  }

  private Finding runFinding(String run, String actor, boolean asMain) {
    var pending =
        "main does not hold run '"
            + run
            + "', which '"
            + actor
            + "' names, yet; runs sync first, so the next round settles this";
    var gone = "run '" + run + "', which '" + actor + "' names, was withdrawn";
    if (changeLog.isErased(Erasure.RUN, run)) {
      return Finding.gone(gone);
    }
    var head = changeLog.head(Erasure.RUN, run);
    if (head.isEmpty()) {
      return asMain ? Finding.pending(pending) : Finding.gone(gone);
    }
    if (head.get().kind() == ChangeLog.Kind.TOMBSTONE) {
      return asMain ? Finding.HELD : Finding.gone(gone);
    }
    return asMain || runOnMain(run) ? Finding.HELD : Finding.pending(pending);
  }

  private Finding sailRunFinding(String owner, String room, boolean asMain) {
    if (Strings.isBlank(owner)) {
      return Finding.HELD;
    }
    var pending =
        Finding.pending(
            "main does not hold a run of '"
                + owner
                + "' in room '"
                + room
                + "' yet; runs sync first, so the next round settles this");
    if (asMain) {
      return hasRunOfOwnerInConversation(owner, room) ? Finding.HELD : pending;
    }
    if (runOfOwnerInConversation(owner, room, true)) {
      return Finding.HELD;
    }
    return runOfOwnerInConversation(owner, room, false)
        ? pending
        : Finding.gone(
            "no run of '" + owner + "' in room '" + room + "' remains to support this post");
  }

  private boolean hasRunOfOwnerInConversation(String owner, String room) {
    return db.queryOne(
            "SELECT 1 FROM runs r WHERE r.owner = ?1 AND " + runInConversation() + " LIMIT 1",
            r -> true,
            owner,
            room)
        .orElse(false);
  }

  private boolean runOfOwnerInConversation(String owner, String room, boolean onMain) {
    return db.queryOne(
            "SELECT 1 FROM runs r WHERE r.owner = ?1 AND "
                + runInConversation()
                + " AND "
                + (onMain ? "" : "NOT ")
                + runOnMainClause()
                + " LIMIT 1",
            r -> true,
            owner,
            room)
        .orElse(false);
  }

  private boolean runOnMain(String run) {
    return db.queryOne(
            "SELECT 1 FROM runs r WHERE r.id = ? AND " + runOnMainClause() + " LIMIT 1",
            r -> true,
            run)
        .orElse(false);
  }

  private static String runInConversation() {
    return "(r.room_id = ?2 OR r.spec_id = ?2"
        + " OR r.spec_id IN (SELECT id FROM specs WHERE room_id = ?2))";
  }

  private static String runOnMainClause() {
    return "(coalesce(r.base_rev, '') <> '' AND coalesce(r.rev, '') = coalesce(r.base_rev, '')"
        + " AND NOT EXISTS (SELECT 1 FROM specs s"
        + " WHERE s.id = r.spec_id AND coalesce(s.base_rev, '') = ''))";
  }

  /**
   * A re-homed spec keeps its author: the run its {@code _actor} names still decides it, so a spec
   * whose run main then denies is withdrawn the round after, never republished as this box's own.
   */
  private void settleGone(String type, String id) {
    var store = SyncedEntities.require(type).store(db);
    var snapshot = store.currentForSync(id);
    if (Strings.isNotBlank(store.baseRevOf(id))) {
      revertToBase(id, store);
    } else if (onlyRoomGone(type, id, snapshot)) {
      var author = Snapshots.text(snapshot, Snapshots.ACTOR);
      Actor.run(Actor.main(author), () -> new SpecStore(db).reHomeToOwnRoom(id));
    } else {
      Actor.run(Actor.main(), () -> store.adoptForSync(id, null, null));
    }
  }

  private void revertToBase(String id, SyncedStore store) {
    var base = store.baseRevOf(id);
    var snapshot = store.comparableAtRev(id, base);
    var author = Snapshots.text(snapshot, Snapshots.ACTOR);
    Actor.run(Actor.main(author), () -> store.applyRevision(id, snapshot, base));
  }

  /**
   * Whether a born-in spec lost only its room: re-homing it keeps its author's work, but a spec
   * whose author's run or project is gone has nothing left to be re-homed under and is withdrawn.
   */
  private boolean onlyRoomGone(String type, String id, Map<String, Object> snapshot) {
    if (!Erasure.SPEC.equals(type) || snapshot == null) {
      return false;
    }
    var otherDependencies =
        ownersFinding(type, snapshot).worse(actorRunFinding(type, id, snapshot, false));
    return otherDependencies.status() != Status.GONE
        && bornInFinding(id, snapshot, false).status() == Status.GONE;
  }

  private Map<String, Object> currentSnapshot(String type, String id) {
    return SyncedEntities.require(type).store(db).currentForSync(id);
  }
}
