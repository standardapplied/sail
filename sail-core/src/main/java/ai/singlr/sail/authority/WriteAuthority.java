/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.identity.Actor;
import java.util.Map;
import java.util.Optional;

/**
 * Who may write one synced type: the one rule every door that writes it asks where it decides, and
 * main's commit asks for every revision a node pushes, so the same edit gets the same answer
 * wherever it is made.
 *
 * <p>Both sides are the type's synced projection, the comparable snapshot. {@code held} is what
 * this box holds, null for a create; over a tombstone (a restore) it is the last live projection.
 * {@code next} is the revision being written, null for a tombstone. An authority may read this
 * box's database for owners, and never writes; an owner derived from the row being decided is read
 * from {@code held}, never from the database, so a revision never admits itself. {@link
 * Actor.Lane#MAIN} and {@link Actor.Lane#SYSTEM} always pass: main has decided, or this box's
 * machinery is writing.
 */
@FunctionalInterface
public interface WriteAuthority {

  /**
   * Why {@code actor} may not write {@code next} over {@code held} for {@code id}; empty if it may.
   */
  Optional<Refusal> decide(
      Actor actor, String id, Map<String, Object> held, Map<String, Object> next);

  /** Whether {@code actor} has already been decided for: main, or this box's own machinery. */
  static boolean decided(Actor actor) {
    return actor.lane() == Actor.Lane.MAIN || actor.lane() == Actor.Lane.SYSTEM;
  }
}
