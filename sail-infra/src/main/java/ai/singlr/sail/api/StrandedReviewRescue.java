/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.RunStatus;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.MissedStops;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** The missed-stop sweep's rescue of a review owed something nothing is coming to give it. */
final class StrandedReviewRescue {

  /** The sweep's own reading of whether a run is gone, and its publishing of a run's stop. */
  @FunctionalInterface
  interface Gone {
    boolean test(RunStore.RunRow run) throws Exception;
  }

  @FunctionalInterface
  interface StopPublisher {
    void publish(RunStore.RunRow run, Integer exitCode, String why);
  }

  private final SpecStore specStore;
  private final RunStore sessionStore;
  private final LoopFactsReader loop;
  private final Supplier<String> localHandle;
  private final Supplier<Instant> clock;
  private final Duration launchGrace;
  private final Gone gone;
  private final StopPublisher publishStop;
  private final Set<String> rescued = ConcurrentHashMap.newKeySet();

  StrandedReviewRescue(
      SpecStore specStore,
      RunStore sessionStore,
      ReviewStore reviewStore,
      Supplier<String> localHandle,
      Supplier<Instant> clock,
      Duration launchGrace,
      Gone gone,
      StopPublisher publishStop) {
    this.specStore = specStore;
    this.sessionStore = sessionStore;
    this.loop =
        new LoopFactsReader(
            specStore, reviewStore, sessionStore, project -> null, project -> null, localHandle);
    this.localHandle = localHandle;
    this.clock = clock;
    this.launchGrace = launchGrace;
    this.gone = gone;
    this.publishStop = publishStop;
  }

  /**
   * Rescues every spec stranded in its review loop, skipping any whose stop was already replayed
   * earlier in this same sweep. Without the skip a spec that the in-progress pass just reconciled —
   * whose replayed stop drives it {@code in_progress → review} on an async subscriber thread —
   * would be seen in {@code review} by this pass and have its stop replayed a second time,
   * double-handling the one run. The sweep is thereby internally consistent regardless of when the
   * async transition lands.
   */
  int rescueStrandedReviews(Set<String> handledThisSweep) {
    var rescued = 0;
    for (var spec : inReviewLoop()) {
      if (handledThisSweep.contains(spec.id())) {
        continue;
      }
      try {
        if (rescueStrandedReview(spec)) {
          handledThisSweep.add(spec.id());
          rescued++;
        }
      } catch (Exception e) {
        System.err.println(
            "  [reconcile] review rescue failed for "
                + spec.project()
                + "/"
                + spec.id()
                + ": "
                + e.getMessage());
      }
    }
    return rescued;
  }

  /**
   * The specs whose review loop may be stranded: every spec in {@code review}, and every {@code
   * in_progress} spec whose newest loop run already served a review — one in its fix phase, where
   * the build's stop was acted on long ago and only the review's rows say what is owed.
   */
  private List<SpecStore.SpecRow> inReviewLoop() {
    return Stream.concat(
            in(SpecStatus.REVIEW).stream(),
            in(SpecStatus.IN_PROGRESS).stream()
                .filter(
                    spec ->
                        sessionStore
                            .latestLoopRun(spec.id())
                            .filter(RunStore.RunRow::servesReview)
                            .isPresent()))
        .toList();
  }

  private List<SpecStore.SpecRow> in(SpecStatus status) {
    return specStore.list(new SpecStore.SpecFilter(null, status.wire(), null, null, null));
  }

  /**
   * Rescues a spec stranded in its review loop, in any of the shapes that leave it parked with
   * nothing coming to move it. <em>Dropped kickoff</em>: an out-of-band status write (a manual
   * edit, or a sync revision from another box) moved the spec to {@code review} while its agent was
   * still running here, so the authoritative stop hit the pipeline's guard against a non-{@code
   * in_progress} spec and no review was created. The rest are what the review's rows say it is owed
   * ({@link LoopFacts#owed}, the reading the pipeline itself acts on). <em>Errored review</em>: its
   * last attempt failed by infrastructure (a reviewer the watcher killed, unparseable output, a
   * launch the container refused) — the design retries an errored attempt on the next stop, and no
   * further stop is coming. <em>Unserved review</em>: a review is {@code running} with an agent
   * stage to run, no run serving it and no wait recorded — the daemon died between writing its rows
   * and its reviewer's claim. <em>Owed fix</em>: a review failed its gate and neither a fix agent
   * nor a wait was ever recorded for it. <em>Waiting review</em>: the gate refused the review's
   * launch, the review recorded the run that held the claim, and that run has ended with no stop on
   * the bus to wake the review — finished in place, a foreground session completing, a launch that
   * failed after reserving. <em>Unheard stop</em>: the reviewer of a review's running stage, or the
   * fix agent answering its gate failure, ended and nothing came of it — that run's stop was never
   * acted on. Replaying the stop of the spec's newest loop run, whichever lane it ran in, lets the
   * pipeline kick off, retry, go on from the stage rows, launch the fix, or judge the run's work.
   *
   * <p>Every rescue is one-shot: keyed by the spec for a dropped kickoff, and by the review row and
   * its shape otherwise — a running review per stage, a waiting one per run it waits on — so a
   * rescue that leaves the same review owed something else is still rescued, and a rescue whose
   * launch is refused again is rescued again only for the new run it then waits on. Nothing is
   * sampled and nothing is retried: a wait is a recorded fact, and with the pipeline's
   * errored-attempt budget escalating a persistent failure, no shape can loop. A rescue counts only
   * once its stop is published. An escalated review, or one waiting on a person or on a live run,
   * is left alone, since a human or a working agent owns the spec then.
   */
  private boolean rescueStrandedReview(SpecStore.SpecRow spec) throws Exception {
    var rescue = rescueFor(spec).orElse(null);
    if (rescue == null || rescued.contains(rescue.key())) {
      return false;
    }
    var node = localHandle.get();
    var latest =
        sessionStore
            .latestLoopRun(spec.id())
            .filter(run -> run.ownedBy(node))
            .filter(run -> RunStatus.isTerminal(run.status()));
    if (latest.isEmpty() || !gone.test(latest.get())) {
      return false;
    }
    publishStop.publish(latest.get(), latest.get().exitCode(), rescue.why());
    rescued.add(rescue.key());
    return true;
  }

