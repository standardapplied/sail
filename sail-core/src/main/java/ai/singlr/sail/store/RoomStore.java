/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Rooms on SQLite: the durable collaboration surface an FDE and their agents converse in. A room
 * carries the conversation-side state that previously lived on the spec row — the agent roster, the
 * wake policy, the waker-box assignee — while a spec remains the work-item. In this brick the table
 * exists and replicates but nothing reads it yet; the conversation write paths move here in the
 * following bricks.
 *
 * <p>{@code roster} is one compact JSON array of agent members ({@code [{agent, mode, model,
 * engaged_at}, …]}) so sync merges the membership atomically — many members by schema even while
 * the UI adds one. Each mutation journals the room's full post-state through the shared {@link
 * RevisionJournal} under entity type {@code room}, so rooms get the same revision/CAS/conflict
 * machinery — and org-wide replication — as specs.
 */
public final class RoomStore implements ConflictResolver, SyncedStore {

  private static final String ENTITY = "room";

  private static final Set<String> SYNC_FIELDS =
      Set.of("project", "title", "assignee", "wake", "roster", "created_by", "created_at");

  private final Sqlite db;
  private final ChangeLog changeLog;
  private final RevisionJournal journal;

  public RoomStore(Sqlite db) {
    this.db = db;
    this.changeLog = new ChangeLog(db);
    this.journal = new RevisionJournal(db, changeLog, new RoomSchema());
  }

  public record RoomRow(
      String id,
      String project,
      String title,
      String assignee,
      String wake,
      String roster,
      String createdBy,
      String createdAt,
      String updatedAt,
      String updatedBy) {}

  /** Creates a room as a local edit, stamping creation and update times. */
  public void create(RoomRow room) {
    Strings.requireNonBlank(room.id(), "A room needs an id");
    Strings.requireNonBlank(room.title(), "A room needs a title");
    var now = DateTimeUtils.now().toString();
    db.transaction(
        () -> {
          db.execute(
              """
              INSERT INTO rooms (id, project, title, assignee, wake, roster, created_by,
                  created_at, updated_at, updated_by)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
              room.id(),
              room.project(),
              room.title(),
              room.assignee(),
              room.wake(),
              room.roster(),
              room.createdBy(),
              now,
              now,
              room.updatedBy());
          journal.recordRevision(room.id(), "local", false);
        });
  }

  /**
   * Seats a new roster as a local edit — a single-column write, so a concurrent wake edit is never
   * clobbered by a stale full-row rewrite.
   */
  public void updateRoster(String id, String roster, String actor) {
    db.transaction(
        () -> {
          db.execute(
              "UPDATE rooms SET roster = ?, updated_at = ?, updated_by = ? WHERE id = ?",
              roster,
              DateTimeUtils.now().toString(),
              actor,
              id);
          journal.recordRevision(id, "local", false);
        });
  }

  /** Stores a new wake mode as a local edit — single-column, mirror of {@link #updateRoster}. */
  public void updateWake(String id, String wake, String actor) {
    db.transaction(
        () -> {
          db.execute(
              "UPDATE rooms SET wake = ?, updated_at = ?, updated_by = ? WHERE id = ?",
              wake,
              DateTimeUtils.now().toString(),
              actor,
              id);
          journal.recordRevision(id, "local", false);
        });
  }

  /** Tombstones a room so the deletion propagates; a no-op if it is already absent. */
  public boolean delete(String id) {
    return db.transaction(
        () -> {
          if (findById(id).isEmpty()) {
            return false;
          }
          journal.recordRevision(id, "local", true);
          db.execute("DELETE FROM rooms WHERE id = ?", id);
          return true;
        });
  }

  /**
   * Writes a row verbatim — timestamps included — and journals it as a LOCAL revision with no
   * synced ancestor. The backfill's write: every field derives from the synced spec row, so each
   * box mints a byte-identical revision, and a room main never minted pushes up on first sync
   * instead of reading as a remote deletion (which a synced-ancestor write would).
   */
  public void createJournaled(RoomRow room) {
    Strings.requireNonBlank(room.id(), "A room needs an id");
    Strings.requireNonBlank(room.title(), "A room needs a title");
    Strings.requireNonBlank(room.createdAt(), "A journaled create carries its creation time");
    Strings.requireNonBlank(room.updatedAt(), "A journaled create carries its update time");
    db.transaction(
        () -> {
          db.execute(
              """
              INSERT INTO rooms (id, project, title, assignee, wake, roster, created_by,
                  created_at, updated_at, updated_by)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
              room.id(),
              room.project(),
              room.title(),
              room.assignee(),
              room.wake(),
              room.roster(),
              room.createdBy(),
              room.createdAt(),
              room.updatedAt(),
              room.updatedBy());
          journal.recordRevision(room.id(), "local", false);
        });
  }

