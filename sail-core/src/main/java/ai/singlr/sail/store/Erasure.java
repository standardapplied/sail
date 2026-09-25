/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.sync.SyncedEntities;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What pruning removes, and the one place it is removed. A prune names roots — specs, a project,
 * or, for retention, single messages and runs — and {@link #closure} extends them to every
 * replicated entity that belongs to them: a spec's runs and reviews, a room's messages and runs, a
 * message's replies, a project's specs, rooms, files and runs, and the room a spec minted once no
 * spec left behind still converses in it (a room a spec was born into outlives it while its minter
 * does). Those links between entities are declared here ({@link #LINKS}) and nowhere else; the
 * journal refuses a state that would belong to an erased entity through the same links ({@link
 * #ownersOf}), and the rows that belong to one entity (a spec's content and dependencies, a run's
 * principals and delivery ledger, a review's stages and findings) go with it through the database's
 * own cascades.
 *
 * <p>{@link #erase} is main's side — the only author of an erasure: for every entity it removes the
 * live row, the open conflicts, the box-local rows keyed by it and every history entry, and records
 * one erasure row naming who and when. {@link #adopt} is a node applying one of main's erasure
 * rows: it records main's row and removes the entity with what belongs to it that main never
 * acknowledged — everything main held gets an erasure row of its own, decided on main's copy, and
 * arrives in its own page. Both are atomic, and adopting an erasure this box already holds changes
 * nothing.
 */
public final class Erasure {

  public static final String SPEC = "spec";
  public static final String ROOM = "room";
  public static final String PROJECT = "project";
  public static final String FILE = "file";
  public static final String RUN = "run";
  public static final String REVIEW = "review";
  public static final String MESSAGE = "message";

  private static final int BATCH = 500;

  /** One replicated entity, by its {@code change_log} type and id. */
  public record Target(String type, String id) {
    public Target {
      Strings.requireNonBlank(type, "An erasure target needs a type");
      Strings.requireNonBlank(id, "An erasure target needs an id");
    }
  }

  /** What one erasure removed: the entities, per type, and the events that went with them. */
  public record Result(List<Target> entities, int events) {
    public static final Result NONE = new Result(List.of(), 0);

    public Result {
      entities = List.copyOf(entities);
    }

    public int count(String type) {
      return (int) entities.stream().filter(target -> target.type().equals(type)).count();
    }

    public Result plus(Result other) {
      var all = new ArrayList<>(entities);
      all.addAll(other.entities);
      return new Result(all, events + other.events);
    }
  }

  /** The entity type a state belongs to through {@code column}, which names its id. */
  public record Owner(String type, String column) {}

  /**
   * A {@code child} belongs to the {@code parent} its {@code column} names. A {@code bound} child
   * cannot outlive its parent's row — the database refuses it — so every box follows the link.
   */
  private record Link(String parent, String child, String table, String column, boolean bound) {}

  private static final List<Link> LINKS =
      List.of(
          new Link(SPEC, RUN, "runs", "spec_id", false),
          new Link(SPEC, REVIEW, "reviews", "spec_id", false),
          new Link(ROOM, MESSAGE, "room_messages", "room_id", false),
          new Link(ROOM, RUN, "runs", "room_id", false),
          new Link(MESSAGE, MESSAGE, "room_messages", "reply_to", true),
          new Link(PROJECT, SPEC, "specs", "project", false),
          new Link(PROJECT, ROOM, "rooms", "project", false),
          new Link(PROJECT, FILE, "project_files", "project", false),
          new Link(PROJECT, RUN, "runs", "project", false));

  private final Sqlite db;
  private final ChangeLog changeLog;
  private final SyncConflicts conflicts;
  private final EventStore events;
  private final EraseRequests requests;
  private final SpecStore specs;
  private final RunStore runs;
  private final SlackThreadStore slackThreads;
  private final Map<String, SyncedStore> stores = new LinkedHashMap<>();

  public Erasure(Sqlite db) {
    this.db = Objects.requireNonNull(db, "db");
    this.changeLog = new ChangeLog(db);
    this.conflicts = new SyncConflicts(db);
    this.events = new EventStore(db);
    this.requests = new EraseRequests(db);
    this.specs = new SpecStore(db);
    this.runs = new RunStore(db);
    this.slackThreads = new SlackThreadStore(db);
    for (var entity : SyncedEntities.all()) {
      stores.put(entity.type(), entity.store(db));
    }
  }

  /** The owners a state of {@code type} names through a declared link. */
  public static List<Owner> ownersOf(String type) {
    return LINKS.stream()
        .filter(link -> link.child().equals(type))
        .map(link -> new Owner(link.parent(), link.column()))
        .toList();
  }

  /**
   * Every entity erasing {@code roots} removes, each once, roots first and then what belongs to
   * them, as main decides it: a spec's runs are the rows naming it and the tombstoned runs whose
   * last state named it.
   */
  public List<Target> closure(List<Target> roots) {
    return closure(roots, false);
  }

  /**
   * Erases {@code entities} as main: one transaction that removes each live row, its conflicts, its
   * box-local rows and its history, and records one erasure row per entity attributed to the bound
   * {@link ai.singlr.sail.identity.Actor}. An entity already erased keeps the erasure it has and is
   * not counted again.
   */
  public Result erase(List<Target> entities, String origin) {
    return db.transaction(
        () -> {
          deferReplyConstraint();
          var erased = new ArrayList<Target>();
          var dropped = 0;
          for (var target : entities) {
            requireKnownType(target.type());
            if (isErased(target)) {
              continue;
            }
            dropped += remove(target);
            changeLog.erase(target.type(), target.id(), erasureRev(target), origin);
            erased.add(target);
          }
          return new Result(erased, dropped);
        });
  }

  /**
   * Applies main's erasure of one entity on this box: records main's erasure row at {@code rev} and
   * removes the entity with what belongs to it that main never acknowledged. What main held leaves
   * with its own erasure row, in its own page. Returns whether anything changed; an erasure this
   * box already holds changes nothing, and one of an entity this box never held records only the
   * row.
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
          for (var target : closure(List.of(root), true)) {
            remove(target);
            if (target.equals(root)) {
              changeLog.erase(type, id, rev, "sync");
            } else {
              changeLog.purge(target.type(), target.id());
            }
          }
          return true;
        });
  }

  /**
   * Removes {@code roots} and what belongs to them that main never acknowledged, leaving no erasure
   * row: how a node prunes what it alone ever held, so there is nothing to ask of main and the ids
   * stay free. Returns what went. Journals nothing and names no actor: it rewrites history under
   * erasure, and what it removes was never acknowledged by anyone else.
   */
  public Result discard(List<Target> roots) {
    return db.transaction(
        () -> {
          deferReplyConstraint();
          var discarded = closure(roots, true);
          var dropped = 0;
          for (var target : discarded) {
            dropped += remove(target);
            changeLog.purge(target.type(), target.id());
          }
          return new Result(discarded, dropped);
        });
  }

  /** Whether this box holds anything of {@code target}: a history of it, or what belongs to it. */
  public boolean holds(Target target) {
    return changeLog.head(target.type(), target.id()).isPresent()
        || closure(List.of(target)).size() > 1;
  }

  /** Whether the latest word on {@code target} here is an erasure. */
  public boolean isErased(Target target) {
    return changeLog.isErased(target.type(), target.id());
  }

  /** Whether {@code target} has a change here that main has not taken yet. */
  public boolean unpushed(Target target) {
    return stores.get(target.type()).dirtyIds().contains(target.id());
  }

  /** Whether main never acknowledged {@code target}: it has no synced base on this box. */
  public boolean unacknowledged(Target target) {
    return stores.get(target.type()).baseRevOf(target.id()) == null;
  }

  /**
   * The closure, walked one level at a time with each link queried once per level, so its cost is
   * linear in what it finds. A node ({@code asNode}) follows only children main never acknowledged,
   * and bound ones.
   */
  private List<Target> closure(List<Target> roots, boolean asNode) {
    var closure = new LinkedHashSet<Target>();
    var frontier = new ArrayList<Target>();
    for (var root : roots) {
      requireKnownType(root.type());
      if (closure.add(root)) {
        frontier.add(root);
      }
    }
    var tombstoned = new HashMap<Link, Map<String, List<String>>>();
    while (!frontier.isEmpty()) {
      var next = new ArrayList<Target>();
      for (var link : LINKS) {
        var owners = idsOf(frontier, link.parent());
        if (owners.isEmpty()) {
          continue;
        }
        for (var id : heldBy(link, owners, tombstoned)) {
          var child = new Target(link.child(), id);
          if ((!asNode || link.bound() || unacknowledged(child)) && closure.add(child)) {
            next.add(child);
          }
        }
      }
      if (next.isEmpty()) {
        for (var room : roomsLeftBehind(closure, asNode)) {
          closure.add(room);
          next.add(room);
        }
      }
      frontier = next;
    }
    return List.copyOf(closure);
  }

  /**
   * The rooms of specs in {@code closure} that nothing left behind converses in: a room goes when
   * the spec that minted it is erased or going and no spec outside the closure — live, or deleted
   * and restorable — lives in it. A spec this box holds neither a row nor a tombstone of minted
   * nothing here, so a room that merely shares its id is not its to take.
   */
  private List<Target> roomsLeftBehind(Set<Target> closure, boolean asNode) {
    var candidates = new LinkedHashSet<String>();
    for (var target : closure) {
      if (!SPEC.equals(target.type())) {
        continue;
      }
      specs
          .lastKnown(target.id())
          .map(SpecStore.LastKnown::roomIdOrIdentity)
          .filter(room -> !closure.contains(new Target(ROOM, room)))
          .filter(
              room -> closure.contains(new Target(SPEC, room)) || isErased(new Target(SPEC, room)))
          .filter(room -> !asNode || unacknowledged(new Target(ROOM, room)))
          .ifPresent(candidates::add);
    }
    var rooms = new ArrayList<Target>();
    for (var conversing : specs.inRooms(candidates).entrySet()) {
      if (conversing.getValue().stream().allMatch(id -> closure.contains(new Target(SPEC, id)))) {
        rooms.add(new Target(ROOM, conversing.getKey()));
      }
    }
    return rooms;
  }

  /**
   * The ids of {@code link}'s child that belong to one of {@code owners}: the live rows naming it,
   * then the entities whose latest entry is a tombstone whose last state named it — a deleted child
   * still belongs to its parent, and its history must not outlive the parent's erasure.
   */
  private Set<String> heldBy(
      Link link, List<String> owners, Map<Link, Map<String, List<String>>> tombstoned) {
    var ids = new LinkedHashSet<String>();
    for (var from = 0; from < owners.size(); from += BATCH) {
      var batch = owners.subList(from, Math.min(owners.size(), from + BATCH));
      ids.addAll(
          db.query(
              "SELECT id FROM "
                  + link.table()
                  + " WHERE "
                  + link.column()
                  + " IN ("
                  + String.join(", ", batch.stream().map(owner -> "?").toList())
                  + ") ORDER BY rowid",
              row -> row.text(0),
              batch.toArray()));
    }
    var byOwner =
        tombstoned.computeIfAbsent(link, key -> changeLog.tombstonedBy(key.child(), key.column()));
    for (var owner : owners) {
      ids.addAll(byOwner.getOrDefault(owner, List.of()));
    }
    return ids;
  }

  private static List<String> idsOf(Collection<Target> targets, String type) {
    return targets.stream().filter(target -> target.type().equals(type)).map(Target::id).toList();
  }

  /** Removes one entity's live row and every box-local row keyed by it; returns events dropped. */
  private int remove(Target target) {
    stores.get(target.type()).eraseRow(target.id());
    conflicts.erase(target.type(), target.id());
    requests.drop(target.type(), target.id());
    return switch (target.type()) {
      case SPEC -> {
        slackThreads.eraseBySpec(target.id());
        yield events.eraseBySpec(target.id());
      }
      case ROOM -> events.eraseBySpec(target.id());
      case PROJECT -> {
        runs.eraseLeases(target.id());
        yield events.eraseByProject(target.id());
      }
      default -> 0;
    };
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
