/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.config.ProjectRegistry;
import java.util.List;

/**
 * Gives every change head written before heads carried their project the project it belongs to
 * ({@link ChangeLog#backfillProjects}), so main scopes a page from the head alone. A head whose
 * entity was pruned before this release keeps none, and reaches unscoped nodes only.
 */
public final class ScopedHeadsMigration implements DataMigration {

  public static final String NAME = "scoped-heads-v1";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Report apply(Sqlite db, ProjectRegistry projects, Prompter prompter) {
    var filled = new ChangeLog(db).backfillProjects();
    var unknown =
        db.queryOne(
                "SELECT COUNT(*) FROM change_heads WHERE project IS NULL", row -> row.integer(0))
            .orElse(0L);
    return new Report(
        Math.toIntExact(filled),
        0,
        Math.toIntExact(unknown),
        unknown == 0
            ? List.of()
            : List.of(
                unknown
                    + " change head(s) name no project (pruned before this release); only"
                    + " unscoped nodes hear of them."));
  }
}
