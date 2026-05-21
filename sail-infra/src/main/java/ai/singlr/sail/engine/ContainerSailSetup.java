/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Self-healing entry point for sail's in-container plumbing. Probes for the three sail-owned files
 * that {@link ProjectProvisioner#attachEventSocket} installs at provision time (sail-event.sh
 * helper, claude-settings.json, codex hooks.json) and re-runs the installers when any of them is
 * missing. Designed for the dispatch hot path: when everything is present the cost is one
 * in-container {@code test -f} call.
 *
 * <p>Containers provisioned before this code shipped — or containers that lost the files in some
 * way — will get backfilled automatically the next time {@code sail dispatch} runs, instead of
 * silently losing lifecycle events until the engineer remembers to run {@code sail project sync}.
 */
public final class ContainerSailSetup {

  private ContainerSailSetup() {}

  /** Result of a setup probe + install. */
  public enum Result {
    /** All sail-owned files were already in place; no install ran. */
    ALREADY_PRESENT,
    /** At least one file was missing; the full set was reinstalled. */
    BACKFILLED
  }

  /**
   * Ensures the sail event socket is mounted and the three sail-owned helper files are installed
   * inside {@code container}. Cheap when everything is present (single {@code test -f} chain),
   * idempotent otherwise.
   */
  public static Result ensureInstalled(ShellExec shell, String container)
      throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(container);
    if (allFilesPresent(shell, container)) {
      return Result.ALREADY_PRESENT;
    }
    new IncusDeviceManager(shell)
        .ensureEventSocket(
            container, SailPaths.apiSocketPath(), SailPaths.apiSocketContainerPath());
    new SailEventHelper(shell).install(container);
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
                        + ClaudeCodeHookConfig.SETTINGS_PATH
                        + " && test -f "
                        + CodexHookConfig.SETTINGS_PATH)));
    return probe.ok();
  }
}
