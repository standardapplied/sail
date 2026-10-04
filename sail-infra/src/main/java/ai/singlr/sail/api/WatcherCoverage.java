/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.RunStore;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

/**
 * Whether a live watcher covers a run — the one answer the watcher re-armer and the missed-stop
 * reconciler share. A covered run needs no second watcher, and its end is its watcher's to report:
 * the watcher alone holds the exit code or the limit it ended the run for.
 *
 * <p>Coverage is probed, not bookkept, and probed at the process level: a recorded watcher pid that
 * is still alive (a free, in-process check) or any {@code sail agent watch} process for the run
 * ({@link WatcherSpawner#watcherProcessRunningForRun}, which sees every systemd scope, every user's
 * manager, and plain fallback processes alike) means covered. Unit-name probes alone would be blind
 * to a watcher armed in another user's manager.
 *
 * @param watcherRunning whether any watcher process for a run id is running on this host
 * @param watcherAlive whether the host process of a recorded watcher pid is alive
 */
public record WatcherCoverage(Predicate<String> watcherRunning, LongPredicate watcherAlive) {

  /** Coverage as this host shows it: its process table, through {@code spawner}'s shell. */
  public static WatcherCoverage of(WatcherSpawner spawner) {
    return new WatcherCoverage(
        spawner::watcherProcessRunningForRun,
        pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
  }

  /** Whether a live watcher covers {@code run}. */
  public boolean covers(RunStore.RunRow run) {
    return (run.watcherPid() != null && watcherAlive.test(run.watcherPid())) || watching(run);
  }

  /**
   * Whether a watcher process for {@code run} is running right now, by the process table alone.
   * What the reconciler asks before it speaks for a run that is gone: a recorded pid may since have
   * been handed to another process, and a run left to a watcher that is not there would never end.
   */
  public boolean watching(RunStore.RunRow run) {
    return watcherRunning.test(run.id());
  }
}
