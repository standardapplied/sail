/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * What a read hands an actor of what it found: the rows of the projects the actor's scope admits
 * ({@link ProjectScope#admits}), and nothing of any other. A row out of scope is a row that is not
 * there, so a read never refuses it: a list is shorter, and a row asked for by id is empty, which
 * the read answers with the not-found it already gives an id nobody holds. A read can therefore
 * never tell an actor that a project it may not see exists.
 */
public final class Scoped {

  private Scoped() {}

  /**
   * The rows of {@code rows} whose {@code project} the actor's scope admits. An unrestricted actor
   * is handed {@code rows} itself, and {@code project} is never asked.
   */
  public static <T> List<T> rows(Actor actor, List<T> rows, Function<? super T, String> project) {
    var scope = actor.scope();
    return scope.unrestricted()
        ? rows
        : rows.stream().filter(row -> scope.admits(project.apply(row))).toList();
  }

  /** {@code row} when the actor's scope admits its {@code project}, and empty when it does not. */
  public static <T> Optional<T> row(
      Actor actor, Optional<T> row, Function<? super T, String> project) {
    return row.filter(found -> actor.scope().admits(project.apply(found)));
  }
}
