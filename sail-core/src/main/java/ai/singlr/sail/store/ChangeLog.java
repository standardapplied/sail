/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import java.util.List;
import java.util.Optional;

/**
 * Append-only revision journal for synced entities — the durability spine of the DB-sync design.
 * Every mutation to a synced entity writes the row <em>and</em> appends its full post-state
 * snapshot here in one transaction, so a prior version is always recoverable and no work is ever
 * lost. Entries are ordered by the monotonic {@code seq}; the latest entry for an entity is its
 * current revision (or its tombstone, when {@code deleted}).
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
      String snapshot) {}

  /** Appends a revision. {@code snapshot} is the entity's full state as JSON at this revision. */
  public void append(
      String entityType,
      String entityId,
      String rev,
      String actor,
      String origin,
      boolean deleted,
      String snapshot) {
    db.execute(
        "INSERT INTO change_log (entity_type, entity_id, rev, actor, recorded_at, origin,"
            + " deleted, snapshot) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        entityType,
        entityId,
        rev,
        actor,
        DateTimeUtils.now().toString(),
        origin,
        deleted ? 1 : 0,
        snapshot);
  }

  /** Returns every revision of an entity in chronological order (oldest first). */
  public List<Entry> history(String entityType, String entityId) {
    return db.query(
        SELECT + " WHERE entity_type = ? AND entity_id = ? ORDER BY seq",
        ChangeLog::map,
        entityType,
        entityId);
  }

  /** The highest sequence recorded for an entity type; 0 if none. The sync high-water mark. */
  public long maxSeq(String entityType) {
    return db.queryOne(
            "SELECT COALESCE(MAX(seq), 0) FROM change_log WHERE entity_type = ?",
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

  private static final String SELECT =
      "SELECT seq, entity_type, entity_id, rev, actor, recorded_at, origin, deleted, snapshot"
          + " FROM change_log";

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
        row.text(8));
  }
}
