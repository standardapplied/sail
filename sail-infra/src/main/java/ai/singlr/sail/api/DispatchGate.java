/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.List;
import java.util.Optional;

/**
 * The pure dispatch-concurrency decision: a new dispatch is refused only when its target repo set
 * overlaps a repo some already-running local run of the same project is working in. Disjoint repo
 * sets share the container safely — every run has its own systemd unit, files, log, and watcher —
 * so a sail-repo agent no longer blocks mast-repo work for hours.
 *
 * <p>An empty repo set means the run (or the target) works the whole container — a project with no
 * {@code repos} block resolves to no specific repo — and therefore overlaps everything, which keeps
 * single-repo and repo-less projects refusing concurrent dispatches exactly as before.
 *
 * <p>The decision consults run rows only: liveness is the reconciler's job, and a row whose unit
 * already died is healed within a sweep interval, so the gate never shells out on the dispatch hot
 * path.
 */
public final class DispatchGate {

  private DispatchGate() {}

  /** One running local run of the project: its id, the spec it works, and that spec's repos. */
  public record RunningRun(String runId, String specId, List<String> repos) {}

  /**
   * The blocking run and the repos both sides claim; empty {@code overlap} means one side works the
   * whole container.
   */
  public record Conflict(RunningRun run, List<String> overlap) {}

  /** The first running run whose repo set overlaps {@code targetRepos}, or empty to allow. */
  public static Optional<Conflict> decide(List<String> targetRepos, List<RunningRun> running) {
    return running.stream()
        .map(run -> conflictWith(targetRepos, run))
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
