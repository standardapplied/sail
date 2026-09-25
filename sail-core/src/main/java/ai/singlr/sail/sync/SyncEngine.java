/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.store.ConflictDetector;
import ai.singlr.sail.store.ProjectStore;
import java.util.ArrayList;
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
 * <p>Pushes are gathered while the round walks its entities and offered to main together through
 * {@link MainReplica#commitAll} — as soon as what is pending weighs {@link MainReplica#offerBudget}
 * or at the end of the walk, whichever comes first, so a first upload of a large table never holds
 * more than one batch of snapshots — then each answer is settled exactly as a single commit's would
 * be; a rejection's re-reconcile may offer again, into the next batch, until the bounded retries
 * are spent. Nothing about an entity's outcome depends on the batching — only on how many round
 * trips a remote main costs — because every adoption is guarded by the rev it was decided against.
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
    public static final Report NONE = new Report(0, 0, 0, 0);

    public int total() {
      return pulled + pushed + merged + conflicts;
    }

    /** Sums two reports into one. */
    public Report plus(Report other) {
      return new Report(
          pulled + other.pulled,
          pushed + other.pushed,
          merged + other.merged,
          conflicts + other.conflicts);
    }
  }

  private enum Outcome {
    CONVERGED,
    PULLED,
    PUSHED,
    MERGED,
    CONFLICT,
    OFFERED,
    DENIED
  }

  public Report reconcile(LocalReplica local, MainReplica main) {
    return new Round(local, main).run();
  }

  /** An offer awaiting main's verdict, with everything needed to settle it. */
  private record Pending(
      String id,
      Map<String, Object> snapshot,
      String offeredLocalRev,
      String expectedRev,
      Outcome onAccepted,
      int redetectsLeft) {}

  private static final class Round {
    private final LocalReplica local;
    private final MainReplica main;
    private final EnumMap<Outcome, Integer> tally = new EnumMap<>(Outcome.class);
    private final List<Pending> pending = new ArrayList<>();
    private long pendingWeight;

    Round(LocalReplica local, MainReplica main) {
      this.local = local;
      this.main = main;
    }

    Report run() {
      var ids = new LinkedHashSet<String>();
      ids.addAll(local.entityIds());
      ids.addAll(main.entityIds());
      for (var id : ids) {
        record(reconcileEntity(id, main.current(id), main.currentRev(id), MAX_REDETECTS));
        if (pendingWeight >= main.offerBudget()) {
          commitPending();
        }
      }
      while (!pending.isEmpty()) {
        commitPending();
      }
      local.advanceCheckpoint(main.id(), main.maxSeq());
      return new Report(
          count(Outcome.PULLED),
          count(Outcome.PUSHED),
          count(Outcome.MERGED),
          count(Outcome.CONFLICT));
    }

    private void record(Outcome outcome) {
      if (outcome != Outcome.OFFERED && outcome != Outcome.DENIED) {
        tally.merge(outcome, 1, Integer::sum);
      }
    }

    /** Offers everything pending to main as one batch and settles each verdict in order. */
    private void commitPending() {
      var batch = List.copyOf(pending);
      pending.clear();
      pendingWeight = 0;
      var outcomes =
          main.commitAll(
              batch.stream()
                  .map(p -> new MainReplica.Offer(p.id(), p.snapshot(), p.expectedRev()))
                  .toList());
      if (outcomes.size() != batch.size()) {
        throw new IllegalStateException(
            "main answered " + outcomes.size() + " outcomes for " + batch.size() + " offers");
      }
      for (var i = 0; i < batch.size(); i++) {
        record(settle(batch.get(i), outcomes.get(i)));
      }
    }

    private int count(Outcome outcome) {
      return tally.getOrDefault(outcome, 0);
    }

    private Outcome reconcileEntity(
        String id, Map<String, Object> remoteSnap, String remoteRev, int redetectsLeft) {
      var base = local.base(id);
      var captured = local.capture(id);
      var localSnap = captured.snapshot();
      var localRev = captured.rev();
      if (ProjectStore.isBlocksResurrectionMarker(remoteSnap)) {
        if (base == null) {
          if (localSnap == null && Objects.equals(localRev, remoteRev)) {
            return Outcome.CONVERGED;
          }
          return adoptOrRedetect(id, localRev, null, remoteRev, Outcome.PULLED, redetectsLeft);
        }
        remoteSnap = null;
      }
      if (ProjectStore.isBlocksResurrectionMarker(localSnap) && base == null) {
        if (remoteSnap != null) {
          local.recordConflict(
              id, null, localSnap, remoteSnap, List.of(ConflictDetector.DELETED_FIELD));
          return Outcome.CONFLICT;
        }
        return offer(id, localSnap, localRev, remoteRev, Outcome.PUSHED, redetectsLeft);
      }
      return switch (ConflictDetector.detect(
          base, localSnap, remoteSnap, local.latestWinsFields())) {
        case ConflictDetector.Converged ignored ->
            remoteRev == null || Objects.equals(localRev, remoteRev)
                ? Outcome.CONVERGED
                : adoptOrRedetect(
                    id, localRev, remoteSnap, remoteRev, Outcome.CONVERGED, redetectsLeft);
        case ConflictDetector.TakeRemote ignored ->
            adoptOrRedetect(id, localRev, remoteSnap, remoteRev, Outcome.PULLED, redetectsLeft);
        case ConflictDetector.KeepLocal ignored ->
            local.mayPush(id)
                ? offer(id, localSnap, localRev, remoteRev, Outcome.PUSHED, redetectsLeft)
                : adoptOrRedetect(
                    id, localRev, remoteSnap, remoteRev, Outcome.PULLED, redetectsLeft);
        case ConflictDetector.Merged m ->
            local.mayPush(id)
                ? offer(id, m.result(), localRev, remoteRev, Outcome.MERGED, redetectsLeft)
                : adoptOrRedetect(
                    id, localRev, remoteSnap, remoteRev, Outcome.PULLED, redetectsLeft);
        case ConflictDetector.Conflict c -> {
          local.recordConflict(id, base, localSnap, remoteSnap, c.fields());
          yield Outcome.CONFLICT;
        }
      };
    }

    /**
     * Adopts an authoritative state, but only if the local row still sits at the revision the
     * round's snapshot was captured from — check and adoption are one atomic replica operation. A
     * local write landing anywhere in the round makes the adoption stale; adopting anyway would
     * overwrite (and, for aggregates, delete the non-replicated children of) the newer local state.
     * Instead the entity is re-reconciled against main's fresh state, so the newer local work
     * pushes, merges, or parks as a conflict — never silently vanishes. The retry is bounded; past
     * the budget the entity parks as a stale conflict with the local row untouched.
     */
    private Outcome adoptOrRedetect(
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
          ? recordStaleConflict(id, main.current(id))
          : reconcileEntity(id, main.current(id), main.currentRev(id), redetectsLeft - 1);
    }

    /** Queues the snapshot for main's next batch; its verdict is settled when the batch answers. */
    private Outcome offer(
        String id,
        Map<String, Object> snapshot,
        String offeredLocalRev,
        String expectedRev,
        Outcome onAccepted,
        int redetectsLeft) {
      pending.add(
          new Pending(id, snapshot, offeredLocalRev, expectedRev, onAccepted, redetectsLeft));
      pendingWeight += main.weigh(new MainReplica.Offer(id, snapshot, expectedRev));
      return Outcome.OFFERED;
    }

    /**
     * Applies main's verdict on one offer: adopt via the stale guard, or re-reconcile. A denial is
     * settled by adopting main's version, exactly as a pulled revision is — the offered revision
     * stays in the change log and no conflict is parked. A local write landing since the offer is
     * left alone; the next round offers it, and main decides it then.
     */
    private Outcome settle(Pending offer, CommitOutcome outcome) {
      return switch (outcome) {
        case CommitOutcome.Accepted a ->
            adoptOrRedetect(
                offer.id(),
                offer.offeredLocalRev(),
                offer.snapshot(),
                a.rev(),
                offer.onAccepted(),
                offer.redetectsLeft());
        case CommitOutcome.Rejected r ->
            offer.redetectsLeft() <= 0
                ? recordStaleConflict(offer.id(), r.currentSnapshot())
                : reconcileEntity(
                    offer.id(), r.currentSnapshot(), r.currentRev(), offer.redetectsLeft() - 1);
        case CommitOutcome.Denied d -> {
          local.adoptIfCurrent(offer.id(), offer.offeredLocalRev(), d.snapshot(), d.rev());
          yield Outcome.DENIED;
        }
      };
    }

    /**
     * Main kept moving under our retries: park the entity as a conflict against its latest state so
     * the user decides, naming the clashing fields when there are any.
     */
    private Outcome recordStaleConflict(String id, Map<String, Object> remoteSnap) {
      var base = local.base(id);
      var localSnap = local.current(id);
      var fields =
          ConflictDetector.detect(base, localSnap, remoteSnap, local.latestWinsFields())
                  instanceof ConflictDetector.Conflict c
              ? c.fields()
              : List.of(STALE_FIELD);
      local.recordConflict(id, base, localSnap, remoteSnap, fields);
      return Outcome.CONFLICT;
    }
  }
}
