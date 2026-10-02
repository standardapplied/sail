/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.Strings;
import java.util.Objects;

/**
 * What this box knows of each shared file's copy on disk, so a copy is told from a person's edit by
 * this record alone, never by retained history: the version this box last wrote to disk or
 * published from it, and the one before, since a write records before it moves the file into place
 * and may die between the two. A copy matching either is this box's ({@link Copy#OURS}) to refresh
 * or remove. A copy the one-time seed could not tell, its file's history compacted past the version
 * it might match, is recorded as {@link Copy#UNDECIDED}: left alone and never published, until this
 * box writes or publishes the file again. Any other copy is a person's. Local to this box: never
 * journaled, never synced, not a blob reference.
 */
public final class MaterializedFiles {

  /** What a copy on disk is to this box. */
  public enum Copy {
    /** The version this box wrote, with no write in flight: nothing to record. */
    SETTLED,
    /**
     * This box's: a version it wrote or published, or the one before while a write is in flight.
     */
    OURS,
    /** A copy the seed could not tell from an edit: kept, never published. */
    UNDECIDED,
    /** A person's copy. */
    PERSONS;

    /** Whether the copy is this box's to refresh, remove or re-mode. */
    public boolean ours() {
      return this == SETTLED || this == OURS;
    }
  }

  private final Sqlite db;

  public MaterializedFiles(Sqlite db) {
    this.db = Objects.requireNonNull(db, "db");
  }

  /**
   * Records that this box is about to write {@code contentHash} at {@code mode} as file {@code id}:
   * the version becomes this box's, and the last one it knows landed on disk stays this box's too,
   * until {@link #wrote} says this write landed — a write that dies in between, or a publish whose
   * materialize never ran, leaves a copy this box still knows.
   */
  void writing(String id, String contentHash, int mode) {
    requireValid(id, contentHash, mode);
    db.execute(
        """
        INSERT INTO materialized_files (id, content_hash, mode, previous_hash, previous_mode,
            undecided)
        VALUES (?, ?, ?, NULL, NULL, 0)
        ON CONFLICT(id) DO UPDATE SET
            previous_hash = CASE WHEN undecided = 1 THEN NULL
                WHEN content_hash = excluded.content_hash AND mode = excluded.mode
                THEN previous_hash ELSE COALESCE(previous_hash, content_hash) END,
            previous_mode = CASE WHEN undecided = 1 THEN NULL
                WHEN content_hash = excluded.content_hash AND mode = excluded.mode
                THEN previous_mode ELSE COALESCE(previous_mode, mode) END,
            content_hash = excluded.content_hash,
            mode = excluded.mode,
            undecided = 0""",
        id,
        contentHash,
        mode);
  }

  /**
   * Records {@code contentHash} at {@code mode} as the version of file {@code id} this box wrote to
   * disk or published, and that alone: a write that landed, a copy found in sync, a publish.
   */
  void wrote(String id, String contentHash, int mode) {
    requireValid(id, contentHash, mode);
    db.execute(
        """
        INSERT INTO materialized_files (id, content_hash, mode, previous_hash, previous_mode,
            undecided)
        VALUES (?, ?, ?, NULL, NULL, 0)
        ON CONFLICT(id) DO UPDATE SET content_hash = excluded.content_hash,
            mode = excluded.mode, previous_hash = NULL, previous_mode = NULL, undecided = 0""",
        id,
        contentHash,
        mode);
  }

  /**
   * Records the copy of file {@code id} holding {@code contentHash} at {@code mode} as one this box
   * cannot tell from a person's edit: kept, reported, never published, until the file is written or
   * published here again. Never over a decided record.
   */
  void recordUndecided(String id, String contentHash, int mode) {
    requireValid(id, contentHash, mode);
    db.execute(
        """
        INSERT INTO materialized_files (id, content_hash, mode, previous_hash, previous_mode,
            undecided)
        VALUES (?, ?, ?, NULL, NULL, 1) ON CONFLICT(id) DO NOTHING""",
        id,
        contentHash,
        mode);
  }

  /**
   * What a disk copy of file {@code id} holding {@code contentHash} at {@code mode} is to this box.
   */
  Copy copyOf(String id, String contentHash, int mode) {
    return db.queryOne(
            """
            SELECT undecided, content_hash = ? AND mode = ? AND previous_hash IS NULL
                FROM materialized_files WHERE id = ?
                AND ((content_hash = ? AND mode = ?)
                     OR (undecided = 0 AND previous_hash = ? AND previous_mode = ?))""",
            row ->
                row.integer(0) == 1
                    ? Copy.UNDECIDED
                    : row.integer(1) == 1 ? Copy.SETTLED : Copy.OURS,
            contentHash,
            mode,
            id,
            contentHash,
            mode,
            contentHash,
            mode)
        .orElse(Copy.PERSONS);
  }

  /** Whether anything is recorded of file {@code id}. */
  boolean recorded(String id) {
    return db.queryOne("SELECT 1 FROM materialized_files WHERE id = ?", row -> row.integer(0), id)
        .isPresent();
  }

  /**
   * Forgets what this box wrote of file {@code id}: its copy left the disk, or the file is erased.
   */
  void forget(String id) {
    db.execute("DELETE FROM materialized_files WHERE id = ?", id);
  }

  /** Re-keys every record under project {@code old} to project {@code renamed}. */
  void rename(String old, String renamed) {
    var prefix = old + "/";
    db.execute(
        """
        UPDATE OR REPLACE materialized_files SET id = ? || substr(id, ?)
            WHERE substr(id, 1, ?) = ?""",
        renamed + "/",
        prefix.length() + 1,
        prefix.length(),
        prefix);
  }

  private static void requireValid(String id, String contentHash, int mode) {
    Strings.requireNonBlank(id, "A file record names its file id");
    BlobStore.requireHash(contentHash);
    if (mode < 0 || mode > 0777) {
      throw new IllegalArgumentException(
          "A file mode is 0 to 0777, not " + Integer.toOctalString(mode));
    }
  }
}
