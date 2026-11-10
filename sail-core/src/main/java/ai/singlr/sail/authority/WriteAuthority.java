/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Decision;
import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.access.Resource;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.Sqlite;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * read from {@code held}, never from the database, and a revision never admits itself. Who may take
 * the actions a revision takes is {@link Access}'s to answer; a rule names them and keeps the facts
 * only it knows. Sail itself is never asked ({@link Actor#isSail}): main has decided, or this box's
 * machinery is writing.
 */
@FunctionalInterface
public interface WriteAuthority {

  /**
   * Why {@code actor} may not write {@code next} over {@code held} for {@code id}; empty if it may.
   */
  Optional<Refusal> decide(
      Actor actor, String id, Map<String, Object> held, Map<String, Object> next);

  /**
   * Why the bound {@link Actor} may not write {@code next} over {@code held} for {@code id} as this
   * box's own, by {@code rule}; empty if it may. The one decision of every write that is not a
   * push: a pushed revision ({@link Actor.Lane#SYNC}) was decided at main's commit and is not
   * decided again, and Sail itself was decided already.
   */
  static Optional<Refusal> local(
      WriteAuthority rule, String id, Map<String, Object> held, Map<String, Object> next) {
    var actor = Actor.current();
    return actor.lane() == Actor.Lane.SYNC || actor.isSail()
        ? Optional.empty()
        : rule.decide(actor, id, held, next);
  }

  /**
   * What a revision takes: every one of {@code actions}, each of which must be allowed, on {@code
   * resource}, the row it is decided on.
   */
  record Taking(Resource resource, List<Action> actions) {

    public Taking {
      Objects.requireNonNull(resource, "resource");
      actions = List.copyOf(actions);
    }

    /** {@code actions} taken on {@code resource}. */
    public static Taking of(Resource resource, Action... actions) {
      return new Taking(resource, List.of(actions));
    }
  }

  /** What a revision of {@code id} from {@code held} to {@code next} takes. */
  @FunctionalInterface
  interface Takes {
    Taking of(String id, Map<String, Object> held, Map<String, Object> next);
  }

  /**
   * The rule of a type the permission table decides alone: a revision is refused unless {@link
   * Access} allows every action {@code takes} names for it, and a push names only the authors its
   * pusher may write as, on {@code db}'s copy.
   */
  static WriteAuthority table(Sqlite db, Takes takes) {
    var attribution = new Attribution(db);
    return (actor, id, held, next) ->
        refused(actor, takes.of(id, held, next))
            .or(() -> attribution.decide(actor, held, next, null, null));
  }

  /**
   * Why {@code actor} may not take what {@code taking} names: the first refusal {@link Access}
   * answers, empty when it allows every action. A row outside the actor's audience is refused as
   * one in no project it is granted, as the scope rule words a write it finds outside ({@link
   * ScopeRule}), which the journal asks first.
   */
  static Optional<Refusal> refused(Actor actor, Taking taking) {
    var resource = taking.resource();
    for (var action : taking.actions()) {
      switch (Access.decide(actor, action, resource)) {
        case Decision.Allowed ignored -> {}
        case Decision.NotFound ignored -> {
          return ScopeRule.unplaced(resource.named());
        }
        case Decision.Refused refused -> {
          return Optional.of(refused.refusal());
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Whether a revision from {@code held} to {@code next} names no row: a deletion ({@link
   * Snapshots#isDeletion}) of an id this box holds no head of, which is filed nowhere, so no
   * resource is there to decide it on ({@link #unnamed}).
   */
  static boolean namesNothing(Map<String, Object> held, Map<String, Object> next) {
    return held == null && Snapshots.isDeletion(next);
  }

  /**
   * Why {@code actor} may not write a revision of {@code kind} that names no row ({@link
   * #namesNothing}): its role and lane alone decide, as a create of the kind ({@link
   * Access#decide(Actor, Action, Resource.Kind)}).
   */
  static Optional<Refusal> unnamed(Actor actor, Resource.Kind kind) {
    return Access.decide(actor, Action.CREATE, kind) instanceof Decision.Refused refused
        ? Optional.of(refused.refusal())
        : Optional.empty();
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