  /**
   * The room for {@code id}, created on demand from the identity fields when absent — the seam that
   * keeps membership writes safe for a spec whose room predates or postdates the backfill.
   */
  public RoomRow ensureFor(
      String id, String project, String title, String assignee, String wake, String actor) {
    return db.immediateTransaction(
        () ->
            findById(id)
                .orElseGet(
                    () -> {
                      create(
                          new RoomRow(
                              id, project, title, assignee, wake, null, actor, null, null, actor));
                      return findById(id).orElseThrow();
                    }));
  }

  /**
   * Whether the journal's last word on {@code id} is a deletion — a room that once existed here and
   * was removed, so an idempotent minter must not resurrect it.
   */
  public boolean isTombstoned(String id) {
    var history = changeLog.history(ENTITY, id);
    return !history.isEmpty() && history.getLast().deleted();
  }

  /**
   * Composes a check-then-create across the stores sharing this database into one write-locked
   * transaction (see {@link Sqlite#immediateTransaction}): the spec-id collision check a room
   * create runs and the insert it guards cannot be split by a spec being born on the same id.
   */
  public <T> T atomically(Supplier<T> work) {
    return db.immediateTransaction(work);
  }

  /** Every room with at least one member — the rooms the engagement sweeper walks. */
  public List<RoomRow> listEngaged() {
    return db.query(
        """
        SELECT id, project, title, assignee, wake, roster, created_by, created_at,
            updated_at, updated_by
        FROM rooms WHERE roster IS NOT NULL""",
        RoomStore::mapRoom);
  }

  public Optional<RoomRow> findById(String id) {
    return db.queryOne(
        """
        SELECT id, project, title, assignee, wake, roster, created_by, created_at,
            updated_at, updated_by
        FROM rooms WHERE id = ?""",
        RoomStore::mapRoom,
        id);
  }

  /** Every current room across all projects, in creation order — the rooms front door. */
  public List<RoomRow> listAll() {
    return db.query(
        """
        SELECT id, project, title, assignee, wake, roster, created_by, created_at,
            updated_at, updated_by
        FROM rooms ORDER BY created_at, id""",
        RoomStore::mapRoom);
  }

  /** Every current room of a project, newest activity last by creation order. */
  public List<RoomRow> list(String project) {
    return db.query(
        """
        SELECT id, project, title, assignee, wake, roster, created_by, created_at,
            updated_at, updated_by
        FROM rooms WHERE project = ? ORDER BY created_at, id""",
        RoomStore::mapRoom,
        project);
  }

  @Override
  public String entityType() {
    return ENTITY;
  }

  @Override
  public Map<String, Object> comparableSnapshot(String id) {
    return journal.comparableSnapshot(id);
  }

  @Override
  public Map<String, Object> comparableAtRev(String id, String rev) {
    return journal.comparableAtRev(id, rev);
  }

  @Override
  public String latestRev(String id) {
    return journal.latestRev(id);
  }

  @Override
  public String baseRevOf(String id) {
    return journal.baseRevOf(id);
  }

  @Override
  public Set<String> syncEntityIds() {
    return new LinkedHashSet<>(journal.entityIds());
  }

