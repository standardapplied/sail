/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
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
 * <p>This store only appends and reads; it never mutates an entry. Mutators call {@link #append}
 * inside their own transaction so the journal can never diverge from the row it describes.
 */
public final class ChangeLog {

  private final Sqlite db;

  public ChangeLog(Sqlite db) {
    this.db = db;
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
      String peer) {}

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

  /**
   * Appends a revision and moves the entity's head to it, as one transaction. {@code snapshot} is
   * the entity's full state as JSON at this revision.
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
          db.execute(
              "INSERT INTO change_log (entity_type, entity_id, rev, actor, recorded_at, origin,"
                  + " deleted, snapshot, peer) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
              entityType,
              entityId,
              rev,
              actor,
              DateTimeUtils.now().toString(),
              origin,
              deleted ? 1 : 0,
              snapshot,
              SyncPeer.current());
          db.execute(
              "INSERT INTO change_heads (entity_type, entity_id, seq) VALUES (?, ?,"
                  + " last_insert_rowid()) ON CONFLICT(entity_type, entity_id) DO UPDATE SET seq ="
                  + " excluded.seq",
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
        "SELECT seq, entity_id FROM change_heads WHERE entity_type = ? AND seq > ? ORDER BY seq"
            + " LIMIT ?",
        row -> new Head(row.integer(0), row.text(1)),
        entityType,
        since,
        limit);
  }

  /**
   * Entities of {@code entityType} whose latest entry is a deletion this box decided itself — a
   * tombstone not adopted from main — and so still has to reach main.
   */
  public Set<String> localTombstones(String entityType) {
    return db
        .query(
            SELECT_HEAD + " WHERE h.entity_type = ? AND l.deleted = 1 AND l.origin <> 'sync'",
            row -> row.text(2),
            entityType)
        .stream()
        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
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
      "seq, entity_type, entity_id, rev, actor, recorded_at, origin, deleted, snapshot, peer";
  private static final String SELECT = "SELECT " + COLUMNS + " FROM change_log";
  private static final String SELECT_HEAD =
      "SELECT l.seq, l.entity_type, l.entity_id, l.rev, l.actor, l.recorded_at, l.origin,"
          + " l.deleted, l.snapshot, l.peer FROM change_heads h JOIN change_log l ON l.seq = h.seq";

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
        row.text(9));
  }
}
