/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.store.SessionStore;
import ai.singlr.sail.store.SpecStore;
import java.util.OptionalLong;
import java.util.function.LongPredicate;

/**
 * Daemon-start recovery for runs left unwatched: the guardrail watcher lives in the daemon's
 * cgroup, so a restart mid-run kills it while the agent keeps going — no stall detection, no
 * max-duration enforcement until the agent exits. For each {@code in_progress} spec whose latest
 * session is still {@code running}, whose systemd unit is still active, and whose recorded watcher
 * pid is dead, this relaunches the watcher against the existing unit. The relaunched {@code sail
 * agent watch} reads the session's original {@code started_at} from the container and computes the
 * wall-clock deadline from it, so an agent three hours into a four-hour budget gets the remaining
 * hour, not a fresh four.
 *
 * <p>A session with no recorded watcher pid (a CLI-side dispatch, whose watcher is not tied to the
 * daemon's lifetime, or a pre-upgrade row) cannot be verified as uncovered; re-arming it could
 * stack a second watcher whose guardrail actions would fire twice, so it is skipped with a log line
 * saying so. A session whose unit is already dead is the missed-stop sweep's job, not this one's.
 */
public final class WatcherRearmer {

  private static final SpecStore.SpecFilter IN_PROGRESS =
      new SpecStore.SpecFilter(null, "in_progress", null, null, null);

  /** Relaunches a project's guardrail watcher; empty when the project declares no guardrails. */
  @FunctionalInterface
  public interface WatcherRelauncher {
    OptionalLong relaunch(String project) throws Exception;
  }

  private final SpecStore specStore;
  private final SessionStore sessionStore;
  private final MissedStopReconciler.UnitProbe unitProbe;
  private final LongPredicate watcherAlive;
  private final WatcherRelauncher relauncher;

  public WatcherRearmer(
      SpecStore specStore,
      SessionStore sessionStore,
      MissedStopReconciler.UnitProbe unitProbe,
      LongPredicate watcherAlive,
      WatcherRelauncher relauncher) {
    this.specStore = specStore;
    this.sessionStore = sessionStore;
    this.unitProbe = unitProbe;
    this.watcherAlive = watcherAlive;
    this.relauncher = relauncher;
  }

  /** Liveness of a host process by pid — how recorded watcher pids are checked in production. */
  public static LongPredicate livingProcess() {
    return pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
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
    var latest = sessionStore.listForSpec(spec.id()).stream().findFirst();
    if (latest.isEmpty() || !"running".equals(latest.get().status())) {
      return false;
    }
    var session = latest.get();
    if (session.watcherPid() == null) {
      System.err.println(
          "  [rearm] "
              + spec.project()
              + "/"
              + spec.id()
              + " (session "
              + session.id()
              + "): no watcher pid recorded, so coverage cannot be verified; not re-arming"
              + " (a second watcher would double guardrail actions)");
      return false;
    }
    if (watcherAlive.test(session.watcherPid())) {
      return false;
    }
    if (!unitProbe.active(spec.project())) {
      return false;
    }
    var pid = relauncher.relaunch(spec.project());
    if (pid.isEmpty()) {
      System.err.println(
          "  [rearm] "
              + spec.project()
              + "/"
              + spec.id()
              + " (session "
              + session.id()
              + "): watcher pid "
              + session.watcherPid()
              + " is dead but the project declares no guardrails; nothing to re-arm");
      return false;
    }
    sessionStore.updateWatcherPid(session.id(), (int) pid.getAsLong());
    System.err.println(
        "  [rearm] re-armed watcher for "
            + spec.project()
            + "/"
            + spec.id()
            + " (session "
            + session.id()
            + "): watcher pid "
            + session.watcherPid()
            + " died while the agent unit is still active; new watcher pid "
            + pid.getAsLong()
            + " resumes the original deadline from the session's started_at");
    return true;
  }
}
