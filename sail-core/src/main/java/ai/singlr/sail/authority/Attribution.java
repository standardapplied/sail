/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncedStore;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Whom a pushed revision may name, decided on the {@link Actor.Lane#SYNC} lane only, where the
 * actor is the pusher: main records a revision's {@code _actor} as its author, and a create's
 * creator as its owner while it is unassigned, and hands both to every box. A revision may name as
 * its author the pusher, this box's machinery ({@link Actor#SYSTEM_HANDLE}), or a principal of a
 * run the pusher owns; absent, main records the pusher. A create may name only the pusher as its
 * creator; absent, the creator is the pusher. A principal naming a run main does not hold yet is
 * refused ({@link SyncedStore.Unheld}), never denied, so the next round decides it once the run
 * lands.
 */
final class Attribution {

  private final RunStore runs;

  Attribution(Sqlite db) {
    this.runs = new RunStore(db);
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
    if (ownRun != null && RunStore.namesRun(author, ownRun)) {
      return true;
    }
    return principalOfOwnedRun(pusher, author);
  }

  /**
   * Whether {@code author} is a principal of a run {@code pusher} owns: a principal names its run
   * in the shape {@link RunStore#principalHandle} mints, so the run is read from the name itself. A
   * principal naming a run main has never held is not decided but refused, so it is decided once
   * the run lands.
   */
  boolean principalOfOwnedRun(String pusher, String author) {
    var named = RunStore.runOf(author);
    if (named.isEmpty()) {
      return false;
    }
    var run = runs.findById(named.get());
    if (run.isPresent()) {
      return Objects.equals(run.get().owner(), pusher);
    }
    if (runs.latestRev(named.get()) == null) {
      throw new SyncedStore.Unheld(
          "main does not hold run '"
              + named.get()
              + "', which '"
              + author
              + "' names, yet; runs sync first, so the next round settles this");
    }
    return false;
  }
}
