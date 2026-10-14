/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.identity;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * The projects an actor may see: every project, or exactly the ones main granted its FDE. {@link
 * #admits} is the one predicate every door asks, the sync server's pages, the journal's writes and
 * the API's reads alike, so no door keeps a list of projects of its own. Main decides it from its
 * roster ({@link #of(Sqlite, String)}); a node only ever holds what main sent. {@code projects} is
 * in name order, and empty for an unrestricted scope.
 */
public record ProjectScope(boolean unrestricted, Set<String> projects) {

  private static final ProjectScope ALL = new ProjectScope(true, Set.of());
  private static final ProjectScope NONE = new ProjectScope(false, Set.of());

  public ProjectScope {
    projects = unrestricted ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(projects));
  }

  /** Every project: what an FDE sees unless main scoped it. */
  public static ProjectScope all() {
    return ALL;
  }

  /** No project at all: a scoped FDE with no grant, and a session that names no FDE. */
  public static ProjectScope none() {
    return NONE;
  }

  /** Exactly {@code projects}. */
  public static ProjectScope of(Collection<String> projects) {
    return projects.isEmpty() ? NONE : new ProjectScope(false, Set.copyOf(projects));
  }

  /**
   * Whether a row of {@code project} is this scope's to see. A row of no known project is only ever
   * an unrestricted scope's.
   */
  public boolean admits(String project) {
    return unrestricted || (project != null && projects.contains(project));
  }
}
