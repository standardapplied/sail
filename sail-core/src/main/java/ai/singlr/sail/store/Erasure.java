/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.sync.SyncedEntities;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What pruning removes, and the one place it is removed. A prune names roots — specs, a project,
 * or, for retention, single messages and runs — and {@link #closure} extends them to every
 * replicated entity that belongs to them: a spec's runs and reviews and the identity room it minted
 * (a room it was born into outlives it), a room's messages and runs, a message's replies, a
 * project's specs, rooms, files and runs. Those links between entities are declared here ({@link
 * #LINKS}) and nowhere else; the rows that belong to one entity (a spec's content and dependencies,
 * a run's principals and delivery ledger, a review's stages and findings) go with it through the
 * database's own cascades. The same links refuse a push that would make an entity belong to one
 * already erased ({@link #erasedOwner}).
 *
 * <p>{@link #erase} is main's side — the only author of an erasure: for every entity it removes the
 * live row, the open conflicts and every history entry, and records one erasure row naming who and
 * when; the events of an erased spec or room and of an erased project go too. {@link #adopt} is a
 * node applying one of main's erasure rows: it records main's row and removes everything that
 * belongs to the entity here, including children main never saw, whose own erasure rows — for the
 * ones main held — arrive in their own pages. Both are atomic, and adopting an erasure this box
 * already holds changes nothing.
 */
public final class Erasure {

  public static final String SPEC = "spec";
  public static final String ROOM = "room";
  public static final String PROJECT = "project";
  public static final String FILE = "file";
  public static final String RUN = "run";
  public static final String REVIEW = "review";
  public static final String MESSAGE = "message";

  /** One replicated entity, by its {@code change_log} type and id. */
  public record Target(String type, String id) {
    public Target {
      Strings.requireNonBlank(type, "An erasure target needs a type");
      Strings.requireNonBlank(id, "An erasure target needs an id");
    }
  }

  /** A push that would make {@code child} belong to {@code owner}, which was erased for good. */
  public static final class Orphaned extends IllegalStateException {
    public Orphaned(Target child, Target owner) {
      super(
          child.type()
              + " '"
              + child.id()
              + "' belongs to "
              + owner.type()
              + " '"
              + owner.id()
              + "', which was pruned");
    }
  }

  /** What one erasure removed: the entities, per type, and the events that went with them. */
  public record Result(List<Target> entities, int events) {
    public Result {
      entities = List.copyOf(entities);
    }

    public int count(String type) {
      return (int) entities.stream().filter(target -> target.type().equals(type)).count();
    }
  }

  /** A {@code child} belongs to the {@code parent} its {@code column} names. */
  private record Link(String parent, String child, String table, String column) {}

  private static final List<Link> LINKS =
      List.of(
          new Link(SPEC, RUN, "runs", "spec_id"),
          new Link(SPEC, REVIEW, "reviews", "spec_id"),
          new Link(ROOM, MESSAGE, "room_messages", "room_id"),
          new Link(ROOM, RUN, "runs", "room_id"),
          new Link(MESSAGE, MESSAGE, "room_messages", "reply_to"),
          new Link(PROJECT, SPEC, "specs", "project"),
          new Link(PROJECT, ROOM, "rooms", "project"),
          new Link(PROJECT, FILE, "project_files", "project"),
          new Link(PROJECT, RUN, "runs", "project"));

  private final Sqlite db;
  private final ChangeLog changeLog;
  private final SyncConflicts conflicts;
  private final EventStore events;
  private final EraseRequests requests;
  private final Map<String, SyncedStore> stores = new LinkedHashMap<>();

  public Erasure(Sqlite db) {
    this.db = Objects.requireNonNull(db, "db");
    this.changeLog = new ChangeLog(db);
    this.conflicts = new SyncConflicts(db);
    this.events = new EventStore(db);
    this.requests = new EraseRequests(db);
    for (var entity : SyncedEntities.all()) {
      stores.put(entity.type(), entity.store(db));
    }
  }

  /**
   * Every entity erasing {@code roots} removes, each once, roots first and then what belongs to
   * them. Reads this box as it is now: a spec's runs are the rows naming it and the tombstoned runs
   * whose last state named it.
   */
  public List<Target> closure(List<Target> roots) {
    var closure = new LinkedHashSet<Target>();
    for (var root : roots) {
      requireKnownType(root.type());
      extend(root, closure);
    }
    return List.copyOf(closure);
  }

  /**
   * Erases {@code entities} as main: one transaction that removes each live row, its conflicts and
   * its history, records one erasure row per entity attributed to {@code actor}, and drops the
   * events of every erased spec, room and project. An entity already erased keeps the erasure it
   * has and is not counted again.
   */
  public Result erase(List<Target> entities, String actor, String origin) {
    return db.transaction(
        () -> {
          deferReplyConstraint();
          var erased = new ArrayList<Target>();
          for (var target : entities) {
            requireKnownType(target.type());
            if (isErased(target)) {
              continue;
            }
            remove(target);
            changeLog.erase(target.type(), target.id(), erasureRev(target), actor, origin);
            erased.add(target);
          }
          return new Result(erased, dropEvents(erased));
        });
  }

  /**
   * Erases {@code root} and everything that belongs to it as main, in one transaction, and answers
   * the root's erasure rev — the one it already carries when it was erased before, without reading
   * what belongs to it again.
   */
  public String eraseClosure(Target root, String actor, String origin) {
    return db.transaction(
        () -> {
          if (!isErased(root)) {
            erase(closure(List.of(root)), actor, origin);
          }
          return changeLog.head(root.type(), root.id()).map(ChangeLog.Entry::rev).orElseThrow();
        });
  }

  /**
   * Applies main's erasure of one entity on this box: records main's erasure row at {@code rev} and
   * removes the entity and everything that belongs to it here. The entities that belong to it leave
   * no erasure row of their own — main's arrive with their pages. Returns whether anything changed;
   * an erasure this box already holds changes nothing, and one of an entity this box never held
   * records only the erasure row.
   */
  public boolean adopt(String type, String id, String rev) {
    requireKnownType(type);
    Strings.requireNonBlank(rev, "An adopted erasure carries main's rev");
    return db.transaction(
        () -> {
          if (changeLog.hasErasure(type, id, rev)) {
            return false;
          }
          deferReplyConstraint();
          var root = new Target(type, id);
          var closure = closure(List.of(root));
          for (var target : closure) {
            remove(target);
            if (target.equals(root)) {
              changeLog.erase(type, id, rev, null, "sync");
            } else {
              changeLog.purge(target.type(), target.id());
            }
          }
          dropEvents(closure);
          return true;
        });
  }

  /** Whether the latest word on {@code target} here is an erasure. */
  public boolean isErased(Target target) {
    return changeLog
        .head(target.type(), target.id())
        .map(head -> head.kind() == ChangeLog.Kind.ERASURE)
        .orElse(false);
  }

  /**
   * The erased entity a pushed {@code snapshot} of {@code type} would belong to through a declared
   * link, if any. A parent this box does not hold, or only holds deleted, is no reason to refuse —
   * sync never depends on the order entities arrive in — but an erased one never comes back, so
   * nothing may belong to it again.
   */
  public static Optional<Target> erasedOwner(
      ChangeLog changeLog, String type, Map<String, Object> snapshot) {
    for (var link : LINKS) {
      var parentId = link.child().equals(type) ? Snapshots.text(snapshot, link.column()) : null;
      if (Strings.isNotBlank(parentId)
          && changeLog
              .head(link.parent(), parentId)
              .filter(head -> head.kind() == ChangeLog.Kind.ERASURE)
              .isPresent()) {
        return Optional.of(new Target(link.parent(), parentId));
      }
    }
    return Optional.empty();
  }

  /**
   * Adds {@code target} and everything linked to it. A spec's identity room is followed whether or
   * not this box holds a row of it: messages are posted to a spec's room before any room row is
   * minted.
   */
  private void extend(Target target, Set<Target> closure) {
    if (!closure.add(target)) {
      return;
    }
    for (var link : LINKS) {
      if (link.parent().equals(target.type())) {
        heldBy(link, target.id()).forEach(id -> extend(new Target(link.child(), id), closure));
      }
    }
    if (SPEC.equals(target.type()) && mintedItsRoom(target.id())) {
      extend(new Target(ROOM, target.id()), closure);
    }
  }

  /**
   * The ids of {@code link}'s child that belong to {@code owner}: the live rows naming it, then the
   * entities whose latest entry here is a tombstone whose last state named it — a deleted child
   * still belongs to its parent, and its history must not outlive the parent's erasure.
   */
  private List<String> heldBy(Link link, String owner) {
    var ids =
        new LinkedHashSet<>(
            db.query(
                "SELECT id FROM "
                    + link.table()
                    + " WHERE "
                    + link.column()
                    + " = ? ORDER BY rowid",
                row -> row.text(0),
                owner));
    ids.addAll(
        db.query(
            """
            SELECT h.entity_id FROM change_heads h JOIN change_log l ON l.seq = h.seq
            WHERE h.entity_type = ? AND l.kind = 'tombstone'
            AND json_extract(l.snapshot, '$.' || ?) = ?
            ORDER BY h.seq""",
            row -> row.text(0),
            link.child(),
            link.column(),
            owner));
    return List.copyOf(ids);
  }

  /**
   * Whether spec {@code id} minted the room that shares its id — the identity room it is erased
   * with — rather than being born into a room it only borrows. Read from the live row, or from the
   * last state a tombstone kept; a spec this box holds neither of minted nothing here, so a room
   * that happens to share its id is not its to take.
   */
  private boolean mintedItsRoom(String id) {
    var live =
        db.queryOne("SELECT COALESCE(room_id, id) FROM specs WHERE id = ?", row -> row.text(0), id);
    if (live.isPresent()) {
      return live.get().equals(id);
    }
    return changeLog
        .head(SPEC, id)
        .filter(head -> head.kind() == ChangeLog.Kind.TOMBSTONE)
        .map(head -> identity(id, Snapshots.text(YamlUtil.parseMap(head.snapshot()), "room_id")))
        .orElse(false);
  }

  private static boolean identity(String specId, String roomId) {
    return Strings.isBlank(roomId) || roomId.equals(specId);
  }

  private void remove(Target target) {
    stores.get(target.type()).eraseRow(target.id());
    conflicts.erase(target.type(), target.id());
    requests.drop(target.type(), target.id());
  }

  private int dropEvents(List<Target> erased) {
    var dropped = 0;
    for (var target : erased) {
      dropped +=
          switch (target.type()) {
            case SPEC, ROOM -> events.eraseBySpec(target.id());
            case PROJECT -> events.eraseByProject(target.id());
            default -> 0;
          };
    }
    return dropped;
  }

  /** A rev no other entry of the entity carries: one past its latest counter, hashed anew. */
  private String erasureRev(Target target) {
    var latest = changeLog.head(target.type(), target.id()).map(ChangeLog.Entry::rev).orElse(null);
    return Revisions.next(
        latest, "erasure|" + target.type() + "|" + target.id() + "|" + DateTimeUtils.newId());
  }

  /**
   * Messages reply to messages of the same room, and an erasure removes a thread in whatever order
   * its entities come; the reply constraint is checked when the erasure commits, not row by row.
   */
  private void deferReplyConstraint() {
    db.execute("PRAGMA defer_foreign_keys = ON");
  }

  private void requireKnownType(String type) {
    if (!stores.containsKey(type)) {
      throw new IllegalArgumentException("Unknown entity type: " + type);
    }
  }
}
