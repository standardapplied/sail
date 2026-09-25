/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.CliOperator;
import ai.singlr.sail.engine.ProjectDefinitions;
import ai.singlr.sail.identity.Actor;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Shared read/write seam for the commands that mutate one field of a project definition (add a repo
 * or service, remove a service, change resources). They all read the current definition
 * catalog-first and persist the result back to the catalog, so an edit can never read a stale
 * on-disk copy or be silently dropped on the next sync. Centralizing it keeps every mutation on the
 * same database-authoritative path as {@code project edit}.
 */
final class ProjectMutations {

  private ProjectMutations() {}

  /** The current definition text, catalog-first; throws with guidance if the project is unknown. */
  static String currentDefinition(String name, Path explicitFile) {
    return ProjectDefinitions.definition(name, explicitFile).orElseThrow(() -> notFound(name));
  }

  /**
   * This box's operator, resolved before a command changes anything, for the catalog write that
   * ends it: a node that cannot name its operator refuses up front, never after cloning, starting
   * or editing. Null for a dry run or an explicit {@code -f} file, neither of which touches the
   * catalog.
   */
  static Actor catalogOperator(Path explicitFile, boolean dryRun) {
    return dryRun || explicitFile != null ? null : CliOperator.current();
  }

  /**
   * Persists an edited definition through the catalog seam as {@code operator} (see {@link
   * #catalogOperator}), or prints the intent under {@code --dry-run}. {@code dryRunLabel} describes
   * the change for the dry-run line.
   */
  static void persist(
      String name,
      Path explicitFile,
      String definition,
      boolean dryRun,
      PrintStream out,
      String dryRunLabel,
      Actor operator)
      throws IOException {
    if (dryRun) {
      out.println("[dry-run] " + dryRunLabel);
      return;
    }
    ProjectDefinitions.persist(name, explicitFile, definition, operator);
  }

  static IllegalStateException notFound(String name) {
    return new IllegalStateException(
        "No project '"
            + name
            + "' in the catalog. Create it with 'sail project apply', or sync it from main with"
            + " 'sail sync'.");
  }
}
