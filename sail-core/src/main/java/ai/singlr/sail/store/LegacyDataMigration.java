/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.ProjectRegistry;
import ai.singlr.sail.engine.NodeIdentity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

/** Carries every pre-0.14 row to the v1 data floor exactly once. */
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
    var notes = new ArrayList<String>();
    var attributed = attributeSpecs(db, projects, notes);
    var runs = new RunStore(db);
    var stamped = stampRuns(db, runs);
    var terminalized = terminalizeLegacyBuilds(db, runs);
    var journaled =
        runs.backfillRevisions()
            + new SpecStore(db).backfillRevisions()
            + new ProjectStore(db).backfillRevisions()
            + new ReviewStore(db).backfillRevisions();
    var ambiguous = nullProjectIds(db).size();
    return new Report(
        attributed + stamped + terminalized + journaled, ambiguous, 0, List.copyOf(notes));
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
    return runs.backfillNode(handle);
  }

  private static int terminalizeLegacyBuilds(Sqlite db, RunStore runs) {
    var active =
        db.query(
            """
            SELECT id, status FROM runs
            WHERE role = 'build' AND COALESCE(unit, '') = ''
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

  private static int attributeSpecs(Sqlite db, ProjectRegistry projects, List<String> notes) {
    var applied = 0;
    for (var id : nullProjectIds(db)) {
      var candidates = projectCandidates(db, id, projects);
      if (candidates.size() != 1) {
        continue;
      }
      db.execute(
          "UPDATE specs SET project = ? WHERE id = ? AND project IS NULL",
          candidates.getFirst(),
          id);
      applied += db.changes();
    }
    var unresolved = nullProjectIds(db);
    if (!unresolved.isEmpty()) {
      notes.add(
          "  ! "
              + unresolved.size()
              + " spec(s) still have no project: "
              + String.join(", ", unresolved)
              + ". Assign each explicitly with 'sail spec edit <id> --project <name>'.");
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

  private static List<String> nullProjectIds(Sqlite db) {
    return db.query("SELECT id FROM specs WHERE project IS NULL ORDER BY id", row -> row.text(0));
  }

  private static String basename(String path) {
    var separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return separator < 0 ? path : path.substring(separator + 1);
  }

  private record LegacyRun(String id, String status) {}
}
