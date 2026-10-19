/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.SailPaths;
import java.util.List;

/**
 * The one canonical registry of data migrations, shared by every lane that converges a database —
 * the {@code sail migrate} CLI, server start, and the sync engine — so a migration added in one
 * place can never silently be skipped by another.
 */
public final class DataMigrations {

  private DataMigrations() {}

  /** Every migration, in order; what one reports to {@code operator} is worded for them. */
  public static List<DataMigration> all(OperatorCommand operator) {
    return List.of(
        new ContentMigration(operator),
        new LegacyDataMigration(operator),
        new ReviewFindingsMigration(),
        new RoomsBackfillMigration(),
        new OrphanErasure(),
        new PersonalRoomErasure(),
        new MaterializedFilesMigration(SailPaths.projectsDir(), operator),
        new StagePromptsMigration(),
        new ScopedHeadsMigration());
  }

  /**
   * The migrations the sync lane applies: those that need nothing of this box's own files. What one
   * reports is worded for {@code operator}, the reader of the round.
   */
  public static List<DataMigration> databaseOnly(OperatorCommand operator) {
    return all(operator).stream().filter(migration -> !migration.readsBoxFiles()).toList();
  }

  /** Whether any of {@code migrations} has not yet been applied to {@code db}. */
  public static boolean anyPending(Sqlite db, List<DataMigration> migrations) {
    return migrations.stream()
        .anyMatch(
            migration ->
                db.queryOne(
                        "SELECT 1 FROM data_migrations WHERE name = ?",
                        row -> row.integer(0),
                        migration.name())
                    .isEmpty());
  }
}
