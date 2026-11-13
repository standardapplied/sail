/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.identity.ProjectScope;
import java.util.Objects;

/**
 * The change feed's floors: the oldest seq this box still answers a reader from. A write that
 * changes what a reader may read, with no entry of any row that reader is told of to say so, raises
 * the floor of the readers it can affect, and their older positions are answered afresh.
 *
 * <p>A floor is an entry of the change log of its own type, {@link #TYPE}, filed under no project,
 * so no sync pages it and no scope holds it; every other reader of the log names the types it
 * reads, and so passes over it. Each floor keeps one entry: raising it moves it to a new entry with
 * a fresh random rev, so a position standing on it says nothing of whose floor rose, and keeps the
 * entry it replaces as a compacted one, so a position standing on that still checks ({@link
 * FeedPositions}).
 */
public final class FeedFloors {

  /** The type of a floor's entries: of no synced type, and filed under no project. */
  public static final String TYPE = "feed";

  /** The id of the floor every reader of this box answers to. */
  private static final String BOX = "box";

  /** The prefix of the id of the floor of the readers acting for one FDE. */
  private static final String FDE = "fde:";

  /** The prefix of the id of the floor of the scoped readers of one project. */
  private static final String PROJECT = "project:";

  private final Sqlite db;

  public FeedFloors(Sqlite db) {
    this.db = Objects.requireNonNull(db, "db");
  }

  /**
   * The oldest seq this database still answers a reader from who acts for the FDE {@code fde} (null
   * for one acting for none) and reads {@code scope}: the seq of the newest entry that raised the
   * box's floor, that FDE's, or, for a scoped reader, a floor of one of its projects; 0 while none
   * has. A reader of every project answers to no project's floor.
   */
  public long floor(String fde, ProjectScope scope) {
    return db.queryOne(
            """
            SELECT COALESCE(MAX(seq), 0) FROM change_heads WHERE entity_type = ?1
            AND (entity_id IN (?2, ?3)
                OR (?4 IS NOT NULL AND entity_id IN (SELECT ?5 || value FROM json_each(?4))))""",
            row -> row.integer(0),
            TYPE,
            BOX,
            fde == null ? BOX : FDE + fde,
            ChangeLog.scopeArgument(scope),
            PROJECT)
        .orElse(0L);
  }

  /**
   * Raises the floor of every reader of the box, in the caller's transaction: history was rewritten
   * with no newer entry to tell a reader what became of a row it held.
   */
  void raiseForEveryone() {
    raise(BOX);
  }

  /** Raises the floor of the readers acting for the FDE {@code fde}, whose reads changed. */
  void raiseFor(String fde) {
    raise(FDE + fde);
  }

  /** Raises the floor of the scoped readers of {@code project}. */
  void raiseOf(String project) {
    raise(PROJECT + project);
  }

  /** Moves the floor {@code id} to a new entry, keeping the one before as a compacted entry. */
  private void raise(String id) {
    db.transaction(
        () -> {
          db.execute(
              "INSERT INTO compacted_entries (seq, entity_type, entity_id, rev)"
                  + " SELECT seq, entity_type, entity_id, rev FROM change_log"
                  + " WHERE entity_type = ? AND entity_id = ?",
              TYPE,
              id);
          db.execute("DELETE FROM change_log WHERE entity_type = ? AND entity_id = ?", TYPE, id);
          db.execute(
              "INSERT INTO change_log (entity_type, entity_id, rev, recorded_at, origin, snapshot)"
                  + " VALUES (?, ?, ?, ?, ?, '{}')",
              TYPE,
              id,
              DateTimeUtils.newId().toString(),
              DateTimeUtils.now(),
              ChangeLog.Entry.LOCAL);
          db.execute(
              """
              INSERT INTO change_heads (entity_type, entity_id, seq, project)
              VALUES (?, ?, last_insert_rowid(), NULL)
              ON CONFLICT(entity_type, entity_id) DO UPDATE SET seq = excluded.seq""",
              TYPE,
              id);
          db.afterCommit(db.appended);
        });
  }
}
