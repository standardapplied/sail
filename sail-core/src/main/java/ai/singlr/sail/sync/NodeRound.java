/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Sqlite;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * What a node does first in every round, once main has welcomed it, before it offers or adopts
 * anything. Main accepts a run only from the box that executed it, acting for that box's FDE, so a
 * node first agrees with main on who it is, then stamps as its own every run main has not taken.
 */
public final class NodeRound {

  private static final String RUN = "run";

  private NodeRound() {}

  /**
   * Begins a round over {@code session} for the node whose database is {@code db} and whose
   * configured sync handle is {@code handle}. It fails, before anything is offered or adopted,
   * unless main authenticated the session as that handle; a main that predates saying which handle
   * it authenticated is not asked. Then every run main has never acknowledged is settled ({@link
   * #acknowledgeHeld}), and every one main does not hold is stamped as this box's ({@link
   * RunStore#stamp}).
   */
  public static void begin(SyncSession session, Sqlite db, String handle) {
    requireAgreed(session.handle(), handle);
    new RunStore(db).stamp(handle, acknowledgeHeld(session, db));
  }

  /**
   * Asks main which of the runs it has never acknowledged here it holds — its answer lost on the
   * way back — and adopts each as acknowledged, keeping the run as it stands here. Nothing is
   * offered, and nothing but main's acknowledgement is adopted. Returns the rest: the runs main
   * does not hold, in the order they were written.
   */
  public static Set<String> acknowledgeHeld(SyncSession session, Sqlite db) {
    var runs = new RunStore(db);
    var unacknowledged = runs.unacknowledged();
    if (unacknowledged.isEmpty()) {
      return Set.of();
    }
    var unheld = new LinkedHashSet<>(unacknowledged);
    for (var entry : session.held(RUN, unacknowledged)) {
      unheld.remove(entry.id());
      if (entry.kind() == ChangeLog.Kind.REVISION) {
        Actor.run(
            Actor.main(entry.author()),
            () -> runs.acknowledge(entry.id(), entry.snapshot(), entry.rev()));
      }
    }
    return unheld;
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
