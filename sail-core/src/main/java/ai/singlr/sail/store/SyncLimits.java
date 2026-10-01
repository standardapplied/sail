/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

/**
 * Main's shared-file ceiling as the node learned it from the last welcome, so an ingest outside a
 * sync — {@code sail project files add}, the API's put, {@code sail migrate}'s import — enforces
 * the lower of this box's own limit and main's before a file can enter and later be refused. One
 * row, replaced each round; 0 means no welcome has named it yet.
 */
public final class SyncLimits {

  private final Sqlite db;

  public SyncLimits(Sqlite db) {
    this.db = db;
  }

  /** Records main's {@code file_max}; a 0 (an older main that names none) leaves the last known. */
  public void recordMainFileMax(long fileMax) {
    if (fileMax <= 0) {
      return;
    }
    db.transaction(
        () -> {
          db.execute("DELETE FROM main_limits");
          db.execute("INSERT INTO main_limits (file_max) VALUES (?)", fileMax);
          return null;
        });
  }

  /** Main's file ceiling, or 0 when no welcome has named it. */
  public long mainFileMax() {
    return db.queryOne("SELECT file_max FROM main_limits", row -> row.integer(0)).orElse(0L);
  }
}
