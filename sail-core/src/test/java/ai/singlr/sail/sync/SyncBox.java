/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import java.nio.file.Path;
import java.util.List;

/**
 * One box in a sync test: its own SQLite database with the full set of stores and a {@link
 * StoreReplica}. Shared by the in-process, over-the-wire, and conflict-resolution harnesses so the
 * fixture is defined once.
 */
public final class SyncBox implements AutoCloseable {

  public final String id;
  public final Sqlite db;
  public final SpecStore specs;
  public final SyncConflicts conflicts;
  public final SyncState syncState;
  public final StoreReplica replica;

  public SyncBox(Path dir, String id) {
    this(id, Sqlite.open(dir.resolve(id + ".db")));
  }

  public SyncBox(String id) {
    this(id, Sqlite.openMemory());
  }

  private SyncBox(String id, Sqlite db) {
    this.id = id;
    this.db = db;
    new SchemaManager(db).migrate();
    this.specs = new SpecStore(db);
    this.conflicts = new SyncConflicts(db);
    this.syncState = new SyncState(db);
    this.replica = new StoreReplica(id, specs, new ChangeLog(db), conflicts, syncState);
  }

  public static SpecStore.SpecRow spec(String id, String title, String status) {
    return new SpecStore.SpecRow(
        id,
        "proj",
        title,
        SpecStatus.fromWire(status),
        null,
        null,
        null,
        null,
        null,
        0,
        "uday",
        "",
        "",
        "uday",
        List.of(),
        List.of());
  }

  @Override
  public void close() {
    db.close();
  }
}
