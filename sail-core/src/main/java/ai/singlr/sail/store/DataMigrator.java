/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.ProjectRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs all registered {@link DataMigration}s that haven't been applied yet, tracking each one in
 * the {@code data_migrations} table so re-runs are no-ops. Mirrors {@link SchemaManager}'s
 * versioned-migration discipline but for content rather than schema.
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
    var applied = appliedNames();
    var runs = new ArrayList<Run>();
    for (var migration : migrations) {
      if (applied.contains(migration.name())) {
        runs.add(new Run(migration.name(), true, DataMigration.Report.empty()));
        continue;
      }
      var report = migration.apply(db, projects, prompter);
      db.execute(
          "INSERT INTO data_migrations (name, applied_at) VALUES (?, ?)",
          migration.name(),
          DateTimeUtils.now().toString());
      runs.add(new Run(migration.name(), false, report));
    }
    return List.copyOf(runs);
  }

  private Set<String> appliedNames() {
    return new HashSet<>(db.query("SELECT name FROM data_migrations", row -> row.text(0)));
  }

  /**
   * Per-migration outcome: name, whether it was already applied, and the freshly-produced report.
   */
  public record Run(String name, boolean alreadyApplied, DataMigration.Report report) {}
}
