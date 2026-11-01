/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.ProjectDefinition.Section;
import ai.singlr.sail.config.ProjectDefinitionJson;
import ai.singlr.sail.engine.CliOperator;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ProjectCatalog;
import ai.singlr.sail.identity.Actor;
import java.io.PrintStream;

/**
 * Shared read/write seam for the commands that change one section of a project's definition (add a
 * repo or service, remove a service, change resources). Each reads the definition from the catalog,
 * checks its edit whole before it touches the container, and writes the one section it changed,
 * refused if another write changed that section since it was read.
 */
final class ProjectMutations {

  private ProjectMutations() {}

  /** The definition project {@code name} holds, or a refusal naming how to make one. */
  static ProjectDefinition current(String name, OperatorCommand operator) {
    return ProjectCatalog.read(name).orElseThrow(() -> notFound(name, operator));
  }

  /**
   * This box's operator, resolved before a command changes anything, for the catalog write of
   * project {@code name} that ends it: a node that cannot name its operator, or whose operator the
   * journal will refuse that write, refuses up front, never after cloning, starting or editing.
   * Null for a dry run, which touches no catalog.
   */
  static Actor catalogOperator(String name, boolean dryRun) {
    if (dryRun) {
      return null;
    }
    var operator = CliOperator.current();
    ProjectCatalog.requireRecordable(name, operator);
    return operator;
  }

  /** {@code edited} checked whole, before any side effect it would follow. */
  static ProjectDefinition checked(ProjectDefinition edited) {
    return ProjectDefinitionJson.validated(edited);
  }

  /**
   * Writes {@code section} of {@code read}'s project as {@code edited} holds it, as {@code
   * operator} (see {@link #catalogOperator}), or prints the intent under {@code --dry-run}.
   */
  static void write(
      Section section,
      ProjectDefinition read,
      ProjectDefinition edited,
      boolean dryRun,
      PrintStream out,
      String dryRunLabel,
      Actor operator) {
    if (dryRun) {
      out.println("[dry-run] " + dryRunLabel);
      return;
    }
    ProjectCatalog.writeSection(read.name(), section, read, edited, operator);
  }

  static IllegalStateException notFound(String name, OperatorCommand operator) {
    return new IllegalStateException(
        "No project '"
            + name
            + "' in the catalog. Create it with '"
            + operator.sail("project create " + name)
            + "', or sync it from main with '"
            + operator.sail("sync")
            + "'.");
  }
}
