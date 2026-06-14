/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import java.util.List;
import java.util.Optional;

/**
 * Pending sync conflicts awaiting a human decision. When {@link ConflictDetector} reports a true
 * {@code Conflict}, the engine records the three snapshots (base / local / remote) and the
 * conflicting field names here; the local row keeps its current value untouched, so the FDE's work
 * is never overwritten while the conflict is open. Resolving a conflict marks it {@code resolved}
 * with the revision the user chose, leaving an auditable trail.
 */
public final class SyncConflicts {

  private static final String PENDING = "pending";
  private static final String RESOLVED = "resolved";

  private final Sqlite db;

  public SyncConflicts(Sqlite db) {
    this.db = db;
  }

  public record Conflict(
      long id,
      String entityType,
      String entityId,
      String baseSnapshot,
      String localSnapshot,
      String remoteSnapshot,
      List<String> fields,
      String detectedAt,
      String status,
      String resolvedRev) {}

  /** Records a pending conflict and returns its id. One open conflict per entity at a time. */
  public long record(
      String entityType,
      String entityId,
      String baseSnapshot,
      String localSnapshot,
      String remoteSnapshot,
      List<String> fields) {
    return db.transaction(
        () -> {
          db.execute(
              "DELETE FROM sync_conflicts WHERE entity_type = ? AND entity_id = ? AND status = ?",
              entityType,
              entityId,
              PENDING);
          db.execute(
              "INSERT INTO sync_conflicts (entity_type, entity_id, base_snapshot, local_snapshot,"
                  + " remote_snapshot, fields, detected_at, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
              entityType,
              entityId,
              baseSnapshot,
              localSnapshot,
              remoteSnapshot,
              String.join("\n", fields),
              DateTimeUtils.now().toString(),
              PENDING);
          return db.queryOne("SELECT last_insert_rowid()", row -> row.integer(0)).orElseThrow();
        });
  }

  /** Every open conflict, oldest first. */
  public List<Conflict> pending() {
    return db.query(SELECT + " WHERE status = ? ORDER BY id", SyncConflicts::map, PENDING);
  }

  public Optional<Conflict> pendingFor(String entityType, String entityId) {
    return db.queryOne(
        SELECT + " WHERE status = ? AND entity_type = ? AND entity_id = ?",
        SyncConflicts::map,
        PENDING,
        entityType,
        entityId);
  }

  /** Marks a conflict resolved with the revision the user settled on. */
  public boolean resolve(long id, String resolvedRev) {
    db.execute(
        "UPDATE sync_conflicts SET status = ?, resolved_rev = ? WHERE id = ? AND status = ?",
        RESOLVED,
        resolvedRev,
        id,
        PENDING);
    return db.changes() > 0;
  }

  private static final String SELECT =
      "SELECT id, entity_type, entity_id, base_snapshot, local_snapshot, remote_snapshot, fields,"
          + " detected_at, status, resolved_rev FROM sync_conflicts";

  private static Conflict map(Sqlite.Row row) {
    var fields = row.text(6);
    return new Conflict(
        row.integer(0),
        row.text(1),
        row.text(2),
        row.text(3),
        row.text(4),
        row.text(5),
        fields.isEmpty() ? List.of() : List.of(fields.split("\n")),
        row.text(7),
        row.text(8),
        row.text(9));
  }
}
