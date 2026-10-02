/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.Strings;
import java.util.Objects;

/**
 * What this box knows of each shared file's copy on disk, so a copy is told from a person's edit by
 * this record alone, never by retained history: every version this box wrote to disk or published
 * since the last one it knows landed — a write records before it moves the file into place and may
 * die between the two, and a publish is landed only by the materialize that follows — and, once one
 * lands, that version alone. A copy matching any of them is this box's ({@link Copy#OURS}) to
 * refresh or remove. A copy the one-time seed could not tell, matching no version its file's
 * history still holds, is recorded as {@link Copy#UNDECIDED}: left alone and never published, until
 * this box writes or publishes the file again. Any other copy is a person's. Local to this box:
 * never journaled, never synced, not a blob reference.
 */
public final class MaterializedFiles {

  /** What a copy on disk is to this box. */
  public enum Copy {
    /** The one version this box wrote, with no write in flight: nothing to record. */
    SETTLED,
    /** This box's: a version it wrote or published, landed or in flight. */
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
   * Records that this box is about to write {@code contentHash} at {@code mode} as file {@code id},
   * or published it for the materialize that follows: the version is this box's beside every other
   * it has in flight, until {@link #wrote} says one landed; an undecided record gives way to it.
   */
  void writing(String id, String contentHash, int mode) {
    requireValid(id, contentHash, mode);
    db.execute("DELETE FROM materialized_files WHERE id = ? AND undecided = 1", id);
    db.execute(
        """
        INSERT INTO materialized_files (id, content_hash, mode, undecided) VALUES (?, ?, ?, 0)
            ON CONFLICT(id, content_hash, mode) DO UPDATE SET undecided = 0""",
        id,
        contentHash,
        mode);
  }

  /**
   * Records {@code contentHash} at {@code mode} as the version of file {@code id} on disk, and that
   * alone: a write that landed, a copy found in sync, a publish whose bytes came from the copy.
   */
  void wrote(String id, String contentHash, int mode) {
    requireValid(id, contentHash, mode);
    db.execute(
        "DELETE FROM materialized_files WHERE id = ? AND NOT (content_hash = ? AND mode = ?)",
        id,
        contentHash,
        mode);
    db.execute(
        """
        INSERT INTO materialized_files (id, content_hash, mode, undecided) VALUES (?, ?, ?, 0)
            ON CONFLICT(id, content_hash, mode) DO UPDATE SET undecided = 0""",
        id,
        contentHash,
        mode);
  }

  /**
   * Records the copy of file {@code id} holding {@code contentHash} at {@code mode} as one this box
   * cannot tell from a person's edit: kept, reported, never published, until the file is written or
   * published here again. Never beside a record this box holds.
   */
  void recordUndecided(String id, String contentHash, int mode) {
    requireValid(id, contentHash, mode);
    if (!recorded(id)) {
      db.execute(
          "INSERT INTO materialized_files (id, content_hash, mode, undecided) VALUES (?, ?, ?, 1)",
          id,
          contentHash,
          mode);
    }
  }

  /**
   * What a disk copy of file {@code id} holding {@code contentHash} at {@code mode} is to this box.
   */
  Copy copyOf(String id, String contentHash, int mode) {
    var versions =
        db.query(
            "SELECT content_hash, mode, undecided FROM materialized_files WHERE id = ?",
            row -> new Version(row.text(0), (int) row.integer(1), row.integer(2) == 1),
            id);
    var matched =
        versions.stream()
            .filter(version -> version.hash().equals(contentHash) && version.mode() == mode)
            .findFirst();
    if (matched.isEmpty()) {
      return Copy.PERSONS;
    }
    if (matched.get().undecided()) {
      return Copy.UNDECIDED;
    }
    return versions.size() == 1 ? Copy.SETTLED : Copy.OURS;
  }

  private record Version(String hash, int mode, boolean undecided) {}

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
