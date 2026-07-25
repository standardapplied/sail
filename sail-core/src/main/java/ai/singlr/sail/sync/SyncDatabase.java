/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.store.LegacyDataMigration;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;

/**
 * The only database handle a sync path may run on: constructing one converges the schema and
 * verifies the v1 data floor, so a sync that skips either requirement is unrepresentable. Exists
 * because of the in-sync self-update incident where a freshly-replaced binary kept syncing against
 * the previous release's schema and aborted on a stale CHECK constraint — both sync entry points
 * (the node's local-replica open and main's RPC-serving open) go through {@link #converge}. Schema
 * migration is idempotent and cheap when already current; data migration remains the explicit
 * responsibility of {@code sail migrate}.
 */
public final class SyncDatabase implements AutoCloseable {

  private final Sqlite db;

  private SyncDatabase(Sqlite db) {
    this.db = db;
  }

  /**
   * Opens the database at {@code dbPath}, converges its schema, and verifies the required data
   * migration marker before returning. A failure closes the handle before any sync data is touched.
   */
  public static SyncDatabase converge(Path dbPath, String box) {
    var db = Sqlite.open(dbPath);
    try {
      new SchemaManager(db).migrate();
    } catch (RuntimeException e) {
      db.close();
      throw new IllegalStateException(
          "Sync aborted before touching data: could not converge the database schema on '"
              + box
              + "': "
              + e.getMessage()
              + ". Run 'sail upgrade' on '"
              + box
              + "', then sync again.",
          e);
    }
    if (db.queryOne(
            "SELECT 1 FROM data_migrations WHERE name = ?",
            row -> row.integer(0),
            LegacyDataMigration.NAME)
        .isEmpty()) {
      db.close();
      throw new IllegalStateException(
          "Required data migration "
              + LegacyDataMigration.NAME
              + " has not completed; run 'sail migrate' before syncing.");
    }
    return new SyncDatabase(db);
  }

  /** The converged handle; valid until {@link #close()}. */
  public Sqlite db() {
    return db;
  }

  @Override
  public void close() {
    db.close();
  }
}
