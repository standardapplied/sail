/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.store.ConflictDetector;
import ai.singlr.sail.store.ProjectStore;
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
    var captured = local.capture(id);
    var localSnap = captured.snapshot();
    var localRev = captured.rev();
    if (ProjectStore.isBlocksResurrectionMarker(remoteSnap)) {
      if (base == null) {
        if (localSnap == null && Objects.equals(localRev, remoteRev)) {
          return Outcome.CONVERGED;
        }
        return adoptOrRedetect(
            local, main, id, localRev, null, remoteRev, Outcome.PULLED, redetectsLeft);
      }
      remoteSnap = null;
    }
    if (ProjectStore.isBlocksResurrectionMarker(localSnap) && base == null) {
      if (remoteSnap != null) {
        local.recordConflict(
            id, null, localSnap, remoteSnap, List.of(ConflictDetector.DELETED_FIELD));
        return Outcome.CONFLICT;
      }
      return push(local, main, id, localSnap, localRev, remoteRev, Outcome.PUSHED, redetectsLeft);
    }
    return switch (ConflictDetector.detect(base, localSnap, remoteSnap)) {
      case ConflictDetector.Converged ignored ->
          remoteRev == null || Objects.equals(localRev, remoteRev)
              ? Outcome.CONVERGED
              : adoptOrRedetect(
                  local,
                  main,
                  id,
                  localRev,
                  remoteSnap,
                  remoteRev,
                  Outcome.CONVERGED,
                  redetectsLeft);
      case ConflictDetector.TakeRemote ignored ->
          adoptOrRedetect(
              local, main, id, localRev, remoteSnap, remoteRev, Outcome.PULLED, redetectsLeft);
      case ConflictDetector.KeepLocal ignored ->
          local.mayPush(id)
              ? push(local, main, id, localSnap, localRev, remoteRev, Outcome.PUSHED, redetectsLeft)
              : adoptOrRedetect(
                  local, main, id, localRev, remoteSnap, remoteRev, Outcome.PULLED, redetectsLeft);
      case ConflictDetector.Merged m ->
          local.mayPush(id)
              ? push(
                  local, main, id, m.result(), localRev, remoteRev, Outcome.MERGED, redetectsLeft)
              : adoptOrRedetect(
                  local, main, id, localRev, remoteSnap, remoteRev, Outcome.PULLED, redetectsLeft);
      case ConflictDetector.Conflict c -> {
        local.recordConflict(id, base, localSnap, remoteSnap, c.fields());
        yield Outcome.CONFLICT;
      }
    };
  }

  /**
   * Adopts an authoritative state, but only if the local row still sits at the revision the round's
   * snapshot was captured from — check and adoption are one atomic replica operation. A local write
   * landing anywhere in the round makes the adoption stale; adopting anyway would overwrite (and,
   * for aggregates, delete the non-replicated children of) the newer local state. Instead the
   * entity is re-reconciled against main's fresh state, so the newer local work pushes, merges, or
   * parks as a conflict — never silently vanishes. The retry is bounded; past the budget the entity
   * parks as a stale conflict with the local row untouched.
   */
  private Outcome adoptOrRedetect(
      LocalReplica local,
      MainReplica main,
      String id,
      String expectedLocalRev,
      Map<String, Object> snapshot,
      String rev,
      Outcome onAdopted,
      int redetectsLeft) {
    if (local.adoptIfCurrent(id, expectedLocalRev, snapshot, rev)) {
      return onAdopted;
    }
    return redetectsLeft <= 0
        ? recordStaleConflict(local, id, main.current(id))
        : reconcileEntity(
            local, main, id, main.current(id), main.currentRev(id), redetectsLeft - 1);
  }

  /** Commits the offered snapshot to main and, on acceptance, adopts it via the stale guard. */
  private Outcome push(
      LocalReplica local,
      MainReplica main,
      String id,
      Map<String, Object> snapshot,
      String offeredLocalRev,
      String expectedRev,
      Outcome onAccepted,
      int redetectsLeft) {
    return switch (main.commit(id, snapshot, expectedRev)) {
      case CommitOutcome.Accepted a ->
          adoptOrRedetect(
              local, main, id, offeredLocalRev, snapshot, a.rev(), onAccepted, redetectsLeft);
      case CommitOutcome.Rejected r -> {
        if (redetectsLeft <= 0) {
          yield recordStaleConflict(local, id, r.currentSnapshot());
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
      LocalReplica local, String id, Map<String, Object> remoteSnap) {
    var base = local.base(id);
    var localSnap = local.current(id);
    var fields =
        ConflictDetector.detect(base, localSnap, remoteSnap) instanceof ConflictDetector.Conflict c
            ? c.fields()
            : List.of(STALE_FIELD);
    local.recordConflict(id, base, localSnap, remoteSnap, fields);
    return Outcome.CONFLICT;
  }
}
