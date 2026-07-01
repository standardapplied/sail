/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Self-healing entry point for sail's in-container plumbing. Reconciles two things on every
 * dispatch:
 *
 * <ol>
 *   <li><b>Event-socket bind mount.</b> {@link IncusDeviceManager#ensureEventSocket} is called
 *       every time so the source path matches the current {@link SailPaths#apiSocketHostDir()} —
 *       containers provisioned before the directory-mount fix (file-level mounts that stranded on
 *       stale inodes when {@code sail-api} restarted) get auto-migrated to the directory mount on
 *       the next dispatch.
 *   <li><b>Helper files in the container.</b> {@code sail-event.sh}, {@code claude-settings.json},
 *       and {@code codex hooks.json} are probed with a single {@code test -f} chain; if any is
 *       missing — or the {@code spec} script still references a stale socket path after the socket
 *       moved off {@code /run}, or {@code claude-settings.json} predates the tool-progress hooks —
 *       the installers re-run and rewrite them. The hook-content check matters because the file is
 *       install-once: without it, a container provisioned before the hooks existed would keep a
 *       settings file the stall watcher gets no progress from, and its agents die at {@code
 *       max_idle}.
 * </ol>
 *
 * Designed for the dispatch hot path: ensureEventSocket is one idempotent shell call, the
 * file-existence probe is one more, and only a broken/missing setup costs the four installer
 * shells.
 */
public final class ContainerSailSetup {

  private ContainerSailSetup() {}

  /** Result of a setup reconciliation. */
  public enum Result {
    /** Mount and all sail-owned files were already in place; no install ran. */
    ALREADY_PRESENT,
    /** Mount was added or replaced, or at least one helper file was missing. */
    BACKFILLED
  }

  /**
   * Reconciles the event-socket bind mount and the three sail-owned helper files in {@code
   * container}. The mount is force-refreshed on every call (remove + re-add) because Incus tracks
   * the bind by inode, and the source directory can be recreated under the same path by {@code
   * systemd}'s {@code RuntimeDirectory=} cleanup — the only signal that the existing mount is stale
   * is the inode mismatch, which Incus does not surface in {@code config device show}. Idempotent
   * at the user-visible level (post-call the mount always points at the current inode); costs two
   * extra {@code incus} shell calls per dispatch.
   */
  public static Result ensureInstalled(ShellExec shell, String container)
      throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(container);
    new IncusDeviceManager(shell)
        .refreshEventSocket(
            container, SailPaths.apiSocketHostDir(), SailPaths.apiSocketContainerDir());
    if (allFilesPresent(shell, container)) {
      return Result.ALREADY_PRESENT;
    }
    new SailEventHelper(shell).install(container);
    new SpecCliHelper(shell).install(container);
    new ClaudeCodeHookConfig(shell).install(container);
    new CodexHookConfig(shell).install(container);
    return Result.BACKFILLED;
  }

  private static boolean allFilesPresent(ShellExec shell, String container)
      throws IOException, InterruptedException, TimeoutException {
    var probe =
        shell.exec(
            ContainerExec.asDevUser(
                container,
                List.of(
                    "bash",
                    "-c",
                    "test -f "
                        + SailEventHelper.SCRIPT_PATH
                        + " && test -f "
                        + SpecCliHelper.SCRIPT_PATH
                        + " && test -f "
                        + ClaudeCodeHookConfig.SETTINGS_PATH
                        + " && grep -qsF "
                        + ClaudeCodeHookConfig.PROGRESS_HOOK_MARKER
                        + " "
                        + ClaudeCodeHookConfig.SETTINGS_PATH
                        + " && test -f "
                        + CodexHookConfig.SETTINGS_PATH
                        + " && grep -qsF "
                        + SpecCliHelper.PATH_MARKER
                        + " "
                        + SpecCliHelper.PROFILE_PATH
                        + " && grep -qsF "
                        + SailPaths.apiSocketContainerPath()
                        + " "
                        + SpecCliHelper.SCRIPT_PATH)));
    return probe.ok();
  }
}
