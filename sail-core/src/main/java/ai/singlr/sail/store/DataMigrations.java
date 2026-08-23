/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.util.List;

/**
 * The one canonical registry of data migrations, shared by every lane that converges a database —
 * the {@code sail migrate} CLI, server start, and the sync engine — so a migration added in one
 * place can never silently be skipped by another.
 */
public final class DataMigrations {

  public static final List<DataMigration> ALL =
      List.of(new LegacyDataMigration(), new RoomsBackfillMigration());

  private DataMigrations() {}

  /** Whether any registered migration has not yet been applied to {@code db}. */
  public static boolean anyPending(Sqlite db) {
    return ALL.stream()
        .anyMatch(
            migration ->
                db.queryOne(
                        "SELECT 1 FROM data_migrations WHERE name = ?",
                        row -> row.integer(0),
                        migration.name())
                    .isEmpty());
  }
}
