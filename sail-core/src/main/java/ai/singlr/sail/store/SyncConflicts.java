/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import java.util.List;
import java.util.Optional;

/**
 * Pending sync conflicts awaiting a human decision. When {@link ConflictDetector} reports a true
 * {@code Conflict}, the engine records the three snapshots (base / local / remote) and the
 * conflicting field names here; the local row keeps its current value untouched, so the FDE's work
 * is never overwritten while the conflict is open. Main's side is kept with the rev main holds it
 * at and the author main recorded, so a resolve adopts main's revision exactly. Resolving a
 * conflict marks it {@code resolved} with the revision the user chose, leaving an auditable trail.
 */
public final class SyncConflicts {

  private static final String PENDING = "pending";
  private static final String RESOLVED = "resolved";

  private final Sqlite db;

  public SyncConflicts(Sqlite db) {
    this.db = db;
  }

  /**
   * One parked conflict. {@code remoteRev} and {@code remoteAuthor} name main's side as main minted
   * it; both are null on a conflict parked before a conflict kept them, until a round re-records
   * it.
   */
  public record Conflict(
      long id,
      String entityType,
      String entityId,
      String baseSnapshot,
      String localSnapshot,
      String remoteSnapshot,
      String remoteRev,
      String remoteAuthor,
      List<String> fields,
      String detectedAt,
      String status,
      String resolvedRev) {

    /** Main's side of this conflict, as it was recorded. */
    public MainVersion theirs() {
      return new MainVersion(
          Strings.isBlank(remoteSnapshot) ? null : YamlUtil.parseMap(remoteSnapshot),
          remoteRev,
          remoteAuthor);
    }

    /** This conflict as settled at {@code rev}. */
    public Conflict resolvedAt(String rev) {
      return new Conflict(
          id,
          entityType,
          entityId,
          baseSnapshot,
          localSnapshot,
          remoteSnapshot,
          remoteRev,
          remoteAuthor,
          fields,
          detectedAt,
          RESOLVED,
          rev);
    }
  }

  /**
   * Records a pending conflict and returns its id: main's side is {@code remoteSnapshot}, at {@code
   * remoteRev} by {@code remoteAuthor}. One open conflict per entity at a time.
   */
  public long record(
      String entityType,
      String entityId,
      String baseSnapshot,
      String localSnapshot,
      String remoteSnapshot,
      String remoteRev,
      String remoteAuthor,
      List<String> fields) {
    return db.transaction(
        () -> {
          db.execute(
              "DELETE FROM sync_conflicts WHERE entity_type = ? AND entity_id = ? AND status = ?",
              entityType,
              entityId,
              PENDING);
          db.execute(
              """
              INSERT INTO sync_conflicts (entity_type, entity_id, base_snapshot, local_snapshot,
                  remote_snapshot, remote_rev, remote_author, fields, detected_at, status)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
              entityType,
              entityId,
              baseSnapshot,
              localSnapshot,
              remoteSnapshot,
              remoteRev,
              remoteAuthor,
              encodeFields(fields),
              DateTimeUtils.now().toString(),
              PENDING);
          return db.queryOne("SELECT last_insert_rowid()", row -> row.integer(0)).orElseThrow();
        });
  }

  /** Every open conflict, oldest first. */
  public List<Conflict> pending() {
    return db.query(SELECT + " WHERE status = ? ORDER BY id", SyncConflicts::map, PENDING);
  }

  /** The ids of every {@code entityType} entity with an open conflict, oldest first. */
  public List<String> pendingIds(String entityType) {
    return db.query(
        "SELECT entity_id FROM sync_conflicts WHERE status = ? AND entity_type = ? ORDER BY id",
        row -> row.text(0),
        PENDING,
        entityType);
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

  /**
   * Closes whatever conflict is open on an entity that has just reconciled cleanly at {@code rev}:
   * the disagreement it recorded no longer exists, and resolving it later would write its stale
   * snapshot over the settled row.
   */
  public void settle(String entityType, String entityId, String rev) {
    db.execute(
        "UPDATE sync_conflicts SET status = ?, resolved_rev = ? WHERE entity_type = ?"
            + " AND entity_id = ? AND status = ?",
        RESOLVED,
        rev,
        entityType,
        entityId,
        PENDING);
  }

  /** Drops every conflict, open or settled, recorded for an erased entity. */
  public void erase(String entityType, String entityId) {
    db.execute(
        "DELETE FROM sync_conflicts WHERE entity_type = ? AND entity_id = ?", entityType, entityId);
  }

  /** The column encoding of a conflict's clashing field names: one per line. */
  public static String encodeFields(List<String> fields) {
    return String.join("\n", fields);
  }

  public static List<String> decodeFields(String encoded) {
    return encoded == null || encoded.isEmpty() ? List.of() : List.of(encoded.split("\n"));
  }

  private static final String SELECT =
      """
      SELECT id, entity_type, entity_id, base_snapshot, local_snapshot, remote_snapshot,
          remote_rev, remote_author, fields, detected_at, status, resolved_rev
      FROM sync_conflicts""";

  private static Conflict map(Sqlite.Row row) {
    return new Conflict(
        row.integer(0),
        row.text(1),
        row.text(2),
        row.text(3),
        row.text(4),
        row.text(5),
        row.text(6),
        row.text(7),
        decodeFields(row.text(8)),
        row.text(9),
        row.text(10),
        row.text(11));
  }
}
