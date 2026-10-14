/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.identity.ProjectScope;
import java.util.Objects;

/**
 * The scope main's last welcome named for this node: the projects it sends this box, and so the
 * only ones this box may start. Main decides it and a node never does; this is only what the node
 * was last told, replaced whole each round. Unlike main's file ceiling ({@link SyncLimits}), a
 * welcome naming no scope replaces it with every project: a main that says nothing scopes no one,
 * and the last scope heard is not a thing to keep.
 */
public final class SyncScope {

  private final Sqlite db;

  public SyncScope(Sqlite db) {
    this.db = Objects.requireNonNull(db, "db");
  }

  /** The scope last recorded; every project on a box no welcome has scoped. */
  public ProjectScope held() {
    var rows =
        db.query(
            "SELECT kind, COALESCE(project, '') FROM sync_scope",
            row -> new String[] {row.text(0), row.text(1)});
    if (rows.isEmpty() || rows.stream().anyMatch(row -> "all".equals(row[0]))) {
      return ProjectScope.all();
    }
    return ProjectScope.of(
        rows.stream().map(row -> row[1]).filter(project -> !project.isEmpty()).toList());
  }

  /** Replaces the recorded scope with {@code scope}. */
  public void record(ProjectScope scope) {
    db.transaction(
        () -> {
          db.execute("DELETE FROM sync_scope");
          if (scope.unrestricted()) {
            db.execute("INSERT INTO sync_scope (kind) VALUES ('all')");
          } else if (scope.projects().isEmpty()) {
            db.execute("INSERT INTO sync_scope (kind) VALUES ('granted')");
          }
          for (var project : scope.projects()) {
            db.execute("INSERT INTO sync_scope (kind, project) VALUES ('granted', ?)", project);
          }
          return null;
        });
  }
}
