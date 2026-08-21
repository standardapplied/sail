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
final class SyncBox implements AutoCloseable {

  final String id;
  final Sqlite db;
  final SpecStore specs;
  final SyncConflicts conflicts;
  final SyncState syncState;
  final StoreReplica replica;

  SyncBox(Path dir, String id) {
    this.id = id;
    this.db = Sqlite.open(dir.resolve(id + ".db"));
    new SchemaManager(db).migrate();
    this.specs = new SpecStore(db);
    this.conflicts = new SyncConflicts(db);
    this.syncState = new SyncState(db);
    this.replica = new StoreReplica(id, specs, new ChangeLog(db), conflicts, syncState);
  }

  static SpecStore.SpecRow spec(String id, String title, String status) {
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
