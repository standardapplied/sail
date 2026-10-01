/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ConflictDetector;
import ai.singlr.sail.store.MainVersion;
import ai.singlr.sail.store.Snapshots;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Drives one sync round between a node ({@link LocalReplica}) and the authoritative {@link
 * MainReplica}, reconciling every entity through the pure {@link ConflictDetector}. Main is the
 * authority: a local-only change pushes (main mints the rev), a main-only change pulls, disjoint
 * edits auto-merge into a new authoritative rev both sides adopt, and a true same-field conflict is
 * parked locally with the node's row untouched. Work this box executes that is still live — a run
 * or review under way here — is never rewritten or removed by main's version, pulled or denied: it
 * stays as it is and is offered once it has finished.
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
    DENIED,
    HELD
  }

  public Report reconcile(LocalReplica local, MainReplica main) {
    return new Round(local, main).run();
  }

  /**
   * An offer awaiting main's verdict, with everything needed to settle it: {@code offeredFrom} is
   * the local row the offer was made from, at the revision the round captured it.
   */
  private record Pending(
      String id,
      Map<String, Object> snapshot,
      LocalReplica.Captured offeredFrom,
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
      if (outcome != Outcome.OFFERED && outcome != Outcome.DENIED && outcome != Outcome.HELD) {
        tally.merge(outcome, 1, Integer::sum);
      }
    }

    /**
     * Offers everything pending to main as one batch and settles each verdict in order. What each
     * offer was made from is recorded before main is asked and forgotten once its verdict is
     * settled, so an answer lost on the way back leaves exactly that behind to recover against.
     */
    private void commitPending() {
      var batch = List.copyOf(pending);
      pending.clear();
      pendingWeight = 0;
      batch.forEach(p -> local.offering(p.id(), p.snapshot(), p.offeredFrom().snapshot()));
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
        local.settled(batch.get(i).id());
      }
    }

    private int count(Outcome outcome) {
      return tally.getOrDefault(outcome, 0);
    }

    /**
     * Reconciles one entity three-way. A marked deletion ({@link Snapshots#isDeletionMark}) is a
     * deletion the detector reads as absence, except where the marks themselves are what has to
     * cross: main's, when this box never heard it ({@link #mainsMarksUnheard}), and this box's own,
     * still to reach main.
     */
    private Outcome reconcileEntity(
        String id, Map<String, Object> remote, String remoteRev, int redetectsLeft) {
      var base = local.base(id);
      var captured = local.capture(id);
      var localSnap = captured.snapshot();
      if (Snapshots.isDeletionMark(remote) && base == null) {
        return mainsMarksUnheard(id, captured, remote, remoteRev, redetectsLeft);
      }
      var remoteLive = Snapshots.isDeletionMark(remote) ? null : remote;
      if (Snapshots.isDeletionMark(localSnap)
          && Objects.equals(local.lastHeardRev(id), captured.rev())) {
        localSnap = null;
      }
      if (Snapshots.isDeletionMark(localSnap) && base == null) {
        if (remoteLive != null) {
          local.recordConflict(
              id,
              null,
              localSnap,
              mainsVersion(id, remote, remoteRev),
              List.of(ConflictDetector.DELETED_FIELD));
          return Outcome.CONFLICT;
        }
        return offer(id, localSnap, captured, remoteRev, Outcome.PUSHED, redetectsLeft);
      }
      var localRev = captured.rev();
      return switch (ConflictDetector.detect(
          base, localSnap, remoteLive, local.latestWinsFields())) {
        case ConflictDetector.Converged ignored ->
            converged(id, captured, localSnap, remote, remoteRev, redetectsLeft);
        case ConflictDetector.TakeRemote ignored ->
            take(id, localRev, remote, remoteRev, Outcome.PULLED, redetectsLeft);
        case ConflictDetector.KeepLocal ignored ->
            local.mayPush(id)
                ? offer(id, localSnap, captured, remoteRev, Outcome.PUSHED, redetectsLeft)
                : take(id, localRev, remote, remoteRev, Outcome.PULLED, redetectsLeft);
        case ConflictDetector.Merged m -> {
          if (rewritesLive(id, localSnap, m.result())) {
            yield Outcome.HELD;
          }
          yield local.mayPush(id)
              ? offer(id, m.result(), captured, remoteRev, Outcome.MERGED, redetectsLeft)
              : take(id, localRev, remote, remoteRev, Outcome.PULLED, redetectsLeft);
        }
        case ConflictDetector.Conflict c -> {
          local.recordConflict(
              id, base, localSnap, mainsVersion(id, remote, remoteRev), c.fields());
          yield Outcome.CONFLICT;
        }
      };
    }

    /**
     * Main holds a marked deletion of {@code id} and this box has no base for it: a copy written
     * over a deletion the box heard is a creation of this box's and is offered, main deciding it
     * against the deletion it holds; a copy that never heard one is stale and adopts the deletion;
     * the deletion itself, already held under the author main names, is converged.
     */
    private Outcome mainsMarksUnheard(
        String id,
        LocalReplica.Captured captured,
        Map<String, Object> remote,
        String remoteRev,
        int redetectsLeft) {
      var localSnap = captured.snapshot();
      if (Snapshots.isDeletionMark(localSnap)
          && Objects.equals(captured.rev(), remoteRev)
          && Objects.equals(local.author(id), mainsAuthor(id, remote))) {
        return Outcome.CONVERGED;
      }
      if (localSnap != null
          && !Snapshots.isDeletionMark(localSnap)
          && local.lastHeardRev(id) != null) {
        return offer(id, localSnap, captured, remoteRev, Outcome.PUSHED, redetectsLeft);
      }
      return take(id, captured.rev(), remote, remoteRev, Outcome.PULLED, redetectsLeft);
    }

    /**
     * Nothing changed on either side since the base. Equal revisions are converged only under one
     * author and one head: a box that adopted a revision under another release's reading of it
     * takes main's again. A box that minted main's revision itself, from the same content, never
     * recorded it as its base; it acknowledges main's as the base of what it holds ({@link
     * LocalReplica#acknowledge}, nothing when the base is already it), and is done offering it.
     */
    private Outcome converged(
        String id,
        LocalReplica.Captured captured,
        Map<String, Object> localSnap,
        Map<String, Object> remote,
        String remoteRev,
        int redetectsLeft) {
      if (remoteRev == null) {
        return Outcome.CONVERGED;
      }
      if (!Objects.equals(captured.rev(), remoteRev)
          || !Objects.equals(local.author(id), mainsAuthor(id, remote))) {
        return take(id, captured.rev(), remote, remoteRev, Outcome.CONVERGED, redetectsLeft);
      }
      if (localSnap != null) {
        Actor.run(
            Actor.main(mainsAuthor(id, remote)), () -> local.acknowledge(id, remote, remoteRev));
      }
      return Outcome.CONVERGED;
    }

    /**
     * Adopts main's version under the author main recorded, or {@linkplain #redetect re-reconciles}
     * when the local row moved, unless {@code id} is work still live here ({@link #liveHere}): then
     * the local row stays as it is, and it is offered once it has finished. Every path of the walk
     * that takes main's version comes through here.
     */
    private Outcome take(
        String id,
        String expectedLocalRev,
        Map<String, Object> snapshot,
        String rev,
        Outcome onAdopted,
        int redetectsLeft) {
      if (liveHere(id)) {
        return Outcome.HELD;
      }
      if (adopt(id, expectedLocalRev, snapshot, rev, main.author(id))) {
        return onAdopted;
      }
      return redetect(id, redetectsLeft);
    }

    /**
     * The one guard: main's version, by denial, by pull or inside a merge, never rewrites or
     * removes an entity this box executes that is still live ({@link LocalReplica#live}).
     */
    private boolean liveHere(String id) {
      return local.live(id);
    }

    /**
     * Whether adopting {@code merged} over {@code localSnap} would take any of main's changes into
     * work still live here ({@link #liveHere}). A merge that keeps the local row as it is — a
     * heartbeat whose later stamp is this box's — is offered like any local change.
     */
    private boolean rewritesLive(
        String id, Map<String, Object> localSnap, Map<String, Object> merged) {
      return liveHere(id) && !ConflictDetector.drift(localSnap, merged, Set.of()).isEmpty();
    }

    /**
     * The local row moved since the round captured it: a local write landing anywhere in the round
     * makes an adoption stale, and adopting anyway would overwrite (and, for aggregates, delete the
     * non-replicated children of) the newer local state. Instead the entity is re-reconciled
     * against main's fresh state, so the newer local work pushes, merges, or parks as a conflict —
     * never silently vanishes. The retry is bounded; past the budget the entity parks as a stale
     * conflict with the local row untouched.
     */
    private Outcome redetect(
        String id, Map<String, Object> remote, String remoteRev, int redetectsLeft) {
      return redetectsLeft <= 0
          ? recordStaleConflict(id, remote, remoteRev)
          : reconcileEntity(id, remote, remoteRev, redetectsLeft - 1);
    }

    private Outcome redetect(String id, int redetectsLeft) {
      return redetect(id, main.current(id), main.currentRev(id), redetectsLeft);
    }

    /**
     * Adopts main's version at {@code rev} if the local row still sits at {@code expectedLocalRev},
     * as main recording {@code author} — so a tombstone, which has no snapshot to name its author
     * in, records the same author on this box as on main.
     */
    private boolean adopt(
        String id,
        String expectedLocalRev,
        Map<String, Object> snapshot,
        String rev,
        String author) {
      return Actor.call(
          Actor.main(author), () -> local.adoptIfCurrent(id, expectedLocalRev, snapshot, rev));
    }

    /** Queues the snapshot for main's next batch; its verdict is settled when the batch answers. */
    private Outcome offer(
        String id,
        Map<String, Object> snapshot,
        LocalReplica.Captured offeredFrom,
        String expectedRev,
        Outcome onAccepted,
        int redetectsLeft) {
      pending.add(new Pending(id, snapshot, offeredFrom, expectedRev, onAccepted, redetectsLeft));
      pendingWeight += main.weigh(new MainReplica.Offer(id, snapshot, expectedRev));
      return Outcome.OFFERED;
    }

    /**
     * Applies main's verdict on one offer: adopt via the stale guard, or re-reconcile. A denial is
     * settled by adopting main's version, exactly as a pulled revision is and counted as one — the
     * offered revision stays in the change log and no conflict is parked. Work still live here and
     * a local write landing since the offer are left alone; the next round offers them, and main
     * decides them then.
     */
    private Outcome settle(Pending offer, CommitOutcome outcome) {
      return switch (outcome) {
        case CommitOutcome.Accepted a -> settleAccepted(offer, a);
        case CommitOutcome.Rejected r ->
            redetect(offer.id(), r.currentSnapshot(), r.currentRev(), offer.redetectsLeft());
        case CommitOutcome.Refused _ -> Outcome.HELD;
        case CommitOutcome.Denied d -> {
          if (d.gone()) {
            yield Outcome.HELD;
          }
          yield !liveHere(offer.id())
                  && adopt(offer.id(), offer.offeredFrom().rev(), d.snapshot(), d.rev(), d.author())
              ? Outcome.PULLED
              : Outcome.DENIED;
        }
      };
    }

    /**
     * Main's side of a conflict on {@code id}: {@code snapshot} at {@code rev}, under the author
     * main names beside it or, for a live revision, in it.
     */
    private MainVersion mainsVersion(String id, Map<String, Object> snapshot, String rev) {
      return new MainVersion(snapshot, rev, mainsAuthor(id, snapshot));
    }

    /**
     * The author main records for its version of {@code id}: named beside it or, for a live
     * revision, in {@code snapshot}.
     */
    private String mainsAuthor(String id, Map<String, Object> snapshot) {
      var author = main.author(id);
      return author != null || snapshot == null
          ? author
          : Snapshots.text(snapshot, Snapshots.ACTOR);
    }

    /**
     * Settles main taking this box's offer: adopts the version main took, or, when a local write
     * landed since the offer, records it as the row's merge base with the edits made since the
     * offer rebased on top ({@link LocalReplica#acknowledge}) before the entity is reconciled
     * again, so this box's own accepted change never reads as a competing edit of main's, and
     * main's changes the offer merged in never read as this box reverting them.
     */
    private Outcome settleAccepted(Pending offer, CommitOutcome.Accepted accepted) {
      var id = offer.id();
      var taken = Snapshots.withCreator(offer.snapshot(), accepted.creator());
      if (adopt(id, offer.offeredFrom().rev(), taken, accepted.rev(), accepted.author())) {
        return offer.onAccepted();
      }
      Actor.run(Actor.main(accepted.author()), () -> local.acknowledge(id, taken, accepted.rev()));
      return redetect(id, offer.redetectsLeft());
    }

    /**
     * Main kept moving under our retries: park the entity as a conflict against its latest state,
     * {@code remoteSnap} at {@code remoteRev}, so the user decides, naming the clashing fields when
     * there are any.
     */
    private Outcome recordStaleConflict(
        String id, Map<String, Object> remoteSnap, String remoteRev) {
      var base = local.base(id);
      var localSnap = local.current(id);
      var fields =
          ConflictDetector.detect(base, localSnap, remoteSnap, local.latestWinsFields())
                  instanceof ConflictDetector.Conflict c
              ? c.fields()
              : List.of(STALE_FIELD);
      local.recordConflict(id, base, localSnap, mainsVersion(id, remoteSnap, remoteRev), fields);
      return Outcome.CONFLICT;
    }
  }
}
