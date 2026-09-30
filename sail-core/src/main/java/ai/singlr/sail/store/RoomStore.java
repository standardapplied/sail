/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.authority.RoomAuthority;
import ai.singlr.sail.authority.WriteAuthority;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Ownership;
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

  /**
   * Creates a room as a local edit, stamping creation and update times, created by the FDE the
   * bound {@link Actor} acts as ({@link Actor#actingFde}) and last updated by the actor, whatever
   * the row names.
   */
  public void create(RoomRow room) {
    Strings.requireNonBlank(room.id(), "A room needs an id");
    Strings.requireNonBlank(room.title(), "A room needs a title");
    var now = DateTimeUtils.now().toString();
    db.transaction(
        () -> {
          var author = author();
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
              Actor.current().actingFde(),
              now,
              now,
              author);
          journal.recordRevision(room.id(), ChangeLog.Entry.LOCAL, false);
        });
  }

  /**
   * Seats a new roster as a local edit — a single-column write, so a concurrent wake edit is never
   * clobbered by a stale full-row rewrite.
   */
  public void updateRoster(String id, String roster) {
    db.transaction(
        () -> {
          db.execute(
              "UPDATE rooms SET roster = ?, updated_at = ?, updated_by = ? WHERE id = ?",
              roster,
              DateTimeUtils.now().toString(),
              author(),
              id);
          journal.recordRevision(id, ChangeLog.Entry.LOCAL, false);
        });
  }

  /** Stores a new wake mode as a local edit — single-column, mirror of {@link #updateRoster}. */
  public void updateWake(String id, String wake) {
    db.transaction(
        () -> {
          db.execute(
              "UPDATE rooms SET wake = ?, updated_at = ?, updated_by = ? WHERE id = ?",
              wake,
              DateTimeUtils.now().toString(),
              author(),
              id);
          journal.recordRevision(id, ChangeLog.Entry.LOCAL, false);
        });
  }

  /** Tombstones a room so the deletion propagates; a no-op if it is already absent. */
  public boolean delete(String id) {
    return db.transaction(
        () -> {
          if (findById(id).isEmpty()) {
            return false;
          }
          stampAuthor(id);
          journal.recordRevision(id, ChangeLog.Entry.LOCAL, true);
          db.execute("DELETE FROM rooms WHERE id = ?", id);
          return true;
        });
  }

  /**
   * Brings back a room deleted with the spec that minted it, from the last state its tombstone
   * kept, as a new revision — the room a restored spec converses in. A no-op unless the room's
   * latest entry is a tombstone.
   */
  public boolean restoreDeleted(String id) {
    return db.transaction(
        () -> {
          var head = changeLog.head(ENTITY, id).orElse(null);
          if (head == null || head.kind() != ChangeLog.Kind.TOMBSTONE) {
            return false;
          }
          var schema = new RoomSchema();
          schema.apply(id, schema.comparable(YamlUtil.parseMap(head.snapshot())));
          stampAuthor(id);
          journal.recordRevision(id, "restore", false);
          return true;
        });
  }

  /**
   * Writes a row verbatim — timestamps and creator included, last updated by the bound {@link
   * Actor} — and journals it as a LOCAL revision with no synced ancestor. The backfill's write:
   * every field derives from the synced spec row, so each box mints a byte-identical revision, and
   * a room main never minted pushes up on first sync instead of reading as a remote deletion (which
   * a synced-ancestor write would).
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
              author());
          journal.recordRevision(room.id(), ChangeLog.Entry.LOCAL, false);
        });
  }

  /**
   * The room for {@code id}, created on demand from the identity fields when absent — the seam that
   * keeps membership writes safe for a spec whose room predates or postdates the backfill.
   */
  public RoomRow ensureFor(String id, String project, String title, String assignee, String wake) {
    return db.transaction(
        () ->
            findById(id)
                .orElseGet(
                    () -> {
                      create(
                          new RoomRow(
                              id, project, title, assignee, wake, null, null, null, null, null));
                      return findById(id).orElseThrow();
                    }));
  }

  private void stampAuthor(String id) {
    db.execute("UPDATE rooms SET updated_by = ? WHERE id = ?", author(), id);
  }

  private static String author() {
    return Actor.current().handle();
  }

  /**
   * Whether the journal's last word on {@code id} is a deletion — a room that once existed here and
   * was removed, so an idempotent minter must not resurrect it.
   */
  public boolean isTombstoned(String id) {
    return changeLog.head(ENTITY, id).map(ChangeLog.Entry::deleted).orElse(false);
  }

  /**
   * Composes a check-then-create across the stores sharing this database into one write-locked
   * transaction (see {@link Sqlite#transaction}): the spec-id collision check a room create runs
   * and the insert it guards cannot be split by a spec being born on the same id.
   */
  public <T> T atomically(Supplier<T> work) {
    return db.transaction(work);
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

  /**
   * Who owns conversation {@code roomId}: the one rule every door that admits a post, a terminal
   * pin or a birth into a room decides by, and main's check of a synced post too. A room belongs to
   * the owner of each spec that lives in it (the spec's own room, or the room it was born in) and,
   * when it is not a spec's own room, to the owner its own row names. A spec's own room row is
   * never consulted: the spec decides, so a claim moves the room with it at every door at once.
   * Each owner is named once; empty when no one is known.
   */
  public List<String> owners(String roomId) {
    return db
        .query(
            """
            SELECT assignee, created_by FROM specs
            WHERE room_id = ?1 OR (coalesce(room_id, '') = '' AND id = ?1)
            UNION ALL
            SELECT assignee, created_by FROM rooms
            WHERE id = ?1 AND NOT EXISTS (SELECT 1 FROM specs WHERE id = ?1)""",
            row -> Ownership.ownerOf(row.text(0), row.text(1)),
            roomId)
        .stream()
        .filter(Strings::isNotBlank)
        .distinct()
        .toList();
  }

  /**
   * Who owns room {@code roomId} — its roster, wake, title and assignee — the one rule every door
   * that changes a room, every waker and main's check of a synced room decide by. A spec's identity
   * room is its spec's, as this box last knew the spec, live or deleted ({@link
   * SpecStore#lastKnown}); any other room is its own row's ({@link Ownership#ownerOf}). Posting is
   * wider ({@link #owners}): owning a spec born in a room gives a voice there, not its settings.
   * Blank when no one is known.
   */
  public String ownerOf(String roomId) {
    return ownerOf(roomId, comparableSnapshot(roomId));
  }

  /**
   * As {@link #ownerOf(String)}, reading a standalone room's owner from {@code held}, the room's
   * projection as this box holds it, so a revision being decided never names its own owner.
   */
  public String ownerOf(String roomId, Map<String, Object> held) {
    return new SpecStore(db)
        .lastKnown(roomId)
        .filter(spec -> spec.roomIdOrIdentity().equals(roomId))
        .map(SpecStore.LastKnown::owner)
        .orElseGet(
            () ->
                held == null
                    ? ""
                    : Ownership.ownerOf(
                        Snapshots.text(held, "assignee"), Snapshots.text(held, "created_by")));
  }

  /**
   * What this box holds of room {@code id}, as a rule reads it: its comparable snapshot, or the
   * last live state its tombstone kept. Null for a room this box never held.
   */
  public Map<String, Object> held(String id) {
    return journal.held(id);
  }

  /**
   * Whether this box holds conversation {@code roomId} — a room, a spec living in it, or either's
   * history — so a revision placed in it can be decided here. One this box has never held is
   * refused ({@link SyncedStore.Unheld}), never denied: its room syncs in its own page, and the
   * next round decides what lives in it.
   */
  public boolean holdsConversation(String roomId) {
    return db.queryOne(
            """
            SELECT 1 WHERE EXISTS (SELECT 1 FROM rooms WHERE id = ?1)
                OR EXISTS (SELECT 1 FROM specs WHERE room_id = ?1)
                OR EXISTS (SELECT 1 FROM change_heads
                    WHERE entity_type IN ('room', 'spec') AND entity_id = ?1)""",
            row -> true,
            roomId)
        .orElse(false);
  }

  /** Who may write a room on this box: the rule every door and main's commit decide by. */
  @Override
  public RoomAuthority authority() {
    return new RoomAuthority(db);
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

  @Override
  public Set<String> dirtyIds() {
    return journal.dirtyIds();
  }

  /**
   * Adopts main's authoritative state at its exact rev (no minting), as the new synced ancestor.
   */
  @Override
  public void applyRevision(String id, Map<String, Object> snapshot, String rev) {
    journal.applyRevision(id, snapshot, rev);
  }

  @Override
  public boolean acknowledge(String id, Map<String, Object> accepted, String rev) {
    return journal.acknowledge(id, accepted, rev);
  }

  @Override
  public Set<String> latestWinsFields() {
    return journal.latestWinsFields();
  }

  @Override
  public Map<String, Object> currentForSync(String id) {
    return journal.currentForSync(id);
  }

  @Override
  public void eraseRow(String id) {
    journal.eraseRow(id);
  }

  /** Compare-and-set commit as main: accepts only if {@code expectedRev} still matches. */
  @Override
  public PushOutcome commitRevision(
      String id, Map<String, Object> snapshot, String expectedRev, WriteAuthority authority) {
    return journal.commitRevision(id, snapshot, expectedRev, authority);
  }

  /** Resolves an open conflict through the shared {@link RevisionJournal#resolveConflict}. */
  @Override
  public String resolveConflict(String id, Map<String, Object> chosen, MainVersion theirs) {
    return journal.resolveConflict(id, chosen, theirs);
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

    /**
     * Writes every synced field of {@code snapshot}. The creator and creation time are written
     * once: an update keeps the row's, except that a node adopting main's revision takes main's.
     */
    @Override
    public void apply(String id, Map<String, Object> snapshot) {
      var now = DateTimeUtils.now().toString();
      var createdAt = Snapshots.text(snapshot, "created_at");
      var adopting = Actor.current().lane() == Actor.Lane.MAIN;
      db.execute(
          """
          INSERT INTO rooms (id, project, title, assignee, wake, roster, created_by,
              created_at, updated_at, updated_by)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT(id) DO UPDATE SET project = excluded.project, title = excluded.title,
              assignee = excluded.assignee, wake = excluded.wake, roster = excluded.roster,
              created_by = CASE WHEN ? = 1 THEN excluded.created_by ELSE rooms.created_by END,
              created_at = CASE WHEN ? = 1 THEN excluded.created_at ELSE rooms.created_at END,
              updated_at = excluded.updated_at, updated_by = excluded.updated_by""",
          id,
          Snapshots.text(snapshot, "project"),
          Snapshots.text(snapshot, "title"),
          Snapshots.text(snapshot, "assignee"),
          Snapshots.text(snapshot, "wake"),
          Snapshots.text(snapshot, "roster"),
          creatorOf(id, snapshot),
          Strings.isBlank(createdAt) ? now : createdAt,
          now,
          Snapshots.actor(snapshot),
          adopting ? 1 : 0,
          adopting && Strings.isNotBlank(createdAt) ? 1 : 0);
    }

    /**
     * The creator a write of {@code snapshot} records: a creator is written once, so a revision
     * re-creating a deleted room keeps the one its tombstone recorded, as an update keeps the
     * row's. Main's own revision names the creator main holds, which a node adopts over its own.
     */
    private String creatorOf(String id, Map<String, Object> snapshot) {
      var offered = Snapshots.text(snapshot, "created_by");
      if (Actor.current().lane() == Actor.Lane.MAIN || exists(id)) {
        return offered;
      }
      var tombstone = journal.held(id);
      return tombstone == null ? offered : Snapshots.text(tombstone, "created_by");
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
