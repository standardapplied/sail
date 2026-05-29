/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.config.ProjectRegistry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Assigns a project to every spec stuck on {@code 'unassigned'} (the backfill value from schema
 * migration 23). Heuristic: look at the spec's repos list and find every project that contains at
 * least one of those repos. Exactly one match → auto-assign. Multiple → ask the prompter. Zero →
 * leave alone and report it so the operator can fix it manually.
 *
 * <p>Repo matching tries the spec repo string as-is first, then its basename, so a spec with {@code
 * "chorus"} matches a project whose descriptor has {@code path: ./chorus} or {@code path:
 * /home/dev/workspace/chorus}.
 */
public final class RebucketSpecsMigration implements DataMigration {

  public static final String NAME = "rebucket-specs-2026-05";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Report apply(Sqlite db, ProjectRegistry projects, Prompter prompter) {
    var specStore = new SpecStore(db);
    var unassigned = specStore.list(new SpecStore.SpecFilter("unassigned", null, null, null, null));
    if (unassigned.isEmpty()) {
      return Report.empty();
    }

    var applied = 0;
    var ambiguous = 0;
    var skipped = 0;
    var notes = new ArrayList<String>();

    for (var spec : unassigned) {
      var candidates = candidatesFor(spec, projects);
      if (candidates.size() == 1) {
        moveSpec(specStore, spec, candidates.getFirst());
        applied++;
        continue;
      }
      if (candidates.isEmpty()) {
        skipped++;
        notes.add(
            "  • "
                + spec.id()
                + " — no project found that contains repos "
                + (spec.repos().isEmpty() ? "(none)" : spec.repos())
                + "; assign manually via 'sail spec edit "
                + spec.id()
                + " --project <name>'");
        continue;
      }
      var chosen = prompter.choose("spec '" + spec.id() + "'", candidates);
      if (chosen.isPresent() && candidates.contains(chosen.get())) {
        moveSpec(specStore, spec, chosen.get());
        applied++;
      } else {
        ambiguous++;
        notes.add(
            "  • "
                + spec.id()
                + " — ambiguous (matches "
                + candidates
                + "); rerun with --interactive or assign via 'sail spec edit "
                + spec.id()
                + " --project <name>'");
      }
    }
    return new Report(applied, ambiguous, skipped, List.copyOf(notes));
  }

  private static List<String> candidatesFor(SpecStore.SpecRow spec, ProjectRegistry projects) {
    if (spec.repos().isEmpty()) {
      return List.of();
    }
    var hits = new LinkedHashSet<String>();
    for (var repo : spec.repos()) {
      hits.addAll(projects.projectsContainingRepo(repo));
      var basename = basename(repo);
      if (!basename.equals(repo)) {
        hits.addAll(projects.projectsContainingRepo(basename));
      }
    }
    return List.copyOf(hits);
  }

  private static void moveSpec(SpecStore specStore, SpecStore.SpecRow spec, String project) {
    var moved =
        new SpecStore.SpecRow(
            spec.id(),
            project,
            spec.title(),
            spec.status(),
            spec.assignee(),
            spec.agent(),
            spec.model(),
            spec.reasoningEffort(),
            spec.branch(),
            spec.priority(),
            spec.createdBy(),
            spec.createdAt(),
            spec.updatedAt(),
            spec.dependsOn(),
            spec.repos());
    specStore.update(moved);
  }

  private static String basename(String path) {
    var idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return idx < 0 ? path : path.substring(idx + 1);
  }
}
