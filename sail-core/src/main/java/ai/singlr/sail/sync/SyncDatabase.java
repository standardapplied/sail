/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.LegacyDataMigration;
import ai.singlr.sail.store.MigrationRunner;
import ai.singlr.sail.store.RoomsBackfillMigration;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;

/**
 * The only database handle a sync path may run on: constructing one converges the schema and
 * verifies the v1 data floor, so a sync that skips either requirement is unrepresentable. Exists
 * because of the in-sync self-update incident where a freshly-replaced binary kept syncing against
 * the previous release's schema and aborted on a stale CHECK constraint — both sync entry points
 * (the node's local-replica open and main's RPC-serving open) go through {@link #converge}. Schema
 * migration is idempotent and cheap when already current; data migration on a pre-existing database
 * remains the explicit responsibility of {@code sail migrate}.
 */
public final class SyncDatabase implements AutoCloseable {

  private final Sqlite db;

  private SyncDatabase(Sqlite db) {
    this.db = db;
  }

  /**
   * Opens the database at {@code dbPath}, converges its schema, and guarantees the v1 data floor
   * before returning. A failure closes the handle before any sync data is touched.
   *
   * <p>{@link SchemaManager#migrate} refuses a below-floor database with the install-0.14.x remedy
   * — that refusal passes through untouched. A database that converges cleanly but has no floor
   * marker was necessarily born at or above the floor (the schema floor forbids any other
   * crossing), so the data migrations run here only to finish or stamp the floor repair — keeping a
   * fresh or auxiliary-created box's first sync frictionless.
   */
  public static SyncDatabase converge(Path dbPath, String box) {
    var db = Sqlite.open(dbPath);
    try {
      new SchemaManager(db).migrate();
      var pending = List.of(LegacyDataMigration.NAME, RoomsBackfillMigration.NAME);
      if (pending.stream()
          .anyMatch(
              name ->
                  db.queryOne(
                          "SELECT 1 FROM data_migrations WHERE name = ?",
                          row -> row.integer(0),
                          name)
                      .isEmpty())) {
        MigrationRunner.applyAll(
            db,
            List.of(new LegacyDataMigration(), new RoomsBackfillMigration()),
            DataMigration.Prompter.NON_INTERACTIVE);
      }
    } catch (SchemaManager.PreFloorException e) {
      db.close();
      throw e;
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
