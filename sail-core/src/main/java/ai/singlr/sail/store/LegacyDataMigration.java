/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.ProjectRegistry;
import ai.singlr.sail.engine.NodeIdentity;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * Carries every pre-0.14 row to the v1 data floor exactly once. It remains registered past the v1
 * schema baseline because its completion marker is the data-floor stamp, and because a 0.14 box
 * whose repair was interrupted finishes it here after the schema on-ramp; it is not a runtime
 * compatibility path.
 */
public final class LegacyDataMigration implements DataMigration {

  public static final String NAME = "legacy-data-floor-0.14.0";

  private final Supplier<String> nodeHandle;

  public LegacyDataMigration() {
    this(NodeIdentity::handle);
  }

  LegacyDataMigration(Supplier<String> nodeHandle) {
    this.nodeHandle = nodeHandle;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Report apply(Sqlite db, ProjectRegistry projects, Prompter prompter) {
    var attributed = attributeSpecs(db, projects);
    var unresolved = legacyProjectIds(db);
    if (!unresolved.isEmpty()) {
      throw new IllegalStateException(
          "Cannot complete the 0.14.0 migration until these specs have projects: "
              + String.join(", ", unresolved));
    }
    var runs = new RunStore(db);
    var stamped = stampRuns(db, runs);
    var terminalized = terminalizeLegacyBuilds(db, runs);
    var journaled = journalRows(db);
    return new Report(attributed + stamped + terminalized + journaled, 0, 0, List.of());
  }

  private int stampRuns(Sqlite db, RunStore runs) {
    var ids =
        db.query(
            "SELECT id FROM runs WHERE node IS NULL OR node = '' ORDER BY id", row -> row.text(0));
    if (ids.isEmpty()) {
      return 0;
    }
    var handle = nodeHandle.get();
    if (Strings.isBlank(handle)) {
      throw new IllegalStateException(
          "The 0.14.0 migration cannot attribute "
              + ids.size()
              + " legacy run(s) without this box's FDE handle: "
              + String.join(", ", ids)
              + ". Set it with 'sail host config set sync-handle <handle>', then rerun"
              + " 'sail migrate'.");
    }
    for (var id : ids) {
      db.transaction(
          () -> {
            db.execute("UPDATE runs SET node = ? WHERE id = ?", handle, id);
            runs.recordRevision(id, "migration", false);
          });
    }
    return ids.size();
  }

  private static int journalRows(Sqlite db) {
    var runs = new RunStore(db);
    var specs = new SpecStore(db);
    var projects = new ProjectStore(db);
    var reviews = new ReviewStore(db);
    var changed = 0;
    for (var id : unjournaled(db, "runs", "id", "run")) {
      db.transaction(() -> runs.recordRevision(id, "migration", false));
      changed++;
    }
    for (var id : unjournaled(db, "specs", "id", "spec")) {
      db.transaction(() -> specs.recordRevision(id, "migration", false));
      changed++;
    }
    for (var id : unjournaled(db, "projects", "name", "project")) {
      var definition = projects.findByName(id).orElseThrow().definition();
      db.transaction(
          () -> projects.recordRevision(id, definition, null, "migration", false, false));
      changed++;
    }
    for (var id : unjournaled(db, "reviews", "id", "review")) {
      db.transaction(() -> reviews.recordRevision(id, null, "migration", false, false));
      changed++;
    }
    return changed;
  }

  private static List<String> unjournaled(
      Sqlite db, String table, String idColumn, String entityType) {
    return db.query(
        "SELECT "
            + idColumn
            + " FROM "
            + table
            + " WHERE NOT EXISTS (SELECT 1 FROM change_log"
            + " WHERE entity_type = ? AND entity_id = "
            + table
            + "."
            + idColumn
            + ") ORDER BY "
            + idColumn,
        row -> row.text(0),
        entityType);
  }

  private static int terminalizeLegacyBuilds(Sqlite db, RunStore runs) {
    var active =
        db.query(
            """
            SELECT id, status FROM runs
            WHERE role = 'build' AND COALESCE(unit, '') = ''
                AND repos IS NULL
                AND status IN ('running', 'stopping')
            ORDER BY id""",
            row -> new LegacyRun(row.text(0), row.text(1)));
    var changed = 0;
    for (var run : active) {
      if (runs.transition(run.id(), run.status(), "stopped")) {
        changed++;
      }
    }
    return changed;
  }

  private static int attributeSpecs(Sqlite db, ProjectRegistry projects) {
    var applied = 0;
    var specs = new SpecStore(db);
    for (var id : legacyProjectIds(db)) {
      var candidates = projectCandidates(db, id, projects);
      if (candidates.size() != 1) {
        continue;
      }
      applied += specs.assignMigrationProject(id, candidates.getFirst()) ? 1 : 0;
    }
    return applied;
  }

  private static List<String> projectCandidates(
      Sqlite db, String specId, ProjectRegistry projects) {
    if (projects.names().size() == 1) {
      return projects.names();
    }
    var matches = new LinkedHashSet<String>();
    var repos =
        db.query(
            "SELECT repo FROM spec_repos WHERE spec_id = ? ORDER BY repo",
            row -> row.text(0),
            specId);
    for (var repo : repos) {
      matches.addAll(projects.projectsContainingRepo(repo));
      matches.addAll(projects.projectsContainingRepo(basename(repo)));
    }
    return List.copyOf(matches);
  }

  private static List<String> legacyProjectIds(Sqlite db) {
    return db.query(
        "SELECT id FROM specs WHERE project IS NULL OR project = 'unassigned' ORDER BY id",
        row -> row.text(0));
  }

  private static String basename(String path) {
    var separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return separator < 0 ? path : path.substring(separator + 1);
  }

  private record LegacyRun(String id, String status) {}
}
