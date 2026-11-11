/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.ReadFilter;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ProjectStore;
import java.util.Objects;

/**
 * The one gate of a door that acts on a project: the row of the project in this box's catalog, when
 * the bound actor may act on it. One the catalog does not hold, or the actor may not read, is
 * {@link ProjectReader#absent}, so a caller cannot tell a project it was not granted from one that
 * does not exist; a refusal is its exception.
 */
final class ProjectGate {

  private ProjectGate() {}

  /**
   * The row of {@code project} in {@code catalog}, when the bound actor may take {@code action} on
   * it, its creator's as the row names.
   */
  static ProjectStore.ProjectRow taking(ProjectStore catalog, Action action, String project) {
    var row = held(catalog, project);
    Refusals.require(
        Access.decide(Actor.current(), action, Resources.project(project, row.createdBy())),
        () -> ProjectReader.absent(project));
    return row;
  }

  /** The row of {@code project} in {@code catalog}, when whoever reads may read it. */
  static ProjectStore.ProjectRow readable(ProjectStore catalog, String project) {
    var row = held(catalog, project);
    requireReads(project);
    return row;
  }

  /**
   * Refuses {@code project} when whoever reads may not read it, answered as one this box does not
   * hold ({@link ProjectReader#absent}), so a caller cannot tell a project it was not granted from
   * one that does not exist. A door with no catalog in hand reads the project first this way, so
   * nothing it finds in the project, a run's id included, is named to a caller outside it.
   */
  static void requireReads(String project) {
    if (!ReadFilter.reads(Actor.reader(), () -> project)) {
      throw ProjectReader.absent(project);
    }
  }

  private static ProjectStore.ProjectRow held(ProjectStore catalog, String project) {
    return Objects.requireNonNull(catalog, "catalog")
        .findByName(project)
        .orElseThrow(() -> ProjectReader.absent(project));
  }
}
