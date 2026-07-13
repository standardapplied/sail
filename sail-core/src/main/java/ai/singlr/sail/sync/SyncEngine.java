/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.store.ConflictDetector;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Drives one sync round between a node ({@link LocalReplica}) and the authoritative {@link
 * MainReplica}, reconciling every entity through the pure {@link ConflictDetector}. Main is the
 * authority: a local-only change pushes (main mints the rev), a main-only change pulls, disjoint
 * edits auto-merge into a new authoritative rev both sides adopt, and a true same-field conflict is
 * parked locally with the node's row untouched.
 *
 * <p>Every push is a compare-and-set against the rev the node fetched, so two nodes syncing
 * concurrently are safe: if main moved under us the push is {@linkplain CommitOutcome.Rejected
 * rejected}, and the entity is re-reconciled against main's fresh state — auto-merging a disjoint
 * concurrent edit, conflicting on an overlapping one — never silently overwriting it. The retry is
 * bounded; under pathological churn the entity is parked as a conflict rather than looping.
 *
 * <p>The round is idempotent — a second run with no new changes converges everything and does
 * nothing — and order-independent across entities, because each entity reconciles against its own
 * merge base. Stateless: all state lives in the replicas, so a sync interrupted between entities
 * re-runs cleanly.
 */
public final class SyncEngine {

  private static final int MAX_REDETECTS = 3;
  private static final String STALE_FIELD = "<stale>";

  public record Report(int pulled, int pushed, int merged, int conflicts) {
    public int total() {
      return pulled + pushed + merged + conflicts;
    }
  }

  private enum Outcome {
    CONVERGED,
    PULLED,
    PUSHED,
    MERGED,
    CONFLICT
  }

  public Report reconcile(LocalReplica local, MainReplica main) {
    var ids = new LinkedHashSet<String>();
    ids.addAll(local.entityIds());
    ids.addAll(main.entityIds());

    var tally = new EnumMap<Outcome, Integer>(Outcome.class);
    for (var id : ids) {
      var outcome =
          reconcileEntity(local, main, id, main.current(id), main.currentRev(id), MAX_REDETECTS);
      tally.merge(outcome, 1, Integer::sum);
    }

    local.advanceCheckpoint(main.id(), main.maxSeq());
    return new Report(
        count(tally, Outcome.PULLED),
        count(tally, Outcome.PUSHED),
        count(tally, Outcome.MERGED),
        count(tally, Outcome.CONFLICT));
  }

  private static int count(EnumMap<Outcome, Integer> tally, Outcome outcome) {
    return tally.getOrDefault(outcome, 0);
  }

  private Outcome reconcileEntity(
      LocalReplica local,
      MainReplica main,
      String id,
      Map<String, Object> remoteSnap,
      String remoteRev,
      int redetectsLeft) {
    var base = local.base(id);
    var localSnap = local.current(id);
    var remoteBlocksResurrection = blockingTombstone(remoteSnap);
    if (remoteBlocksResurrection && base == null) {
      var changed = localSnap != null || !Objects.equals(local.currentRev(id), remoteRev);
      if (changed) {
        local.adopt(id, null, remoteRev);
      }
      return changed ? Outcome.PULLED : Outcome.CONVERGED;
    }
    if (remoteBlocksResurrection) {
      remoteSnap = null;
    }
    if (blockingTombstone(localSnap) && base == null) {
      return push(local, main, id, localSnap, remoteRev, Outcome.PUSHED, redetectsLeft);
    }
    return switch (ConflictDetector.detect(base, localSnap, remoteSnap)) {
      case ConflictDetector.Converged ignored -> {
        linkSharedRevision(local, id, remoteSnap, remoteRev);
        yield Outcome.CONVERGED;
      }
      case ConflictDetector.TakeRemote ignored -> {
        local.adopt(id, remoteSnap, remoteRev);
        yield Outcome.PULLED;
      }
      case ConflictDetector.KeepLocal ignored ->
          push(local, main, id, localSnap, remoteRev, Outcome.PUSHED, redetectsLeft);
      case ConflictDetector.Merged m ->
          push(local, main, id, m.result(), remoteRev, Outcome.MERGED, redetectsLeft);
      case ConflictDetector.Conflict c -> {
        local.recordConflict(id, base, localSnap, remoteSnap, c.fields());
        yield Outcome.CONFLICT;
      }
    };
  }

  private static boolean blockingTombstone(Map<String, Object> snapshot) {
    return snapshot != null && Boolean.TRUE.equals(snapshot.get("_blocks_resurrection"));
  }

  private Outcome push(
      LocalReplica local,
      MainReplica main,
      String id,
      Map<String, Object> snapshot,
      String expectedRev,
      Outcome onAccepted,
      int redetectsLeft) {
    return switch (main.commit(id, snapshot, expectedRev)) {
      case CommitOutcome.Accepted a -> {
        local.adopt(id, snapshot, a.rev());
        yield onAccepted;
      }
      case CommitOutcome.Rejected r -> {
        if (redetectsLeft <= 0) {
          yield recordStaleConflict(local, id, r);
        }
        yield reconcileEntity(
            local, main, id, r.currentSnapshot(), r.currentRev(), redetectsLeft - 1);
      }
    };
  }

  /**
   * Main kept moving under our retries: park the entity as a conflict against its latest state so
   * the user decides, naming the clashing fields when there are any.
   */
  private static Outcome recordStaleConflict(
      LocalReplica local, String id, CommitOutcome.Rejected rejected) {
    var base = local.base(id);
    var localSnap = local.current(id);
    var fields =
        ConflictDetector.detect(base, localSnap, rejected.currentSnapshot())
                instanceof ConflictDetector.Conflict c
            ? c.fields()
            : List.of(STALE_FIELD);
    local.recordConflict(id, base, localSnap, rejected.currentSnapshot(), fields);
    return Outcome.CONFLICT;
  }

  /**
   * When local and main already agree but local has not yet recorded main's revision (e.g. they
   * independently reached identical content), adopt main's rev so both share a merge base going
   * forward. A no-op once the revisions are linked, so it never churns.
   */
  private static void linkSharedRevision(
      LocalReplica local, String id, Map<String, Object> remoteSnap, String remoteRev) {
    if (remoteRev != null && !Objects.equals(local.currentRev(id), remoteRev)) {
      local.adopt(id, remoteSnap, remoteRev);
    }
  }
}
