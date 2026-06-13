/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.store.ConflictDetector;
import java.util.LinkedHashSet;
import java.util.Objects;

/**
 * Drives one sync round between a node ({@link LocalReplica}) and the authoritative {@link
 * MainReplica}, reconciling every entity through the pure {@link ConflictDetector}. Main is the
 * authority: a local-only change pushes (main mints the rev), a main-only change pulls, disjoint
 * edits auto-merge into a new authoritative rev both sides adopt, and a true same-field conflict is
 * parked locally with the node's row untouched.
 *
 * <p>The round is idempotent — a second run with no new changes converges everything and does
 * nothing — and order-independent across entities, because each entity reconciles against its own
 * merge base. Stateless: all state lives in the replicas, so a sync interrupted between entities
 * re-runs cleanly.
 */
public final class SyncEngine {

  public record Report(int pulled, int pushed, int merged, int conflicts) {
    public int total() {
      return pulled + pushed + merged + conflicts;
    }
  }

  public Report reconcile(LocalReplica local, MainReplica main) {
    var ids = new LinkedHashSet<String>();
    ids.addAll(local.entityIds());
    ids.addAll(main.entityIds());

    var pulled = 0;
    var pushed = 0;
    var merged = 0;
    var conflicts = 0;

    for (var id : ids) {
      var base = local.base(id);
      var localSnap = local.current(id);
      var remoteSnap = main.current(id);

      switch (ConflictDetector.detect(base, localSnap, remoteSnap)) {
        case ConflictDetector.Converged ignored -> linkSharedRevision(local, main, id, remoteSnap);
        case ConflictDetector.TakeRemote ignored -> {
          local.adopt(id, remoteSnap, main.currentRev(id));
          pulled++;
        }
        case ConflictDetector.KeepLocal ignored -> {
          local.adopt(id, localSnap, main.commit(id, localSnap));
          pushed++;
        }
        case ConflictDetector.Merged m -> {
          local.adopt(id, m.result(), main.commit(id, m.result()));
          merged++;
        }
        case ConflictDetector.Conflict c -> {
          local.recordConflict(id, base, localSnap, remoteSnap, c.fields());
          conflicts++;
        }
      }
    }

    local.advanceCheckpoint(main.id(), main.maxSeq());
    return new Report(pulled, pushed, merged, conflicts);
  }

  /**
   * When local and main already agree but local has not yet recorded main's revision (e.g. they
   * independently reached identical content), adopt main's rev so both share a merge base going
   * forward. A no-op once the revisions are linked, so it never churns.
   */
  private static void linkSharedRevision(
      LocalReplica local, MainReplica main, String id, java.util.Map<String, Object> remoteSnap) {
    var mainRev = main.currentRev(id);
    if (mainRev != null && !Objects.equals(local.currentRev(id), mainRev)) {
      local.adopt(id, remoteSnap, mainRev);
    }
  }
}
