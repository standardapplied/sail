/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.sync.SyncedEntities;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What one box holds of the entities an offer depends on, read as its role reads them. Main holds a
 * dependency while it has any live or deleted entry for it, awaits one it has never seen, and has
 * lost one it erased. A node reads its own copy for what main holds: a live row carries a {@code
 * base_rev} once main has taken it; a deletion heard from main, or a local deletion of a row main
 * had taken, is main's word; a withdrawal, or a local deletion of a row main never took, is
 * something main never held and never will.
 */
public sealed interface Holdings {

  /** Main's reading of its own database. */
  static Holdings main(Sqlite db) {
    return new OnMain(new Entries(db));
  }

  /** A node's reading of its copy, for what main holds. */
  static Holdings node(Sqlite db) {
    return new OnNode(new Entries(db));
  }

  /** The project an entity belongs to: gone once erased, held otherwise. */
  Standing project(String project);

  /** The run {@code actor}, a run principal, names, which decides a revision it wrote. */
  Standing run(String run, String actor);

  /**
   * The run {@code author}, a run principal, names, which decides a post it made: main places the
   * run in the post's conversation through the run's spec, so the spec must be there too.
   */
  Standing authorRun(String run, String author);

  /**
   * The room spec {@code specId} is born in. A deleted room main did hold is a fact main knows: a
   * spec main holds stays decidable in it, while a spec main has not taken has nowhere to land and
   * is gone.
   */
  Standing bornInRoom(String room, String specId);

  /**
   * The spec review {@code reviewId} is for; a review main holds stays decidable for a spec gone.
   */
  Standing specOf(String spec, String reviewId);

  /** The conversation a message is posted in: a room, or the spec living in it. */
  Standing conversation(String room);

  /** The message a reply answers. */
  Standing parent(String message);

  /**
   * On a node, the parent of a reply that is held here but not yet taken by main, whose standing
   * the reply inherits: they settle together. Empty on main, and for a parent main has taken.
   */
  Optional<Map<String, Object>> unsyncedParent(String message);

  /** A run of {@code owner} in conversation {@code room}, which a platform post there rests on. */
  Standing ownerRun(String owner, String room);

  /** The reads both roles share. */
  final class Entries {
    private final Sqlite db;
    private final ChangeLog changeLog;

    Entries(Sqlite db) {
      this.db = db;
      this.changeLog = new ChangeLog(db);
    }

    boolean erased(String type, String id) {
      return changeLog.isErased(type, id);
    }

    Optional<ChangeLog.Entry> head(String type, String id) {
      return changeLog.head(type, id);
    }

    boolean tombstoned(String type, String id) {
      return head(type, id).filter(h -> h.kind() == ChangeLog.Kind.TOMBSTONE).isPresent();
    }

    /** Whether this box has any word of {@code id}: a live row, or an entry in its log. */
    boolean knows(String type, String id) {
      return store(type).liveBase(id).isPresent() || head(type, id).isPresent();
    }

    SyncedStore store(String type) {
      return SyncedEntities.require(type).store(db);
    }

    RunStore runs() {
      return new RunStore(db);
    }

    RoomStore rooms() {
      return new RoomStore(db);
    }

    MessageStore messages() {
      return new MessageStore(db);
    }

    /**
     * Whether a spec other than {@code except} lives in {@code room} — the room's own spec, or one
     * born in it — anchoring its conversation; {@code takenByMain} counts only a spec main has
     * taken.
     */
    boolean anchored(String room, String except, boolean takenByMain) {
      return db.queryOne(
              "SELECT 1 FROM specs WHERE (id = ?1 OR room_id = ?1) AND id <> ?2"
                  + " AND (?3 = 0 OR coalesce(base_rev, '') <> '') LIMIT 1",
              r -> true,
              room,
              Objects.toString(except, ""),
              takenByMain ? 1 : 0)
          .orElse(false);
    }

    /**
     * Whether main ever held {@code id}: its deletion here was heard from main, or made over a row
     * main had taken. A withdrawal, or a deletion of a row main never took, says it did not.
     */
    boolean mainHeld(String type, String id) {
      var head = head(type, id);
      if (head.isEmpty() || head.get().withdrawn()) {
        return false;
      }
      return head.get().heardFromMain() || Strings.isNotBlank(store(type).baseRevOf(id));
    }
  }

  /** Main: a dependency it has any entry for is held; one it never saw is awaited. */
  final class OnMain implements Holdings {
    private final Entries entries;

    private OnMain(Entries entries) {
      this.entries = entries;
    }

    @Override
    public Standing project(String project) {
      return entries.erased(Erasure.PROJECT, project)
          ? new Standing.Gone(Reasons.projectPruned(project))
          : Standing.HELD;
    }

