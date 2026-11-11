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
 * The projects an actor may see: every project, or exactly the ones main granted its FDE. It is the
 * value the audience is decided from, never asked by a door itself: a row in hand is read through
 * {@code ReadFilter}, and a query filters by {@code Access.audience}, so the sync server's pages,
 * the journal's writes and the API's reads ask one audience and no door keeps a list of projects of
 * its own. Main decides it from its roster ({@link #of(Sqlite, String)}); a node only ever holds
 * what main sent. {@code projects} is in name order, and empty for an unrestricted scope.
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
