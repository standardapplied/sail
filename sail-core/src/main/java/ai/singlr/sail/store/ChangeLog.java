/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Append-only revision journal for synced entities — the durability spine of the DB-sync design.
 * Every mutation to a synced entity writes the row <em>and</em> appends its full post-state
 * snapshot here in one transaction, so a prior version is always recoverable and no work is ever
 * lost. Entries are ordered by the monotonic {@code seq}; the latest entry for an entity is its
 * current revision (or its tombstone, when {@code deleted}).
 *
 * <p>Beside the log sits {@code change_heads}: one row per entity naming the seq of its latest
 * entry, upserted in the same transaction as every append. It is what keeps every read the sync
 * protocol makes O(what it asks for) rather than O(history): a page of changes since a checkpoint
 * is an index range over heads, an entity's latest revision is one head row joined to its entry,
 * and the high-water of a type is the largest head — never a scan of the log.
 *
 * <p>Every entry is one {@link Kind}: a revision, a tombstone (a deletion that keeps the entity's
 * last state, so it can be restored), or an erasure — the entity is gone everywhere, and the
 * erasure row, a few hundred bytes naming who and when, is the only thing left of it. An erasure is
 * terminal: {@link #erase} removes every other entry of the entity in the same transaction, no
 * compaction ever removes an erasure row, and {@link #append} refuses anything after one — an
 * erased id is never created again, so its erasure stays its head, the last entry every node pages
 * for it however long it was offline.
 *
 * <p>Otherwise this store only appends and reads. Mutators call {@link #append} inside their own
 * transaction so the journal can never diverge from the row it describes. History is bounded by
 * {@link #compact}, a local rule every box applies identically: the newest {@link
 * #HISTORY_REVISIONS} entries of an entity, its synced base, and every tombstone and erasure.
 */
public final class ChangeLog {

  /** The newest entries every box keeps of each entity's history; a constant, never configured. */
  public static final int HISTORY_REVISIONS = 20;

  private static final String EMPTY_SNAPSHOT = "{}";

  private final Sqlite db;

  public ChangeLog(Sqlite db) {
    this.db = db;
  }

  /** What one entry records about its entity. */
  public enum Kind {
    REVISION,
    TOMBSTONE,
    ERASURE;

    /** The value stored in {@code change_log.kind} and carried on the wire. */
    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }

    /** The kind named by {@code value}; anything else is refused. */
    public static Kind of(String value) {
      for (var kind : values()) {
        if (kind.wire().equals(value)) {
          return kind;
        }
      }
      throw new IllegalArgumentException("Unknown change kind: " + value);
    }
  }

  public record Entry(
      long seq,
      String entityType,
      String entityId,
      String rev,
      String actor,
      String recordedAt,
      String origin,
      boolean deleted,
      String snapshot,
      String peer,
      Kind kind) {}

  /** One entity's latest change: the seq of its head entry and its id. */
  public record Head(long seq, String entityId) {}

  /**
   * Runs {@code work} in one transaction on the journal's database, excluding every concurrent
   * writer for the whole scope. The atomicity primitive behind {@code capture}/{@code
   * adoptIfCurrent} in the sync replicas, which share this database with their stores.
   */
  public <T> T transaction(Supplier<T> work) {
    return db.transaction(work);
  }

  /** Runs {@code work} as one read snapshot of the journal's database; see {@link Sqlite#read}. */
  public <T> T read(Supplier<T> work) {
    return db.read(work);
  }

  /**
   * A write this journal refuses because it touches what was pruned: an erased id, which is spent
   * for good, or a state that would belong to an erased entity, which never comes back. Thrown
   * inside the writer's transaction, so nothing of the write lands.
   */
  public static final class Pruned extends IllegalArgumentException {
    Pruned(String message) {
      super(message);
    }
  }

  /**
   * Appends a revision and moves the entity's head to it, as one transaction. {@code snapshot} is
   * the entity's full state as JSON at this revision. Refused ({@link Pruned}) for an erased id,
   * and for a revision whose state names an erased owner through a link {@link Erasure} declares:
   * every writer — a local create, an import, main's commit of a push — meets the same rule here.
   */
  public void append(
      String entityType,
      String entityId,
      String rev,
      String actor,
      String origin,
      boolean deleted,
      String snapshot) {
    db.transaction(
        () -> {
          if (isErased(entityType, entityId)) {
            throw new Pruned(
                entityType
                    + " '"
                    + entityId
                    + "' was pruned, and a pruned id is never used again; choose a new one.");
          }
          if (!deleted) {
            requireNoErasedOwner(entityType, entityId, snapshot);
          }
          insert(
              entityType,
              entityId,
              rev,
              actor,
              origin,
              deleted,
              snapshot,
              deleted ? Kind.TOMBSTONE : Kind.REVISION);
        });
  }

  /**
   * Erases an entity's history and records the erasure in its place, as one transaction: every
   * entry of the entity except earlier erasure rows is deleted, one erasure row is appended with
   * the empty snapshot and {@code rev}, and the head moves to it. The caller removes the live row
   * in the same transaction.
   */
  public void erase(String entityType, String entityId, String rev, String actor, String origin) {
    db.transaction(
        () -> {
          purge(entityType, entityId);
          insert(entityType, entityId, rev, actor, origin, true, EMPTY_SNAPSHOT, Kind.ERASURE);
        });
  }

  /**
   * Deletes every entry of an entity except its erasure rows, and points its head at the newest
   * erasure left — or drops the head when none is. How an entity that belonged to an erased one
   * leaves this box's history when its own erasure has not been recorded here.
   */
  public void purge(String entityType, String entityId) {
    db.transaction(
        () -> {
          db.execute(
              "DELETE FROM change_log WHERE entity_type = ? AND entity_id = ? AND kind <> 'erasure'",
              entityType,
              entityId);
          db.execute(
              "DELETE FROM change_heads WHERE entity_type = ? AND entity_id = ?",
              entityType,
              entityId);
          db.execute(
              """
              INSERT INTO change_heads (entity_type, entity_id, seq)
              SELECT entity_type, entity_id, MAX(seq) FROM change_log
              WHERE entity_type = ? AND entity_id = ? AND kind = 'erasure'
              GROUP BY entity_type, entity_id""",
              entityType,
              entityId);
        });
  }

  /** The erasure that is an entity's latest entry here, if it was erased. */
  public Optional<Entry> erasure(String entityType, String entityId) {
    return head(entityType, entityId).filter(head -> head.kind() == Kind.ERASURE);
  }

  /** Whether the latest entry of an entity here is its erasure. */
  public boolean isErased(String entityType, String entityId) {
    return erasure(entityType, entityId).isPresent();
  }

  private void requireNoErasedOwner(String entityType, String entityId, String snapshot) {
    for (var owner : Erasure.ownersOf(entityType)) {
      var erased =
          db.queryOne(
              """
              SELECT h.entity_id FROM change_heads h JOIN change_log l ON l.seq = h.seq
              WHERE h.entity_type = ? AND h.entity_id = json_extract(?, ?)
              AND l.kind = 'erasure'""",
              row -> row.text(0),
              owner.type(),
              snapshot,
              "$." + owner.column());
      if (erased.isPresent()) {
        throw new Pruned(
            entityType
                + " '"
                + entityId
                + "' belongs to "
                + owner.type()
                + " '"
                + erased.get()
                + "', which was pruned");
      }
    }
  }

  /**
   * The entities of {@code entityType} whose latest entry is a tombstone, grouped by the id their
   * last state names in {@code field} (blank when it names none). Read through the tombstone index,
   * so it costs what the deletions of the type cost, however many live entities it has.
   */
  public Map<String, List<String>> tombstonedBy(String entityType, String field) {
    var byOwner = new HashMap<String, List<String>>();
    for (var tombstone :
        db.query(
            """
            SELECT l.entity_id, json_extract(l.snapshot, ?) FROM change_log l
            JOIN change_heads h ON h.seq = l.seq
            WHERE l.entity_type = ? AND l.kind = 'tombstone'""",
            row -> Map.entry(row.text(0), Objects.toString(row.text(1), "")),
            "$." + field,
            entityType)) {
      byOwner
          .computeIfAbsent(tombstone.getValue(), owner -> new ArrayList<>())
          .add(tombstone.getKey());
    }
    return byOwner;
  }

  /** Whether this box holds the erasure of an entity recorded at {@code rev}. */
  public boolean hasErasure(String entityType, String entityId, String rev) {
    return db.queryOne(
            "SELECT 1 FROM change_log WHERE entity_type = ? AND entity_id = ? AND kind = 'erasure'"
                + " AND rev = ?",
            row -> true,
            entityType,
            entityId,
            rev)
        .orElse(false);
  }

  /**
   * Drops the entries of each of {@code ids} that fall outside what history keeps — the newest
   * {@link #HISTORY_REVISIONS} of the entity, the revision {@code baseRevOf} names as its synced
   * base, and every tombstone and erasure — one entity per transaction. Returns how many entries
   * were dropped. A head is always among the newest, so no head ever moves.
   */
  public long compact(String entityType, Collection<String> ids, UnaryOperator<String> baseRevOf) {
    var dropped = 0L;
    for (var id : ids) {
      dropped +=
          db.transaction(
              () -> {
                db.execute(
                    """
                    DELETE FROM change_log WHERE entity_type = ? AND entity_id = ?
                    AND kind = 'revision' AND rev IS NOT ?
                    AND seq < (SELECT MIN(seq) FROM (SELECT seq FROM change_log
                        WHERE entity_type = ? AND entity_id = ? ORDER BY seq DESC LIMIT ?))""",
                    entityType,
                    id,
                    baseRevOf.apply(id),
                    entityType,
                    id,
                    HISTORY_REVISIONS);
                return (long) db.changes();
              });
    }
    return dropped;
  }

  /**
   * The ids among {@code ids} whose history holds more than {@link #HISTORY_REVISIONS} entries —
   * what {@link #compact} could drop something from. Every id of the type when {@code ids} is null.
   */
  public Set<String> overflowing(String entityType, Collection<String> ids) {
    if (ids == null) {
      return new LinkedHashSet<>(
          db.query(
              """
              SELECT entity_id FROM change_log WHERE entity_type = ?
              GROUP BY entity_id HAVING COUNT(*) > ?""",
              row -> row.text(0),
              entityType,
              HISTORY_REVISIONS));
    }
    var overflowing = new LinkedHashSet<String>();
    for (var id : ids) {
      var count =
          db.queryOne(
                  "SELECT COUNT(*) FROM change_log WHERE entity_type = ? AND entity_id = ?",
                  row -> row.integer(0),
                  entityType,
                  id)
              .orElse(0L);
      if (count > HISTORY_REVISIONS) {
        overflowing.add(id);
      }
    }
    return overflowing;
  }

  private void insert(
      String entityType,
      String entityId,
      String rev,
      String actor,
      String origin,
      boolean deleted,
      String snapshot,
      Kind kind) {
    db.transaction(
        () -> {
          db.execute(
              "INSERT INTO change_log (entity_type, entity_id, rev, actor, recorded_at, origin,"
                  + " deleted, snapshot, peer, kind) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
              entityType,
              entityId,
              rev,
              actor,
              DateTimeUtils.now().toString(),
              origin,
              deleted ? 1 : 0,
              snapshot,
              SyncPeer.current(),
              kind.wire());
          db.execute(
              """
              INSERT INTO change_heads (entity_type, entity_id, seq)
              VALUES (?, ?, last_insert_rowid())
              ON CONFLICT(entity_type, entity_id) DO UPDATE SET seq = excluded.seq""",
              entityType,
              entityId);
        });
  }

  /** Returns every revision of an entity in chronological order (oldest first). */
  public List<Entry> history(String entityType, String entityId) {
    return db.query(
        SELECT + " WHERE entity_type = ? AND entity_id = ? ORDER BY seq",
        ChangeLog::map,
        entityType,
        entityId);
  }

  /** The latest entry of an entity — its current revision or tombstone — read through its head. */
  public Optional<Entry> head(String entityType, String entityId) {
    return db.queryOne(
        SELECT_HEAD + " WHERE h.entity_type = ? AND h.entity_id = ?",
        ChangeLog::map,
        entityType,
        entityId);
  }

  /**
   * The entities of {@code entityType} whose latest change sits after {@code since}, ascending by
   * that change's seq and at most {@code limit} of them: one page of the change log as a sync pull
   * reads it. An index range over heads, so a page costs the same on a seed as on an idle round.
   */
  public List<Head> headsAfter(String entityType, long since, int limit) {
    return db.query(
        """
        SELECT seq, entity_id FROM change_heads
        WHERE entity_type = ? AND seq > ?
        ORDER BY seq LIMIT ?""",
        row -> new Head(row.integer(0), row.text(1)),
        entityType,
        since,
        limit);
  }

  /**
   * Entities of {@code entityType} whose latest entry is a deletion this box decided itself — a
   * tombstone not adopted from main — and so still has to reach main, in the order they were
   * decided.
   */
  public Set<String> localTombstones(String entityType) {
    return db
        .query(
            SELECT_HEAD
                + " WHERE h.entity_type = ? AND l.kind = 'tombstone' AND l.origin <> 'sync'"
                + " ORDER BY l.seq",
            row -> row.text(2),
            entityType)
        .stream()
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /** The highest sequence recorded for an entity type; 0 if none. The sync high-water mark. */
  public long maxSeq(String entityType) {
    return db.queryOne(
            "SELECT COALESCE(MAX(seq), 0) FROM change_heads WHERE entity_type = ?",
            row -> row.integer(0),
            entityType)
        .orElse(0L);
  }

  /** Returns a specific revision of an entity, if it was ever recorded. */
  public Optional<Entry> at(String entityType, String entityId, String rev) {
    return db.queryOne(
        SELECT + " WHERE entity_type = ? AND entity_id = ? AND rev = ?",
        ChangeLog::map,
        entityType,
        entityId,
        rev);
  }

  private static final String COLUMNS =
      "seq, entity_type, entity_id, rev, actor, recorded_at, origin, deleted, snapshot, peer, kind";
  private static final String SELECT = "SELECT " + COLUMNS + " FROM change_log";
  private static final String SELECT_HEAD =
      "SELECT l.seq, l.entity_type, l.entity_id, l.rev, l.actor, l.recorded_at, l.origin,"
          + " l.deleted, l.snapshot, l.peer, l.kind"
          + " FROM change_heads h JOIN change_log l ON l.seq = h.seq";

  private static Entry map(Sqlite.Row row) {
    return new Entry(
        row.integer(0),
        row.text(1),
        row.text(2),
        row.text(3),
        row.text(4),
        row.text(5),
        row.text(6),
        row.integer(7) != 0,
        row.text(8),
        row.text(9),
        Kind.of(row.text(10)));
  }
}
