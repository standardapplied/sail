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
import java.util.Set;

/**
 * What pruning removes, and the one place it is removed. A prune names roots — specs, a project,
 * or, for retention, single messages and runs — and {@link #closure} extends them to every
 * replicated entity that belongs to them: a spec's runs and reviews and the identity room it minted
 * (a room it was born into outlives it), a room's messages and runs, a project's specs, rooms,
 * files and runs. Those links between entities are declared here and nowhere else; the rows that
 * belong to one entity (a spec's content and dependencies, a run's principals and delivery ledger,
 * a review's stages and findings) go with it through the database's own cascades.
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

  /** What one erasure removed: the entities, per type, and the events that went with them. */
  public record Result(List<Target> entities, int events) {
    public Result {
      entities = List.copyOf(entities);
    }

    public int count(String type) {
      return (int) entities.stream().filter(target -> target.type().equals(type)).count();
    }
  }

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
   * the root's erasure rev — the one it already carries when it was erased before.
   */
  public String eraseClosure(Target root, String actor, String origin) {
    return db.transaction(
        () -> {
          erase(closure(List.of(root)), actor, origin);
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

  private void extend(Target target, Set<Target> closure) {
    if (!closure.add(target)) {
      return;
    }
    switch (target.type()) {
      case SPEC -> {
        heldBy(RUN, "runs", "spec_id", target.id()).forEach(id -> extend(run(id), closure));
        heldBy(REVIEW, "reviews", "spec_id", target.id())
            .forEach(id -> extend(new Target(REVIEW, id), closure));
        var identityRoom = new Target(ROOM, target.id());
        if (mintedItsRoom(target.id()) && held(identityRoom)) {
          extend(identityRoom, closure);
        }
      }
      case ROOM -> {
        heldBy(MESSAGE, "room_messages", "room_id", target.id())
            .forEach(id -> extend(new Target(MESSAGE, id), closure));
        heldBy(RUN, "runs", "room_id", target.id()).forEach(id -> extend(run(id), closure));
      }
      case PROJECT -> {
        heldBy(SPEC, "specs", "project", target.id())
            .forEach(id -> extend(new Target(SPEC, id), closure));
        heldBy(ROOM, "rooms", "project", target.id())
            .forEach(id -> extend(new Target(ROOM, id), closure));
        heldBy(FILE, "project_files", "project", target.id())
            .forEach(id -> extend(new Target(FILE, id), closure));
        heldBy(RUN, "runs", "project", target.id()).forEach(id -> extend(run(id), closure));
      }
      default -> {}
    }
  }

  private static Target run(String id) {
    return new Target(RUN, id);
  }

  /**
   * The ids of {@code type} that belong to {@code owner} through {@code column}: the live rows of
   * {@code table} naming it, then the entities whose latest entry here is a tombstone whose last
   * state named it — a deleted child still belongs to its parent, and its history must not outlive
   * the parent's erasure.
   */
  private List<String> heldBy(String type, String table, String column, String owner) {
    var ids =
        new LinkedHashSet<>(
            db.query(
                "SELECT id FROM " + table + " WHERE " + column + " = ? ORDER BY rowid",
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
            type,
            column,
            owner));
    return List.copyOf(ids);
  }

  /**
   * Whether spec {@code id} minted the room that shares its id — the identity room it is erased
   * with — rather than being born into a room it only borrows. Read from the live row, or from the
   * last state a tombstone kept.
   */
  private boolean mintedItsRoom(String id) {
    var roomId =
        db.queryOne("SELECT COALESCE(room_id, id) FROM specs WHERE id = ?", row -> row.text(0), id)
            .or(
                () ->
                    changeLog
                        .head(SPEC, id)
                        .filter(head -> head.kind() == ChangeLog.Kind.TOMBSTONE)
                        .map(head -> Snapshots.text(YamlUtil.parseMap(head.snapshot()), "room_id")))
            .orElse(id);
    return Strings.isBlank(roomId) || roomId.equals(id);
  }

  /** Whether this box holds {@code target} at all: a live row, or any entry of its history. */
  private boolean held(Target target) {
    return stores.get(target.type()).latestRev(target.id()) != null
        || changeLog.head(target.type(), target.id()).isPresent();
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

  /**
   * A rev no other entry of the entity carries: one past its latest counter, hashed over who erased
   * it and when, so a node can tell this erasure from an earlier one of the same id.
   */
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
