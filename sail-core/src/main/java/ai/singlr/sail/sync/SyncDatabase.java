/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;

/**
 * Opens the schema before a sync path touches replicated data: both sync entry points go through
 * {@link #prepare}, so a database of another release is refused before anything is served or
 * synchronized; {@link #converge} also owns the RPC server's database handle.
 */
public final class SyncDatabase implements AutoCloseable {

  private final Sqlite db;

  private SyncDatabase(Sqlite db) {
    this.db = db;
  }

  /**
   * Opens the database at {@code dbPath} and creates its schema when it is fresh, before returning.
   * A failure closes the handle before any sync data is touched; a database {@link SchemaManager}
   * refuses passes through with its own remedy.
   */
  public static SyncDatabase converge(Path dbPath, String box) {
    var db = Sqlite.open(dbPath);
    try {
      prepare(db, box);
      return new SyncDatabase(db);
    } catch (RuntimeException e) {
      db.close();
      throw e;
    }
  }

  /** Creates {@code db}'s schema when it is fresh, or refuses a database of another release. */
  public static void prepare(Sqlite db, String box) {
    try {
      new SchemaManager(db).migrate();
    } catch (Remedied.State refused) {
      throw refused;
    } catch (RuntimeException e) {
      throw Remedied.state(
          "Sync aborted before touching data: could not converge the database schema on '"
              + box
              + "': "
              + e.getMessage()
              + ".",
          Remedy.of("Run '", Remedy.sail("upgrade"), "' on '" + box + "', then sync again."),
          e);
    }
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
