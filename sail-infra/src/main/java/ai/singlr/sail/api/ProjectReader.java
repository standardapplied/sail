/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.ReadFilter;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ProjectStore;
import java.util.Optional;

/**
 * Where everything that runs a project reads its definition: the catalog row, read as stored, so a
 * revision that reaches the catalog, by sync or by a command, is what the next read sees.
 */
@FunctionalInterface
public interface ProjectReader {

  /** The project's definition, or empty when this box holds no definition of it. */
  Optional<ProjectDefinition> read(String project);

  /** The project's definition, or a not-found error saying how to get one. */
  default ProjectDefinition require(String project) {
    return read(project).orElseThrow(() -> absent(project));
  }

  /**
   * The row of {@code project} in {@code catalog}, when the bound actor may take {@code action} on
   * it, its creator's as the row names: the one gate of a door that acts on a project. One the
   * catalog does not hold, or the actor may not read, is {@link #absent}; a refusal is its
   * exception. A box that keeps no catalog ({@code catalog} null) has no row to hand back, and
   * decides on the project by name. A read is {@link #readable}.
   */
  static Optional<ProjectStore.ProjectRow> require(
      ProjectStore catalog, Action action, String project) {
    var row = held(catalog, project);
    Refusals.require(
        Access.decide(
            Actor.current(),
            action,
            Resources.project(project, row.map(ProjectStore.ProjectRow::createdBy).orElse(null))),
        () -> absent(project));
    return row;
  }

  /**
   * The row of {@code project} in {@code catalog}, as {@link #require} finds it, when whoever reads
   * may read it ({@link ReadFilter}); one the reader may not read is {@link #absent}.
   */
  static Optional<ProjectStore.ProjectRow> readable(ProjectStore catalog, String project) {
    var row = held(catalog, project);
    if (!ReadFilter.reads(Actor.reader(), () -> project)) {
      throw absent(project);
    }
    return row;
  }

  private static Optional<ProjectStore.ProjectRow> held(ProjectStore catalog, String project) {
    if (catalog == null) {
      return Optional.empty();
    }
    return Optional.of(catalog.findByName(project).orElseThrow(() -> absent(project)));
  }

  /** The not-found error for a project this box holds no definition of. */
  static ApiException absent(String project) {
    return new ApiException(
        ErrorCode.PROJECT_NOT_IN_CATALOG,
        "Project '" + project + "' is not in the catalog.",
        Remedy.of(
            "Create it with '",
            Remedy.sail("project create", project),
            "', or sync it from main with '",
            Remedy.sail("sync"),
            "'."));
  }

  /**
   * The reader over the catalog: a row is parsed as stored, one that does not parse is {@link
   * Unreadable}, and a failure of the store itself passes through unwrapped. A null store holds no
   * project.
   */
  static ProjectReader ofCatalog(ProjectStore store) {
    if (store == null) {
      return project -> Optional.empty();
    }
    return project -> store.findByName(project).map(row -> parse(project, row));
  }

  private static ProjectDefinition parse(String project, ProjectStore.ProjectRow row) {
    try {
      return row.definition();
    } catch (RuntimeException e) {
      throw new Unreadable(project, e);
    }
  }

  /** A catalog row that holds a definition which cannot be read as one, with its cause's remedy. */
  final class Unreadable extends RuntimeException implements Remedied {
    private final String reason;
    private final transient Remedy remedy;

    Unreadable(String project, Throwable cause) {
      this(
          Remedied.joined(
              "The definition of project '" + project + "' in the catalog could not be read:",
              Remedied.reasonOf(cause)),
          Remedied.remedyOf(cause),
          cause);
    }

    private Unreadable(String reason, Remedy remedy, Throwable cause) {
      super(Remedied.message(reason, remedy), cause);
      this.reason = reason;
      this.remedy = remedy;
    }

    @Override
    public String reason() {
      return reason;
    }

    @Override
    public Remedy remedy() {
      return remedy;
    }
  }
}
