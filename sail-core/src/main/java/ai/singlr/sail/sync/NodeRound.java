/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Sqlite;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a node does first in every round, once main has welcomed it, before it offers or adopts
 * anything. Main accepts a run only from the box that executed it, acting for that box's FDE, so a
 * node first agrees with main on who it is, then stamps as its own every run main has not taken.
 */
public final class NodeRound {

  private NodeRound() {}

  /**
   * Begins a round over {@code session} for the node whose database is {@code db} and whose
   * configured sync handle is {@code handle}. It fails, before anything is offered or adopted,
   * unless main authenticated the session as that handle; a main that predates saying which handle
   * it authenticated is not asked. Then every run main has never acknowledged is settled ({@link
   * #acknowledgeHeld}), every one main does not hold is stamped as this box's ({@link
   * #stampUnheld}), and so is every run of this box's that acts for no one, which an older release
   * reserved and main would never take.
   */
  public static void begin(SyncSession session, Sqlite db, String handle) {
    requireAgreed(session.handle(), handle);
    stampUnheld(db, handle, acknowledgeHeld(session, db));
    var runs = new RunStore(db);
    runs.stamp(handle, runs.ownerless(handle));
  }

  /**
   * Asks main which of the runs this box made that it never heard acknowledged main holds — its
   * answer lost on the way back — and adopts the version main took from this box as each one's base
   * ({@link RunStore#acknowledge}), keeping the run as it stands here. Nothing is offered, and
   * nothing but main's acknowledgement is adopted. Returns the ids main holds.
   */
  public static Set<String> acknowledgeHeld(SyncSession session, Sqlite db) {
    var runs = new RunStore(db);
    var unacknowledged = runs.unacknowledged();
    if (unacknowledged.isEmpty()) {
      return Set.of();
    }
    var answer = session.held(runs.entityType(), unacknowledged);
    for (var entry : answer.accepted()) {
      Actor.run(
          Actor.main(entry.author()),
          () -> runs.acknowledge(entry.id(), entry.snapshot(), entry.rev()));
    }
    var held = new LinkedHashSet<String>();
    answer.current().forEach(entry -> held.add(entry.id()));
    return Collections.unmodifiableSet(held);
  }

  /**
   * Stamps as the box whose FDE handle is {@code handle} executes them ({@link RunStore#stamp})
   * every run this box made that main has never acknowledged and does not hold — {@code held} is
   * what main said it holds ({@link #acknowledgeHeld}) — so main takes each from this box. Returns
   * the ids stamped.
   */
  public static List<String> stampUnheld(Sqlite db, String handle, Set<String> held) {
    var runs = new RunStore(db);
    return runs.stamp(
        handle, runs.unacknowledged().stream().filter(id -> !held.contains(id)).toList());
  }

  /**
   * Fails naming both handles and the fix unless {@code configured} is the handle main
   * authenticated the session as, when main said.
   */
  static void requireAgreed(Optional<String> authenticated, String configured) {
    if (authenticated.isEmpty()
        || (Strings.isNotBlank(configured) && configured.equals(authenticated.get()))) {
      return;
    }
    var main = authenticated.get();
    var mine =
        Strings.isBlank(configured)
            ? "this box has no sync handle"
            : "this box's sync handle is '" + configured + "'";
    throw new SyncTransportException(
        "refused",
        (Strings.isBlank(main)
                ? "main authenticated this session as no FDE"
                : "main knows this box as FDE '" + main + "'")
            + ", but "
            + mine
            + ", so this round offers and adopts nothing. Set the sync handle to the one main knows"
            + (Strings.isBlank(main)
                ? ""
                : " ('sudo sail host config set sync-handle " + main + "')")
            + ", or have an admin tie this box's sync key to "
            + (Strings.isBlank(configured) ? "its FDE" : "FDE '" + configured + "'")
            + " on main.",
        null);
  }
}
