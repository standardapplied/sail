/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.access.Resource;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.PrincipalHandle;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Decidability;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.Sqlite;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Who wrote what. Who made a spec, and so its own room, is what the journal's first revision of it
 * here records ({@link ChangeLog#bornBy}), an agent's own work: the one read of a maker, for every
 * rule that decides a spec, a room or a post. Whom a pushed revision may name is decided on the
 * {@link Actor.Lane#SYNC} lane only, where the actor is the pusher: main records a revision's
 * {@code _actor} as its author, and a create's creator as its owner while it is unassigned, and
 * hands both to every box. A revision may name as its author the pusher, this box's machinery
 * ({@link Actor#SYSTEM_HANDLE}), or a principal of a run the pusher owns; absent, main records the
 * pusher. A create may name only the pusher as its creator; absent, the creator is the pusher. A
 * principal naming a run main does not hold yet is refused by {@link Decidability}, never denied,
 * so the next round decides it once the run lands.
 */
final class Attribution {

  private final RunStore runs;
  private final ChangeLog journal;

  Attribution(Sqlite db) {
    this.runs = new RunStore(db);
    this.journal = new ChangeLog(db);
  }

  /**
   * {@code resource}, a spec or the conversation on a spec's id, as made by whoever made that spec;
   * by no one when the journal no longer keeps its first revision.
   */
  Resource made(Resource resource) {
    return Resources.madeBy(resource, journal.bornBy(resource.id()).orElse(null));
  }

  /**
   * Why pusher {@code actor} may not name the author and creator {@code next} carries. {@code
   * creatorKey} is where the type keeps a create's creator, null when it keeps none; {@code ownRun}
   * is the run whose own revision this is, null for any other type.
   */
  Optional<Refusal> decide(
      Actor actor,
      Map<String, Object> held,
      Map<String, Object> next,
      String creatorKey,
      String ownRun) {
    if (actor.lane() != Actor.Lane.SYNC || next == null) {
      return Optional.empty();
    }
    var author = Snapshots.text(next, Snapshots.ACTOR);
    if (author != null && !mayWriteAs(actor.handle(), author, ownRun)) {
      return Refusal.of(
          Refusal.Kind.NOT_AUTHOR,
          "'" + actor.handle() + "' may not write as '" + author + "'.",
          "A box writes as its FDE, its own machinery, or the runs acting for its FDE.");
    }
    var creator = creatorKey == null || held != null ? null : Snapshots.text(next, creatorKey);
    if (creator != null && !creator.equals(actor.handle())) {
      return Refusal.of(
          Refusal.Kind.NOT_AUTHOR,
          "'" + actor.handle() + "' may not create for '" + creator + "'.",
          "A box creates only as its own FDE.");
    }
    return Optional.empty();
  }

  private boolean mayWriteAs(String pusher, String author, String ownRun) {
    if (author.equals(pusher) || Actor.SYSTEM_HANDLE.equals(author)) {
      return true;
    }
    if (ownRun != null && PrincipalHandle.namesRun(author, ownRun)) {
      return true;
    }
    return principalOfOwnedRun(pusher, author);
  }

  /**
   * Whether {@code author} is a principal of a run {@code pusher} owns: a principal names its run
   * in the shape {@link RunStore#principalHandle} mints, so the run is read from the name itself.
   * Whether main holds the run at all is not decided here but by {@link Decidability}, which
   * refuses the offer until the run lands and denies it once the run is gone; this only weighs
   * ownership of a run main holds.
   */
  boolean principalOfOwnedRun(String pusher, String author) {
    return PrincipalHandle.runOf(author)
        .flatMap(runs::findById)
        .map(run -> Objects.equals(run.owner(), pusher))
        .orElse(false);
  }
}
