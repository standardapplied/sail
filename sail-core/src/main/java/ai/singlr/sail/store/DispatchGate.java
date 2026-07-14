/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.util.List;
import java.util.Optional;

/**
 * The pure dispatch-concurrency decision: a new dispatch is refused when its spec already has a
 * running local run, or when its target repo set overlaps a repo some already-running local run of
 * the same project is working in. Disjoint repo sets of distinct specs share the container safely —
 * every run has its own systemd unit, files, log, and watcher — so a sail-repo agent no longer
 * blocks mast-repo work for hours.
 *
 * <p>An empty repo set means the run (or the target) works the whole container — a project with no
 * {@code repos} block resolves to no specific repo — and therefore overlaps everything, which keeps
 * single-repo and repo-less projects refusing concurrent dispatches exactly as before.
 *
 * <p>The decision consults run rows only: liveness is the reconciler's job, and a row whose unit
 * already died is healed within a sweep interval, so the gate never consults systemd. The binding
 * evaluation runs inside {@link RunStore#reserveDispatch}'s {@code BEGIN IMMEDIATE} transaction,
 * which checks and inserts atomically so two concurrent dispatches — even from separate processes —
 * can never both pass; callers outside that transaction use it only for advisory pre-checks.
 */
public final class DispatchGate {

  private DispatchGate() {}

  /** One running local run of the project: its id, the spec it works, and its reserved repos. */
  public record RunningRun(String runId, String specId, List<String> repos) {}

  /**
   * The blocking run and the repos both sides claim; empty {@code overlap} means one side works the
   * whole container.
   */
  public record Conflict(RunningRun run, List<String> overlap) {}

  /**
   * The first running run that blocks the dispatch, or empty to allow. A run of {@code
   * targetSpecId} itself always blocks, even on disjoint repos: a spec has one lifecycle and one
   * review pipeline, so a second live execution — reachable via restart with a repo override —
   * would race the first over shared spec state. Any other run blocks only on repo overlap.
   */
  public static Optional<Conflict> decide(
      String targetSpecId, List<String> targetRepos, List<RunningRun> running) {
    return running.stream()
        .map(
            run ->
                run.specId().equals(targetSpecId)
                    ? Optional.of(new Conflict(run, List.of()))
                    : conflictWith(targetRepos, run))
        .flatMap(Optional::stream)
        .findFirst();
  }

  private static Optional<Conflict> conflictWith(List<String> targetRepos, RunningRun run) {
    if (targetRepos.isEmpty() || run.repos().isEmpty()) {
      return Optional.of(new Conflict(run, List.of()));
    }
    var overlap = targetRepos.stream().filter(run.repos()::contains).toList();
    return overlap.isEmpty() ? Optional.empty() : Optional.of(new Conflict(run, overlap));
  }
}
