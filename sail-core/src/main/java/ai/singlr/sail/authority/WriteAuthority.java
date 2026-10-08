/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.Sqlite;
import java.util.Map;
import java.util.Optional;

/**
 * Who may write one synced type: the one rule the journal asks as it records every write of this
 * box's own, and main's commit asks for every revision a node pushes, so the same edit gets the
 * same answer wherever it is made. No door asks it.
 *
 * <p>Both sides are the type's synced projection, the comparable snapshot. {@code held} is what
 * this box holds at the journal's head, null for a create; over a tombstone (a restore) it is the
 * last live projection. {@code next} is the revision being written, null for a tombstone, or a
 * tombstone's marks. An authority may read this box's database for owners, and never writes; the
 * journal asks it after the row is written, so whatever it derives from the row being decided is
 * read from {@code held}, never from the database, and a revision never admits itself. {@link
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

  /**
   * Why the bound {@link Actor} may not write {@code next} over {@code held} for {@code id} as this
   * box's own, by {@code rule}; empty if it may. The one decision of every write that is not a
   * push: a pushed revision ({@link Actor.Lane#SYNC}) was decided at main's commit and is not
   * decided again.
   */
  static Optional<Refusal> local(
      WriteAuthority rule, String id, Map<String, Object> held, Map<String, Object> next) {
    var actor = Actor.current();
    return actor.lane() == Actor.Lane.SYNC ? Optional.empty() : rule.decide(actor, id, held, next);
  }

  /**
   * Refuses the write {@code refusal} decides against, dooming the transaction it runs in on {@code
   * db} ({@link Sqlite#doomed}); a no-op when the rule allowed.
   *
   * @throws WriteRefused when the rule refused
   */
  static void admit(Sqlite db, Optional<Refusal> refusal) {
    if (refusal.isPresent()) {
      throw db.doomed(new WriteRefused(refusal.get()));
    }
  }
}
