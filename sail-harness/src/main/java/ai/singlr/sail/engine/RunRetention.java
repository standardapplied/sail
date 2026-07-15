/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Ids;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    return prune(shell, container, runIdsNewestFirst, Set.of(), keep);
  }

  /**
   * As {@link #prune(ShellExec, String, List, int)}, while retaining every protected run even when
   * it falls beyond the keep window. Callers protect live runs so concurrent build and review
   * executions never lose their directory while an agent still has the log open.
   */
  public static List<String> prune(
      ShellExec shell,
      String container,
      List<String> runIdsNewestFirst,
      Set<String> protectedRunIds,
      int keep)
      throws IOException, InterruptedException, TimeoutException {
    var pruned = new ArrayList<String>();
    for (var i = Math.max(keep, 0); i < runIdsNewestFirst.size(); i++) {
      var runId = runIdsNewestFirst.get(i);
      if (protectedRunIds.contains(runId)) {
        continue;
      }
      var result =
          shell.exec(ContainerExec.asDevUser(container, List.of("rm", "-rf", runDir(runId))));
      if (result.ok()) {
        pruned.add(runId);
      }
    }
    return pruned;
  }

  /**
   * The absolute run-log directory for {@code runId}, guaranteed to be a direct child of the runs
   * root. Run ids can arrive over sync from writable peers, so the id is validated as a canonical
   * UUID and the resolved path is checked to sit immediately beneath {@link AgentUnit#RUNS_DIR}
   * before it is handed to {@code rm -rf} — a {@code ..} segment can never escape the runs root to
   * delete another directory owned by the container's dev user.
   */
  private static String runDir(String runId) {
    var runsRoot = Path.of(AgentUnit.RUNS_DIR);
    var runDir = runsRoot.resolve(Ids.requireUuid(runId)).normalize();
    if (!runsRoot.equals(runDir.getParent())) {
      throw new IllegalArgumentException("Run directory escapes runs root: " + runId);
    }
    return runDir.toString();
  }
}
