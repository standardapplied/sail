/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.time.Duration;
import java.util.Optional;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Keeps every running agent guarded: for each {@code in_progress} spec whose latest session is
 * still {@code running} and whose agent unit is still active, but which no live watcher covers,
 * this relaunches the watcher — at daemon start and then periodically, so a watcher that dies
 * mid-run (crash, OOM kill) leaves the agent unguarded for at most one pass interval. The
 * relaunched {@code sail agent watch} recomputes its wall-clock deadline from the session's
 * original {@code started_at}, so an agent three hours into a four-hour budget gets the remaining
 * hour, not a fresh four. Only sessions this node executed are considered — a synced foreign run is
 * its executing node's to guard, and arming a local watcher against it would eventually enforce a
 * foreign deadline on this box's container.
 *
 * <p>Coverage is probed, not bookkept — and probed at the process level: a recorded watcher pid
 * that is still alive (free, in-process check) or any {@code sail agent watch} process for the
 * project ({@link WatcherSpawner#watcherProcessRunning}, which sees every systemd scope, every
 * user's manager, and plain fallback processes alike) means covered. Unit-name probes alone would
 * be blind to a watcher armed in another user's manager and re-arm a double whose guardrail actions
 * fire twice. Relaunching is unit-or-nothing ({@link WatcherSpawner#spawnUnit}); where systemd is
 * unavailable the relaunch is empty and the missed-stop sweep still replays the stop when the agent
 * ends. A session whose agent unit is already dead is that sweep's job, not this one's.
 */
public final class WatcherRearmer implements AutoCloseable {

  /** Re-arm cadence, matching the missed-stop sweep: unguarded time is bounded by one interval. */
  public static final Duration DEFAULT_INTERVAL = MissedStopReconciler.DEFAULT_INTERVAL;

  private static final SpecStore.SpecFilter IN_PROGRESS =
      new SpecStore.SpecFilter(null, "in_progress", null, null, null);

  /** Relaunches a project's watcher as a unit; empty when neither systemd scope accepts it. */
  @FunctionalInterface
  public interface WatcherRelauncher {
    Optional<WatcherSpawner.Unit> relaunch(String project) throws Exception;
  }

  private final SpecStore specStore;
  private final RunStore sessionStore;
  private final MissedStopReconciler.UnitProbe agentUnitProbe;
  private final Predicate<String> watcherRunning;
  private final LongPredicate watcherAlive;
  private final Supplier<String> localHandle;
  private final WatcherRelauncher relauncher;
  private final PeriodicPass pass;

  public WatcherRearmer(
      SpecStore specStore,
      RunStore sessionStore,
      MissedStopReconciler.UnitProbe agentUnitProbe,
      Predicate<String> watcherRunning,
      LongPredicate watcherAlive,
      Supplier<String> localHandle,
      WatcherRelauncher relauncher) {
    this.specStore = specStore;
    this.sessionStore = sessionStore;
    this.agentUnitProbe = agentUnitProbe;
    this.watcherRunning = watcherRunning;
    this.watcherAlive = watcherAlive;
    this.localHandle = localHandle;
    this.relauncher = relauncher;
    this.pass = new PeriodicPass("rearm", this::rearm);
  }

  /** Liveness of a host process by pid — how recorded watcher pids are checked in production. */
  public static LongPredicate livingProcess() {
    return pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
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
   * Runs one re-arm pass and returns how many watchers were relaunched. Best-effort: a failing spec
   * is logged and skipped so one broken project cannot leave the rest unwatched, and a store error
   * is logged and swallowed so re-arming can never block server startup.
   */
  public int rearm() {
    var rearmed = 0;
    try {
      for (var spec : specStore.list(IN_PROGRESS)) {
        try {
          rearmed += rearm(spec) ? 1 : 0;
        } catch (Exception e) {
          System.err.println(
              "  [rearm] failed for " + spec.project() + "/" + spec.id() + ": " + e.getMessage());
        }
      }
    } catch (Exception e) {
      System.err.println("  [rearm] watcher re-arm aborted: " + e.getMessage());
    }
    return rearmed;
  }

  private boolean rearm(SpecStore.SpecRow spec) throws Exception {
    var node = localHandle.get();
    var latest =
        sessionStore.listForSpec(spec.id()).stream()
            .filter(run -> SailApiOperations.ownsRun(run.node(), node))
            .findFirst();
    if (latest.isEmpty() || !"running".equals(latest.get().status())) {
      return false;
    }
    var session = latest.get();
    if (session.watcherPid() != null && watcherAlive.test(session.watcherPid())) {
      return false;
    }
    if (watcherRunning.test(spec.project())) {
      return false;
    }
    if (!agentUnitProbe.active(spec.project())) {
      return false;
    }
    var unit = relauncher.relaunch(spec.project());
    if (unit.isEmpty()) {
      System.err.println(
          "  [rearm] "
              + spec.project()
              + "/"
              + spec.id()
              + " (session "
              + session.id()
              + "): agent is running unwatched but no watcher unit could be launched (no agent"
              + " block or no systemd scope); the missed-stop sweep still replays its stop when"
              + " the agent ends");
      return false;
    }
    System.err.println(
        "  [rearm] re-armed watcher for "
            + spec.project()
            + "/"
            + spec.id()
            + " (session "
            + session.id()
            + "): "
            + (unit.get().adopted()
                ? "adopted already-active unit " + unit.get().name()
                : "launched unit "
                    + unit.get().name()
                    + " ("
                    + unit.get().scope()
                    + " scope), resuming the original deadline from the session's started_at"));
    return true;
  }

  @Override
  public void close() {
    pass.close();
  }
}