  /** A stranded review's rescue: the key it is spent under, and why it is owed. */
  private record Rescue(String key, String why) {

    static Rescue of(ReviewStore.ReviewRow review, String shape, String why) {
      return new Rescue(review.id() + ":" + shape, "review " + review.id() + " " + why);
    }
  }

  private Optional<Rescue> rescueFor(SpecStore.SpecRow spec) {
    var facts = loop.read(spec.project(), spec.id());
    if (facts.review().isEmpty()) {
      return Optional.of(
          new Rescue(
              spec.id(),
              "stranded in review with no review started; replaying the stop to kick it off"));
    }
    return switch (facts.owed()) {
      case LoopFacts.Owed.Retry retry ->
          Optional.of(
              Rescue.of(
                  retry.review(),
                  "errored",
                  "errored ("
                      + retry.review().error()
                      + "); replaying the stop to retry the iteration"));
      case LoopFacts.Owed.Advance advance ->
          settled(facts, advance.review())
              .map(
                  review ->
                      Rescue.of(
                          review,
                          "unserved:" + passedStages(facts),
                          "is running with no run serving it; replaying the stop to go on from"
                              + " its stages"));
      case LoopFacts.Owed.Fix fix ->
          settled(facts, fix.review())
              .map(
                  review ->
                      Rescue.of(
                          review,
                          "fix-owed",
                          "failed its gate and no fix agent was launched; replaying the stop to"
                              + " launch it"));
      case LoopFacts.Owed.Waiting waiting ->
          Optional.of(waiting.review())
              .filter(review -> endedAWhileAgo(review.waitingOn()))
              .map(
                  review ->
                      Rescue.of(
                          review,
                          "waiting:" + review.waitingOn(),
                          "waited on run "
                              + review.waitingOn()
                              + ", which ended with no stop; replaying the stop to take the step"
                              + " it held up"));
      case LoopFacts.Owed.Stop unheard ->
          settled(facts, unheard.review())
              .map(
                  review ->
                      Rescue.of(
                          review,
                          "stop-unheard",
                          "waits on run "
                              + unheard.run().id()
                              + ", which ended; replaying that stop"));
      case LoopFacts.Owed.Nothing nothing -> Optional.empty();
    };
  }

  /**
   * How many of a running review's stages have passed: a review is rescued per stage, so one
   * rescued at a stage is not out of rescues at the next. The count holds still while the stage's
   * rows are written, as an id would not.
   */
  private static long passedStages(LoopFacts facts) {
    return facts.stages().stream().takeWhile(stage -> "passed".equals(stage.status())).count();
  }

  /**
   * Whether the run a review waits on has ended and its own stop, had it published one, is past:
   * its row is gone, or terminal for longer than the launch grace. A holder that only just ended
   * may still have its stop on the way, and that stop is what wakes the review.
   */
  private boolean endedAWhileAgo(String holderRunId) {
    var cutoff = clock.get().minus(launchGrace);
    return loop.ended(holderRunId)
        && sessionStore
            .findById(holderRunId)
            .map(holder -> MissedStops.parseOr(holder.completedAt(), Instant.MAX).isBefore(cutoff))
            .orElse(true);
  }

  /**
   * {@code review}, once nothing is running for it and nothing is about to: every run that served
   * it finished longer ago than the launch grace — a run that only just ended has a stop the
   * pipeline is still acting on — or, with no run recorded yet, the review itself is older than
   * that, so a sweep landing between its rows and its first launch leaves it alone.
   */
  private Optional<ReviewStore.ReviewRow> settled(LoopFacts facts, ReviewStore.ReviewRow review) {
    var cutoff = clock.get().minus(launchGrace);
    var serving = facts.serving();
    var settled =
        serving.isEmpty()
            ? MissedStops.parseOr(review.createdAt(), Instant.MAX).isBefore(cutoff)
            : serving.stream()
                .allMatch(
                    run ->
                        RunStatus.isTerminal(run.status())
                            && MissedStops.parseOr(run.completedAt(), Instant.MAX)
                                .isBefore(cutoff));
    return settled ? Optional.of(review) : Optional.empty();
  }
}
