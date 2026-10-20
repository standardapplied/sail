/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.config.ProjectRegistry;
import ai.singlr.sail.engine.NodeIdentity;
import ai.singlr.sail.identity.ProjectScope;
import java.util.List;

/**
 * Single entry point for applying every pending schema and data migration to an open database. Run
 * on every {@code sail server start}, on {@code sail server init}, and by the {@code sail migrate}
 * CLI so the schema is always current regardless of how the operator got here.
 *
 * <p>The {@code sail upgrade} flow also spawns the freshly-installed binary's {@code sail migrate}
 * as a sub-process, which lands in this same code path — that's how a binary upgrade picks up the
 * NEW release's migrations without the OLD binary needing to know they exist.
 *
 * <p>Once the schema is current — at {@code sail migrate}, every start of sail-api, and a sync
 * round that finds a data migration pending — a box that is not a node forgets the scope main last
 * named it ({@link SyncScope}): a box promoted before a role change cleared it, or whose role
 * change died between writing {@code host.yaml} and clearing it, would otherwise keep refusing its
 * own writes and its nodes' pushes into every project outside that scope. Only a whole {@code
 * host.yaml} decides it ({@link NodeIdentity#declaresNotNode}): one absent, empty or unreadable at
 * that moment keeps the scope for the next start to decide, so no node loses its grant to a file
 * being moved or rewritten. It takes no lock: a whole file is the box's identity when read, and a
 * box just joined as a node is without a scope until its first round records one, repair or not.
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
    forgetNodeScope(db);
    var projects = ProjectRegistry.loadFromDisk();
    var runs = new DataMigrator(db, dataMigrations).run(projects, prompter);
    return new Result(before, after, runs);
  }

  private static void forgetNodeScope(Sqlite db) {
    if (NodeIdentity.declaresNotNode()) {
      new SyncScope(db).record(ProjectScope.all());
    }
  }

  /**
   * @param schemaBefore schema version when {@link #applyAll} was called
   * @param schemaAfter schema version after migration (equal to {@code schemaBefore} when no schema
   *     change applied)
   * @param dataRuns per-migration outcome from the data-migrator
   */
  public record Result(int schemaBefore, int schemaAfter, List<DataMigrator.Run> dataRuns) {}
}