  /**
   * Adopts main's authoritative state at its exact rev (no minting), as the new synced ancestor.
   */
  @Override
  public void applyRevision(String id, Map<String, Object> snapshot, String rev) {
    journal.applyRevision(id, snapshot, rev);
  }

  /** Compare-and-set commit as main: accepts only if {@code expectedRev} still matches. */
  @Override
  public PushOutcome commitRevision(String id, Map<String, Object> snapshot, String expectedRev) {
    return journal.commitRevision(id, snapshot, expectedRev);
  }

  /**
   * Resolves an open room conflict locally: rebases onto main's conflicting content as the new
   * merge base, then writes {@code chosen} as the resolved state. Every state stays in the {@link
   * ChangeLog}, so no choice loses work.
   */
  @Override
  public String resolveConflict(String id, Map<String, Object> chosen, Map<String, Object> remote) {
    return journal.resolveConflict(id, chosen, remote);
  }

  private static RoomRow mapRoom(Sqlite.Row row) {
    return new RoomRow(
        row.text(0),
        row.text(1),
        row.text(2),
        row.text(3),
        row.text(4),
        row.text(5),
        row.text(6),
        row.text(7),
        row.text(8),
        row.text(9));
  }

  private Map<String, Object> snapshotMap(RoomRow room) {
    var map = new LinkedHashMap<String, Object>();
    map.put("id", room.id());
    map.put("project", room.project());
    map.put("title", room.title());
    map.put("assignee", room.assignee());
    map.put("wake", room.wake());
    map.put("roster", room.roster());
    map.put("created_by", room.createdBy());
    map.put("created_at", room.createdAt());
    map.put("updated_by", room.updatedBy());
    map.put("updated_at", room.updatedAt());
    return map;
  }

  /** The room's store-specific half of the shared {@link RevisionJournal} sync protocol. */
  private final class RoomSchema implements EntitySchema {

    @Override
    public String entityType() {
      return ENTITY;
    }

    @Override
    public String table() {
      return "rooms";
    }

    @Override
    public boolean exists(String id) {
      return findById(id).isPresent();
    }

    @Override
    public Map<String, Object> snapshotMap(String id) {
      return findById(id).map(RoomStore.this::snapshotMap).orElse(null);
    }

    @Override
    public String author(String id) {
      return findById(id).map(RoomRow::updatedBy).orElse(null);
    }

    @Override
    public void apply(String id, Map<String, Object> snapshot) {
      var now = DateTimeUtils.now().toString();
      var createdAt = Snapshots.text(snapshot, "created_at");
      db.execute(
          """
          INSERT INTO rooms (id, project, title, assignee, wake, roster, created_by,
              created_at, updated_at, updated_by)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT(id) DO UPDATE SET project = excluded.project, title = excluded.title,
              assignee = excluded.assignee, wake = excluded.wake, roster = excluded.roster,
              updated_at = excluded.updated_at, updated_by = excluded.updated_by""",
          id,
          Snapshots.text(snapshot, "project"),
          Snapshots.text(snapshot, "title"),
          Snapshots.text(snapshot, "assignee"),
          Snapshots.text(snapshot, "wake"),
          Snapshots.text(snapshot, "roster"),
          Snapshots.text(snapshot, "created_by"),
          Strings.isBlank(createdAt) ? now : createdAt,
          now,
          Snapshots.actor(snapshot));
    }

    @Override
    public Map<String, Object> comparable(Map<String, Object> full) {
      if (full == null) {
        return null;
      }
      var m = new LinkedHashMap<String, Object>();
      for (var field : full.keySet()) {
        if (SYNC_FIELDS.contains(field)) {
          m.put(field, full.get(field));
        }
      }
      var author = full.get("updated_by");
      if (author != null) {
        m.put(Snapshots.ACTOR, author);
      }
      return m;
    }

    @Override
    public void deleteRow(String id) {
      db.execute("DELETE FROM rooms WHERE id = ?", id);
    }
  }
}
