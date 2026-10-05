/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.RunStore;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Keeps every running agent guarded: for each local {@code running} run — a build, an ad-hoc run, a
 * chat turn, a reviewer, a fix agent; the run table is the one session model — whose recorded agent
 * unit is still active but which no live watcher covers, this relaunches the watcher — at daemon
 * start and then periodically, so a watcher that dies mid-run (crash, OOM kill) leaves the agent
 * unguarded for at most one pass interval. The relaunched {@code sail agent watch} is handed the
 * run row's {@code started_at}, the anchor its first watcher had, so an agent three hours into a
 * four-hour budget gets the remaining hour, not a fresh four. Only sessions this node executed are
 * considered — a synced foreign run is its executing node's to guard, and arming a local watcher
 * against it would eventually enforce a foreign deadline on this box's container.
 *
 * <p>Coverage is probed, not bookkept ({@link WatcherCoverage}): re-arming a run a watcher this
 * pass could not address still covers would double it, and its guardrail actions would fire twice.
 * The agent probe is deliberately systemd-strict ({@link #systemdUnitActiveProbe}): a watcher
 * supervises a unit, and only background sessions run as one — a foreground session writes the same
 * run-scoped pid file but its blocking launcher owns its lifecycle, so a pid-file-based probe would
 * arm guardrails over a session that was never meant to have them. Relaunching is unit-or-nothing
 * ({@link WatcherSpawner#spawnUnitForRun}); where systemd is unavailable the relaunch is empty and
 * the missed-stop sweep still replays the stop when the agent ends. A session whose agent unit is
 * already dead is that sweep's job, not this one's, and a legacy row with no recorded unit has
 * nothing to supervise.
 */
public final class WatcherRearmer implements AutoCloseable {

  /** Re-arm cadence, matching the missed-stop sweep: unguarded time is bounded by one interval. */
  public static final Duration DEFAULT_INTERVAL = MissedStopReconciler.DEFAULT_INTERVAL;

  /** Relaunches a run's watcher as a unit; empty when neither systemd scope accepts it. */
  @FunctionalInterface
  public interface WatcherRelauncher {
    Optional<WatcherSpawner.Unit> relaunch(RunStore.RunRow run) throws Exception;
  }

  private final RunStore sessionStore;
  private final MissedStopReconciler.UnitProbe agentUnitActive;
  private final WatcherCoverage coverage;
  private final Supplier<String> localHandle;
  private final WatcherRelauncher relauncher;
  private final PeriodicPass pass;

  public WatcherRearmer(
      RunStore sessionStore,
      MissedStopReconciler.UnitProbe agentUnitActive,
      WatcherCoverage coverage,
      Supplier<String> localHandle,
      WatcherRelauncher relauncher) {
    this.sessionStore = sessionStore;
    this.agentUnitActive = agentUnitActive;
    this.coverage = coverage;
    this.localHandle = localHandle;
    this.relauncher = relauncher;
    this.pass = new PeriodicPass("rearm", this::rearm);
  }

  /**
   * The production agent probe: active only when the run's recorded unit is live in the container's
   * user systemd manager, never through the pid file — the discriminator that keeps a live
   * foreground session from being armed with a watcher it was never launched with.
   */
  public static MissedStopReconciler.UnitProbe systemdUnitActiveProbe(ShellExec shell) {
    var session = new AgentSession(shell);
    return (project, runId, unit) -> session.unitActive(project, AgentUnit.recorded(runId, unit));
  }

  /** Starts the periodic re-arm at the default cadence. */
  public void start() {
    start(DEFAULT_INTERVAL);
  }

  public void start(Duration interval) {
    pass.start(interval);
  }

  /** Runs one pass unless another is still in flight. Returns whether the pass ran. */
  boolean rearmIfIdle() {
    return pass.runIfIdle();
  }

  /**
   * Runs one re-arm pass and returns how many watchers were relaunched. Best-effort: a failing run
   * is logged and skipped so one broken session cannot leave the rest unwatched, and a store error
   * is logged and swallowed so re-arming can never block server startup.
   */
  public int rearm() {
    var rearmed = 0;
    try {
      var node = localHandle.get();
      for (var run : sessionStore.running()) {
        if (!run.ownedBy(node) || Strings.isBlank(run.unit())) {
          continue;
        }
        try {
          rearmed += rearm(run) ? 1 : 0;
        } catch (Exception e) {
          System.err.println(
              "  [rearm] failed for "
                  + run.project()
                  + " session "
                  + run.id()
                  + ": "
                  + e.getMessage());
        }
      }
    } catch (Exception e) {
      System.err.println("  [rearm] watcher re-arm aborted: " + e.getMessage());
    }
    return rearmed;
  }

  private boolean rearm(RunStore.RunRow session) throws Exception {
    if (coverage.covers(session)) {
      return false;
    }
    if (!agentUnitActive.active(session.project(), session.id(), session.unit())) {
      return false;
    }
    var unit = relauncher.relaunch(session);
    if (unit.isEmpty()) {
      System.err.println(
          "  [rearm] "
              + describe(session)
              + ": agent is running unwatched but no watcher unit could be launched (no agent"
              + " block or no systemd scope); the missed-stop sweep still replays its stop when"
              + " the agent ends");
      return false;
    }
    System.err.println(
        "  [rearm] re-armed watcher for "
            + describe(session)
            + ": "
            + (unit.get().adopted()
                ? "adopted already-active unit " + unit.get().name()
                : "launched unit "
                    + unit.get().name()
                    + " ("
                    + unit.get().scope()
                    + " scope), resuming the original deadline from the run's started_at"));
    return true;
  }

  private static String describe(RunStore.RunRow session) {
    var work = Strings.isBlank(session.specId()) ? "ad-hoc" : "spec " + session.specId();
    return session.project() + " session " + session.id() + " (" + work + ")";
  }

  @Override
  public void close() {
    pass.close();
  }
}
