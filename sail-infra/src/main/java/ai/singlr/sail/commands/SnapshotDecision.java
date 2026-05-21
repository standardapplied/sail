/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.SnapshotManager;
import ai.singlr.sail.engine.Spinner;
import java.io.PrintStream;
import picocli.CommandLine.Help.Ansi;

/**
 * Resolves whether to take a snapshot before a state-mutating command (dispatch, agent launch, run)
 * and runs the snapshot with progress feedback. The CLI uses {@code --snapshot} / {@code
 * --no-snapshot} (picocli {@code negatable = true}) to override the inherited default from {@code
 * sail.yaml}'s {@code agent.auto_snapshot}.
 */
final class SnapshotDecision {

  private SnapshotDecision() {}

  /**
   * Returns true if a snapshot should be taken now. {@code override} is the value of {@code
   * --snapshot} / {@code --no-snapshot} ({@code null} means neither was passed). When neither flag
   * is set: if {@code agent.auto_snapshot} is true in YAML, snapshot silently; otherwise prompt the
   * user (defaults to no), and skip silently in non-interactive mode (JSON output or piped stdin).
   */
  static boolean shouldSnapshot(Boolean override, SailYaml config, boolean json) {
    if (override != null) {
      return override;
    }
    if (config != null && config.agent() != null && config.agent().autoSnapshot()) {
      return true;
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
