/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Boot-time reconciliation of every running project container's sail-owned surface, and every
 * running workspace's of a project this box holds ({@link Workspaces}): the socket bind mount
 * (re-pointed when its source moved) and the sail-owned files (rewritten when the machinery stamp
 * does not match this binary). This is the eager half of an enforcement change — a server that
 * starts requiring something new must also provision it, not wait for the next sail-launched
 * session to wander by. Best-effort by design: one wedged container is reported and skipped, an
 * unlistable daemon reconciles nothing, and the server boots either way.
 */
public final class ContainerSetupSweep {

  private ContainerSetupSweep() {}

  /**
   * Reconciles each running container whose name is in {@code projects}, and each running workspace
   * of one of them; returns how many were reconciled.
   */
  public static int sweep(ShellExec shell, Collection<String> projects)
      throws InterruptedException {
    return sweep(shell, projects, SailPaths.locksDir());
  }

  /** As {@link #sweep(ShellExec, Collection)}, each container serialised under {@code locks}. */
  public static int sweep(ShellExec shell, Collection<String> projects, Path locks)
      throws InterruptedException {
    var reconciled = 0;
    for (var container : running(shell, projects)) {
      if (reconcile(shell, container, locks)) {
        reconciled++;
      }
    }
    return reconciled;
  }

  /**
   * The single best-effort reconcile of one container: mount check plus stamp-gated file rewrite, a
   * warning instead of a failure when the container is wedged. Every automatic trigger — the boot
   * sweep here, {@code sail up} — shares this one policy; callers that need the outcome or their
   * own error handling call {@link ContainerSailSetup#ensureInstalled} directly.
   */
  public static boolean reconcile(ShellExec shell, String container) throws InterruptedException {
    return reconcile(shell, container, SailPaths.locksDir());
  }

  /** As {@link #reconcile(ShellExec, String)}, serialised under {@code locks}. */
  public static boolean reconcile(ShellExec shell, String container, Path locks)
      throws InterruptedException {
    try {
      ContainerSailSetup.ensureInstalled(shell, shell, container, locks);
      return true;
    } catch (IOException | TimeoutException | RuntimeException e) {
      System.err.println(
          "sail setup: could not refresh helpers in '" + container + "' (" + e.getMessage() + ").");
      return false;
    }
  }

  /**
   * Whether {@code instance} is a project's own container or a whole workspace of a project in
   * {@code projects}: a template, a stray and a half-made workspace are never reconciled.
   */
  private static boolean swept(BoxInstances.Instance instance, Collection<String> projects) {
    return switch (instance.kind()) {
      case BoxInstances.Plain ignored -> projects.contains(instance.name());
      case BoxInstances.Workspace workspace ->
          workspace.ready() && projects.contains(workspace.project());
      case BoxInstances.Template ignored -> false;
      case BoxInstances.Stray ignored -> false;
    };
  }

  private static Collection<String> running(ShellExec shell, Collection<String> projects)
      throws InterruptedException {
    try {
      return new BoxInstances(shell)
          .all().stream()
              .filter(instance -> swept(instance, projects))
              .filter(instance -> instance.state() instanceof ContainerState.Running)
              .map(BoxInstances.Instance::name)
              .toList();
    } catch (IOException | TimeoutException | RuntimeException e) {
      System.err.println("sail setup sweep: could not list containers (" + e.getMessage() + ").");
      return List.of();
    }
  }
}
