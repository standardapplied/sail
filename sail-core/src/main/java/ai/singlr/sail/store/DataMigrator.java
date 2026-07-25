/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.ProjectRegistry;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs all registered {@link DataMigration}s that haven't been applied yet, tracking each one in
 * the {@code data_migrations} table so re-runs are no-ops. Mirrors {@link SchemaManager}'s
 * versioned-migration discipline but for content rather than schema.
 *
 * <p>Each migration claims, applies, and marks inside one {@code BEGIN IMMEDIATE} transaction. The
 * in-process connection lock cannot serialize separate processes — every sync session converges the
 * database in its own process — so a deferred check-then-apply lets two first runs both see a
 * migration as pending and double-apply it, with the loser failing on the marker's primary key
 * after its partial effects committed. Taking the write lock up front means the loser re-checks the
 * marker after the winner commits and skips cleanly, and a failing migration rolls back whole:
 * nested store transactions join this scope, so no partial migration ever persists.
 */
public final class DataMigrator {

  private final Sqlite db;
  private final List<DataMigration> migrations;

  public DataMigrator(Sqlite db, List<DataMigration> migrations) {
    this.db = db;
    this.migrations = List.copyOf(migrations);
  }

  /** Returns the names of every migration in the registry. */
  public List<String> registered() {
    return migrations.stream().map(DataMigration::name).toList();
  }

  /** Applies every migration not yet recorded in {@code data_migrations}. Returns each report. */
  public List<Run> run(ProjectRegistry projects, DataMigration.Prompter prompter) {
    var runs = new ArrayList<Run>();
    for (var migration : migrations) {
      runs.add(
          db.immediateTransaction(
              () -> {
                if (isApplied(migration.name())) {
                  return new Run(migration.name(), true, DataMigration.Report.empty());
                }
                var report = migration.apply(db, projects, prompter);
                db.execute(
                    "INSERT INTO data_migrations (name, applied_at) VALUES (?, ?)",
                    migration.name(),
                    DateTimeUtils.now().toString());
                return new Run(migration.name(), false, report);
              }));
    }
    return List.copyOf(runs);
  }

  private boolean isApplied(String name) {
    return !db.query("SELECT name FROM data_migrations WHERE name = ?", row -> row.text(0), name)
        .isEmpty();
  }

  /**
   * Per-migration outcome: name, whether it was already applied, and the freshly-produced report.
   */
  public record Run(String name, boolean alreadyApplied, DataMigration.Report report) {}
}
