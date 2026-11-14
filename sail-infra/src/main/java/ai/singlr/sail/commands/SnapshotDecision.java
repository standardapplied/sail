/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.SnapshotManager;
import ai.singlr.sail.engine.Spinner;
import java.io.PrintStream;
import picocli.CommandLine.Help.Ansi;

/**
 * Resolves whether to take a snapshot before {@code sail agent run}, which runs in the project's
 * own container, and runs the snapshot with progress feedback. The CLI uses {@code --snapshot} /
 * {@code --no-snapshot} (picocli {@code negatable = true}) to skip the prompt; without those flags
 * the user is always prompted in interactive mode.
 */
final class SnapshotDecision {

  private SnapshotDecision() {}

  /**
   * Returns true if a snapshot should be taken now. {@code override} is the value of {@code
   * --snapshot} / {@code --no-snapshot} ({@code null} means neither was passed). When no override
   * is set the user is prompted (defaults to no); non-interactive mode (JSON output or piped stdin)
   * skips silently: snapshotting is opt-in per run via the flag.
   */
  static boolean shouldSnapshot(Boolean override, boolean json) {
    if (override != null) {
      return override;
    }
    if (json) {
      return false;
    }
    return ConsoleHelper.confirmNo("Snapshot project before continuing?");
  }

  /**
   * Creates a snapshot, showing an animated spinner with elapsed time in non-JSON mode. The
   * snapshot uses {@link SnapshotManager#DEFAULT_TIMEOUT}.
   */
  static void create(
      PrintStream out, SnapshotManager snapMgr, String project, String label, boolean json)
      throws Exception {
    if (json) {
      snapMgr.create(project, label);
      return;
    }
    try (var ignored = Spinner.start(out, "Creating snapshot " + label)) {
      snapMgr.create(project, label);
    }
    Banner.printSnapshotCreated(project, label, out, Ansi.AUTO);
    out.println();
  }
}
