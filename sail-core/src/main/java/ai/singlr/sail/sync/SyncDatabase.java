/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.LegacyDataMigration;
import ai.singlr.sail.store.MigrationRunner;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;

/**
 * The only database handle a sync path may run on: constructing one converges the schema, so a sync
 * that skips convergence is unrepresentable. Exists because of the in-sync self-update incident
 * where a freshly-replaced binary kept syncing against the previous release's schema and aborted on
 * a stale CHECK constraint — both sync entry points (the node's local-replica open and main's
 * RPC-serving open) go through {@link #converge}. Migration is idempotent and cheap when the schema
 * is already current, so converging unconditionally is safe.
 */
public final class SyncDatabase implements AutoCloseable {

  private final Sqlite db;

  private SyncDatabase(Sqlite db) {
    this.db = db;
  }

  /**
   * Opens the database at {@code dbPath} and converges its schema before returning. A convergence
   * failure closes the handle and aborts with a message naming {@code box} — the box whose binary
   * and schema disagree — so the operator knows where to run {@code sail upgrade}. No sync data has
   * been touched at that point.
   */
  public static SyncDatabase converge(Path dbPath, String box) {
    var db = Sqlite.open(dbPath);
    try {
      MigrationRunner.applyAll(
          db, List.of(new LegacyDataMigration()), DataMigration.Prompter.NON_INTERACTIVE);
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
