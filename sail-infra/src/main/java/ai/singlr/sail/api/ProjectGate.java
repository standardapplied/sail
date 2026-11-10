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
public final class ProjectGate {

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
  public static ProjectStore.ProjectRow readable(ProjectStore catalog, String project) {
    var row = held(catalog, project);
    requireReads(Actor.reader(), project);
    return row;
  }

  /**
   * Refuses {@code project} when {@code reader} may not read it, answered as one this box does not
   * hold ({@link ProjectReader#absent}), so a caller cannot tell a project it was not granted from
   * one that does not exist.
   */
  public static void requireReads(Actor reader, String project) {
    if (!ReadFilter.reads(reader, () -> project)) {
      throw ProjectReader.absent(project);
    }
  }

  private static ProjectStore.ProjectRow held(ProjectStore catalog, String project) {
    return Objects.requireNonNull(catalog, "catalog")
        .findByName(project)
        .orElseThrow(() -> ProjectReader.absent(project));
  }
}