    @Override
    public Standing run(String run, String actor) {
      return known(Erasure.RUN, run, Reasons.runPending(run, actor), Reasons.runGone(run, actor));
    }

    @Override
    public Standing authorRun(String run, String author) {
      return run(run, author);
    }

    @Override
    public Standing bornInRoom(String room, String specId) {
      if (entries.erased(Erasure.ROOM, room)) {
        return new Standing.Gone(Reasons.roomPruned(room, specId));
      }
      var live = entries.store(Erasure.ROOM).liveBase(room).isPresent() || anchored(room, specId);
      if (!live && !entries.knows(Erasure.SPEC, specId) && entries.tombstoned(Erasure.ROOM, room)) {
        return new Standing.Gone(Reasons.roomDeleted(room, specId));
      }
      return live || entries.head(Erasure.ROOM, room).isPresent()
          ? Standing.HELD
          : new Standing.Pending(Reasons.roomPending(room, specId));
    }

    @Override
    public Standing specOf(String spec, String reviewId) {
      return known(
          Erasure.SPEC,
          spec,
          Reasons.specPending(spec, reviewId),
          Reasons.specPruned(spec, reviewId));
    }

    @Override
    public Standing conversation(String room) {
      if (entries.erased(Erasure.ROOM, room)) {
        return new Standing.Gone(Reasons.roomPruned(room));
      }
      return entries.rooms().holdsConversation(room)
          ? Standing.HELD
          : new Standing.Pending(Reasons.conversationPending(room));
    }

    @Override
    public Standing parent(String message) {
      if (entries.erased(Erasure.MESSAGE, message)) {
        return new Standing.Gone(Reasons.parentPruned(message));
      }
      return entries.messages().findById(message).isPresent()
          ? Standing.HELD
          : new Standing.Pending(Reasons.parentPending(message));
    }

    @Override
    public Optional<Map<String, Object>> unsyncedParent(String message) {
      return Optional.empty();
    }

    @Override
    public Standing ownerRun(String owner, String room) {
      return entries.runs().ranInConversation(owner, room)
          ? Standing.HELD
          : new Standing.Pending(Reasons.ownerRunPending(owner, room));
    }

    private boolean anchored(String room, String except) {
      return entries.anchored(room, except, false);
    }

    private Standing known(String type, String id, String pending, String gone) {
      if (entries.erased(type, id)) {
        return new Standing.Gone(gone);
      }
      return entries.knows(type, id) ? Standing.HELD : new Standing.Pending(pending);
    }
  }

  /**
   * A node: a dependency main has taken is held; one held here that main has not taken is awaited;
   * one main never held, and never will, is gone. A deleted dependency main did hold is still a
   * fact main knows, so an update of something main holds is decided by main, while a new spec born
   * in such a room has nowhere to land and is gone.
   */
  final class OnNode implements Holdings {
    private final Entries entries;

    private OnNode(Entries entries) {
      this.entries = entries;
    }

    @Override
    public Standing project(String project) {
      return entries.erased(Erasure.PROJECT, project)
          ? new Standing.Gone(Reasons.projectPruned(project))
          : Standing.HELD;
    }

    @Override
    public Standing run(String run, String actor) {
      return run(run, actor, entries.runs().heldByMain(run));
    }

    @Override
    public Standing authorRun(String run, String author) {
      return run(run, author, entries.runs().heldByMainInConversation(run));
    }

    private Standing run(String run, String actor, boolean heldByMain) {
      if (entries.erased(Erasure.RUN, run) || entries.runs().findById(run).isEmpty()) {
        return new Standing.Gone(Reasons.runGone(run, actor));
      }
      return heldByMain ? Standing.HELD : new Standing.Pending(Reasons.runPending(run, actor));
    }

    @Override
    public Standing bornInRoom(String room, String specId) {
      if (entries.erased(Erasure.ROOM, room)) {
        return new Standing.Gone(Reasons.roomPruned(room, specId));
      }
      var live = entries.store(Erasure.ROOM).liveBase(room);
      if (live.isPresent()) {
        return Strings.isNotBlank(live.get()) || anchored(room, specId)
            ? Standing.HELD
            : new Standing.Pending(Reasons.roomPending(room, specId));
      }
      if (anchored(room, specId) || entries.head(Erasure.ROOM, room).isEmpty()) {
        return Standing.HELD;
      }
      return takenByMain(Erasure.SPEC, specId)
          ? Standing.HELD
          : new Standing.Gone(Reasons.roomDeleted(room, specId));
    }

    @Override
    public Standing specOf(String spec, String reviewId) {
      if (entries.erased(Erasure.SPEC, spec)) {
        return new Standing.Gone(Reasons.specPruned(spec, reviewId));
      }
      var live = entries.store(Erasure.SPEC).liveBase(spec);
      if (live.isPresent()) {
        return Strings.isNotBlank(live.get())
            ? Standing.HELD
            : new Standing.Pending(Reasons.specPending(spec, reviewId));
      }
      return takenByMain(Erasure.REVIEW, reviewId) || entries.mainHeld(Erasure.SPEC, spec)
          ? Standing.HELD
          : new Standing.Gone(Reasons.specPruned(spec, reviewId));
    }

