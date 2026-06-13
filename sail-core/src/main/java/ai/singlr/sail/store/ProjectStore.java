/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Project-definition catalog on SQLite. Each project's full descriptor (the canonical {@code
 * sail.yaml}) is stored verbatim as the {@code definition} blob — the catalog never queries inside
 * a definition, it loads it whole and hands it to the provisioner — alongside the handful of
 * columns the board lists and joins on (name, attribution, timestamps).
 *
 * <p>This is the shared coordination record that replicates across devboxes (see the {@code
 * db-sync} spec). Containers and run state are local and live elsewhere; this table is only the
 * definition every box agrees on.
 */
public final class ProjectStore {

  private final Sqlite db;

  public ProjectStore(Sqlite db) {
    this.db = db;
  }

  public record ProjectRow(
      String name,
      String definition,
      String createdBy,
      String createdAt,
      String updatedBy,
      String updatedAt) {}

  /**
   * Inserts the project or replaces its definition if it already exists, preserving the original
   * {@code created_by}/{@code created_at}. Idempotent: re-applying the same definition is a no-op
   * beyond bumping {@code updated_at}.
   */
  public void upsert(String name, String definition, String actor) {
    var now = Instant.now().toString();
    db.transaction(
        () -> {
          var existing = findByName(name);
          if (existing.isPresent()) {
            db.execute(
                "UPDATE projects SET definition = ?, updated_by = ?, updated_at = ? WHERE name = ?",
                definition,
                actor,
                now,
                name);
          } else {
            db.execute(
                "INSERT INTO projects (name, definition, created_by, created_at, updated_by,"
                    + " updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                name,
                definition,
                actor,
                now,
                actor,
                now);
          }
        });
  }

  public Optional<ProjectRow> findByName(String name) {
    return db.queryOne(SELECT + " WHERE name = ?", ProjectStore::map, name);
  }

  public List<ProjectRow> list() {
    return db.query(SELECT + " ORDER BY name", ProjectStore::map);
  }

  public boolean delete(String name) {
    db.execute("DELETE FROM projects WHERE name = ?", name);
    return db.changes() > 0;
  }

  private static final String SELECT =
      "SELECT name, definition, created_by, created_at, updated_by, updated_at FROM projects";

  private static ProjectRow map(Sqlite.Row row) {
    return new ProjectRow(
        row.text(0), row.text(1), row.text(2), row.text(3), row.text(4), row.text(5));
  }
}
