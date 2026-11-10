/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

import ai.singlr.sail.identity.Actor;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The Read rule asked of rows. Read is yes in every column of {@link Policy}, so whether an actor
 * reads a row is the audience step of {@link Access#decide} alone: the project the row is filed
 * under is in {@link Access#audience}. The project is asked only of a restricted reader: working it
 * out may cost a query, and no project hides a row from an unrestricted one, so a list read by one
 * adds no query per row. A read that filters many rows inside one query filters by the audience
 * itself. A conversation's messages have a second Read rule, which binds an agent alone: it hears
 * only the voices that steer it ({@link Policy#hearsOnlySteering}).
 */
public final class ReadFilter {

  private ReadFilter() {}

  /** Whether {@code actor} reads what is filed under the project {@code project} names. */
  public static boolean reads(Actor actor, Supplier<String> project) {
    var audience = Access.audience(actor);
    return audience.unrestricted() || audience.admits(project.get());
  }

  /** Whether {@code actor} reads a row, filed under the project {@code project} names of it. */
  public static <T> Predicate<T> readable(Actor actor, Function<? super T, String> project) {
    return row -> reads(actor, () -> project.apply(row));
  }

  /** The rows of {@code rows} {@code actor} reads, in their order. */
  public static <T> List<T> readable(
      Actor actor, List<T> rows, Function<? super T, String> project) {
    return rows.stream().filter(readable(actor, project)).toList();
  }

  /** {@code row} when {@code actor} reads it; empty if not. */
  public static <T> Optional<T> readable(
      Actor actor, Optional<T> row, Function<? super T, String> project) {
    return row.filter(readable(actor, project));
  }
}
