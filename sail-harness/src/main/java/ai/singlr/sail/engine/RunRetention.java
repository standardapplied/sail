/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Bounds the per-run log directories a container accumulates. Each dispatch writes its log under
 * {@code ~/.sail/runs/<runId>/}; without pruning that grows unbounded. Retention keeps the newest
 * {@code keep} runs' directories and removes the rest, returning the run ids it pruned so the
 * caller can log them — pruning is never silent. The run rows themselves stay (they are synced
 * history); only the on-disk log bytes, which are box-local and never replicated, are reclaimed.
 */
public final class RunRetention {

  /** A generous default: enough that an operator can still open recent runs' logs. */
  public static final int DEFAULT_KEEP = 20;

  private RunRetention() {}

  /**
   * Removes the log directory of every run beyond the newest {@code keep} of {@code
   * runIdsNewestFirst}. Idempotent — a missing directory is a no-op — and best-effort per run: a
   * failed removal is skipped rather than aborting the sweep. Returns the ids actually pruned.
   */
  public static List<String> prune(
      ShellExec shell, String container, List<String> runIdsNewestFirst, int keep)
      throws IOException, InterruptedException, TimeoutException {
    var pruned = new ArrayList<String>();
    for (var i = Math.max(keep, 0); i < runIdsNewestFirst.size(); i++) {
      var runId = runIdsNewestFirst.get(i);
      var result =
          shell.exec(
              ContainerExec.asDevUser(container, List.of("rm", "-rf", AgentUnit.runDir(runId))));
      if (result.ok()) {
        pruned.add(runId);
      }
    }
    return pruned;
  }
}
