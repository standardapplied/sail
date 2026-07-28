/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.config.ProjectRegistry;
import java.util.List;

/**
 * Single entry point for applying every pending schema and data migration to an open database. Run
 * on every {@code sail server start}, on {@code sail server init}, and by the {@code sail migrate}
 * CLI so the schema is always current regardless of how the operator got here.
 *
 * <p>The {@code sail upgrade} flow also spawns the freshly-installed binary's {@code sail migrate}
 * as a sub-process, which lands in this same code path — that's how a binary upgrade picks up the
 * NEW release's migrations without the OLD binary needing to know they exist.
 */
public final class MigrationRunner {

  private MigrationRunner() {}

  /** Applies schema then data migrations to {@code db}. Returns the data-migration runs. */
  public static Result applyAll(
      Sqlite db, List<DataMigration> dataMigrations, DataMigration.Prompter prompter) {
    var schema = new SchemaManager(db);
    var before = schema.currentVersion();
    schema.migrate();
    var after = schema.currentVersion();
    var projects = ProjectRegistry.loadFromDisk();
    var runs = new DataMigrator(db, dataMigrations).run(projects, prompter);
    return new Result(before, after, runs);
  }

  /**
   * @param schemaBefore schema version when {@link #applyAll} was called
   * @param schemaAfter schema version after migration (equal to {@code schemaBefore} when no schema
   *     change applied)
   * @param dataRuns per-migration outcome from the data-migrator
   */
  public record Result(int schemaBefore, int schemaAfter, List<DataMigrator.Run> dataRuns) {}
}
