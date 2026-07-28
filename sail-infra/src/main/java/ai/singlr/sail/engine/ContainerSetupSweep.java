/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Boot-time reconciliation of every running project container's sail-owned surface: the socket bind
 * mount (force-refreshed, healing reboot-stranded inodes) and the helper scripts (rewritten when a
 * staleness marker is missing). This is the eager half of an enforcement change — a server that
 * starts requiring something new must also provision it, not wait for the next sail-launched
 * session to wander by. Best-effort by design: one wedged container is reported and skipped, an
 * unlistable daemon reconciles nothing, and the server boots either way.
 */
public final class ContainerSetupSweep {

  private ContainerSetupSweep() {}

  /**
   * Reconciles each running container whose name is in {@code projects}; returns how many were
   * reconciled.
   */
  public static int sweep(ShellExec shell, Collection<String> projects)
      throws InterruptedException {
    var reconciled = 0;
    for (var container : running(shell, projects)) {
      if (reconcile(shell, container)) {
        reconciled++;
      }
    }
    return reconciled;
  }

  /**
   * The single best-effort reconcile of one container: mount force-refresh plus marker-gated script
   * rewrite, a warning instead of a failure when the container is wedged. Every automatic trigger —
   * the boot sweep here, {@code sail up} — shares this one policy; callers that need the outcome or
   * their own error handling call {@link ContainerSailSetup#ensureInstalled} directly.
   */
  public static boolean reconcile(ShellExec shell, String container) throws InterruptedException {
    try {
      ContainerSailSetup.ensureInstalled(shell, container);
      return true;
    } catch (IOException | TimeoutException | RuntimeException e) {
      System.err.println(
          "sail setup: could not refresh helpers in '" + container + "' (" + e.getMessage() + ").");
      return false;
    }
  }

  private static Collection<String> running(ShellExec shell, Collection<String> projects)
      throws InterruptedException {
    try {
      return new ContainerManager(shell)
          .listAll().stream()
              .filter(info -> projects.contains(info.name()))
              .filter(info -> info.state() instanceof ContainerState.Running)
              .map(ContainerManager.ContainerInfo::name)
              .toList();
    } catch (IOException | TimeoutException | RuntimeException e) {
      System.err.println("sail setup sweep: could not list containers (" + e.getMessage() + ").");
      return List.of();
    }
  }
}
