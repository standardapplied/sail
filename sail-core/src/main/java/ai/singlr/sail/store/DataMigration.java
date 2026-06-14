/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.config.ProjectRegistry;
import java.util.List;
import java.util.Optional;

/**
 * A one-shot fix-up applied to existing data after a schema change. Schema migrations add columns;
 * data migrations fill them in. Each migration is tracked by name in the {@code data_migrations}
 * table and runs exactly once per database, even across {@code sail migrate} re-runs.
 */
public interface DataMigration {

  /** Unique name used as the primary key in {@code data_migrations}. Keep this stable. */
  String name();

  /**
   * Applies the migration. Receives the database, a snapshot of registered projects (loaded from
   * {@code ~/.sail/projects/}), and a prompter so interactive heuristics can ask the operator.
   *
   * @return a summary of what was changed, ambiguous, or skipped
   */
  Report apply(Sqlite db, ProjectRegistry projects, Prompter prompter);

  /** Per-migration outcome surfaced back to the CLI for printing. */
  record Report(int applied, int ambiguous, int skipped, List<String> notes) {
    public static Report empty() {
      return new Report(0, 0, 0, List.of());
    }
  }

  /**
   * Interactive prompter abstraction. The CLI plugs in a real terminal prompt; tests plug in a
   * scripted answer source; non-interactive mode plugs in one that always returns empty.
   */
  interface Prompter {
    /**
     * Asks the operator to pick one of {@code candidates} for {@code context}. Returns the chosen
     * value or empty to leave the row alone.
     */
    Optional<String> choose(String context, List<String> candidates);

    /** Non-interactive prompter — never asks, always declines. */
    Prompter NON_INTERACTIVE = (context, candidates) -> Optional.empty();
  }
}