    /**
     * Whether main has taken {@code id}, so an offer of it is an update main decides on authority
     * alone: a deleted dependency is then a fact main knows, not a reason to give the work up.
     */
    private boolean takenByMain(String type, String id) {
      return Strings.isNotBlank(entries.store(type).baseRevOf(id));
    }

    @Override
    public Standing conversation(String room) {
      if (entries.erased(Erasure.ROOM, room)) {
        return new Standing.Gone(Reasons.roomPruned(room));
      }
      if (entries.rooms().holdsLiveConversation(room)) {
        return Standing.HELD;
      }
      var type = entries.head(Erasure.ROOM, room).isPresent() ? Erasure.ROOM : Erasure.SPEC;
      return entries.head(type, room).isEmpty() || entries.mainHeld(type, room)
          ? Standing.HELD
          : new Standing.Gone(Reasons.conversationGone(room));
    }

    @Override
    public Standing parent(String message) {
      if (entries.erased(Erasure.MESSAGE, message)) {
        return new Standing.Gone(Reasons.parentPruned(message));
      }
      return entries.messages().findById(message).isPresent()
          ? Standing.HELD
          : new Standing.Gone(Reasons.parentGone(message));
    }

    @Override
    public Optional<Map<String, Object>> unsyncedParent(String message) {
      var messages = entries.messages();
      return messages
          .liveBase(message)
          .filter(Strings::isBlank)
          .map(ignored -> messages.currentForSync(message));
    }

    @Override
    public Standing ownerRun(String owner, String room) {
      var runs = entries.runs();
      if (runs.ranInConversation(owner, room, true)) {
        return Standing.HELD;
      }
      return runs.ranInConversation(owner, room, false)
          ? new Standing.Pending(Reasons.ownerRunPending(owner, room))
          : new Standing.Gone(Reasons.ownerRunGone(owner, room));
    }

    /**
     * Whether a spec main has taken, other than {@code except}, lives in {@code room}, anchoring
     * its conversation: two unpublished siblings of a gone room never anchor each other.
     */
    private boolean anchored(String room, String except) {
      return entries.anchored(room, except, true);
    }
  }

  /** The words a standing carries, as main's refusal or denial says them. */
  final class Reasons {
    private Reasons() {}

    static String projectPruned(String project) {
      return "project '" + project + "' was pruned";
    }

    static String runPending(String run, String actor) {
      return "main does not hold run '"
          + run
          + "', which '"
          + actor
          + "' names, yet; runs sync first, so the next round settles this";
    }

    static String runGone(String run, String actor) {
      return "run '" + run + "', which '" + actor + "' names, was withdrawn";
    }

    static String roomPruned(String room, String specId) {
      return "room '" + room + "', which spec '" + specId + "' is born in, was pruned";
    }

    static String roomDeleted(String room, String specId) {
      return "room '" + room + "', which spec '" + specId + "' is born in, was deleted";
    }

    static String roomPending(String room, String specId) {
      return "room '"
          + room
          + "', which spec '"
          + specId
          + "' is born in, is not held here yet; it syncs in its own page, so the next round"
          + " settles this";
    }

    static String specPending(String spec, String reviewId) {
      return "main does not hold spec '"
          + spec
          + "', which review '"
          + reviewId
          + "' is for, yet; specs sync before reviews, so the next round settles this";
    }

    static String specPruned(String spec, String reviewId) {
      return "spec '" + spec + "', which review '" + reviewId + "' is for, is gone";
    }

    static String roomPruned(String room) {
      return "room '" + room + "' was pruned";
    }

    static String conversationPending(String room) {
      return "main does not hold room '"
          + room
          + "' yet; rooms sync before their messages, so the next round settles this";
    }

    static String conversationGone(String room) {
      return "room '" + room + "' is gone here";
    }

    static String parentPruned(String message) {
      return "message '" + message + "', which this replies to, was pruned";
    }

    static String parentPending(String message) {
      return "main does not hold message '"
          + message
          + "', which this replies to, yet; it syncs before its replies, so the next round"
          + " settles this";
    }

    static String parentGone(String message) {
      return "message '" + message + "', which this replies to, is gone here";
    }

    static String ownerRunPending(String owner, String room) {
      return "main does not hold a run of '"
          + owner
          + "' in room '"
          + room
          + "' yet; runs sync first, so the next round settles this";
    }

    static String ownerRunGone(String owner, String room) {
      return "no run of '" + owner + "' in room '" + room + "' remains to support this post";
    }
  }
}
