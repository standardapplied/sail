/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Ids;

/**
 * The identity of a headless agent run inside a container: the systemd unit that owns it and the
 * files carrying its pid, streamed log, session metadata, and task/prompt.
 *
 * <p>Every agent sail starts — a build, an ad-hoc run, a chat turn, a reviewer, a fix agent — gets
 * a <em>per-run</em> identity via {@link #forRun}: unit {@code sail-agent-<runId>} with every file
 * under {@code ~/.sail/runs/<runId>/}, so concurrent executions in one container never collide on a
 * unit name or clobber each other's session state, and a log address names exactly one execution.
 * The run records the unit it was launched with as part of its aggregate; later consumers (stop,
 * probe, reconciler, watcher) rebuild the identity from that record via {@link #recorded} instead
 * of re-deriving the name, so the derivation rule can change without stranding a run that is
 * already executing.
 *
 * <p>The paths are the single source of truth for where an agent run lives on disk; {@link
 * AgentSession} and the watcher read them from here rather than hardcoding their own copies.
 */
public record AgentUnit(
    String unitName, String logPath, String pidPath, String sessionPath, String taskPath) {

  private static final String DIR = "/home/dev/.sail";

  /** Root of the per-run directories: {@code ~/.sail/runs/<runId>/}. */
  public static final String RUNS_DIR = DIR + "/runs";

  /** Prefix of every run-scoped build unit: {@code sail-agent-<runId>}. */
  public static final String RUN_UNIT_PREFIX = "sail-agent-";

  /**
   * Derives a run's identity at launch: unit {@code sail-agent-<runId>}, files under {@code
   * ~/.sail/runs/<runId>/}. The launcher records the derived unit name on the run row; everything
   * after launch should rebuild the identity with {@link #recorded}.
   */
  public static AgentUnit forRun(String runId) {
    return recorded(runId, RUN_UNIT_PREFIX + Ids.requireUuid(runId));
  }

  /**
   * Rebuilds a run's identity from its recorded unit name: systemd is addressed by exactly the unit
   * the run was launched as, while the file paths derive from the canonical run id — run rows
   * replicate over sync, so a persisted path is untrusted input that must never select a file.
   */
  public static AgentUnit recorded(String runId, String unitName) {
    var dir = runDir(Ids.requireUuid(runId));
    return new AgentUnit(
        unitName,
        dir + "/agent.log",
        dir + "/agent.pid",
        dir + "/agent-session.json",
        dir + "/agent-task.txt");
  }

  /**
   * The log a reader of run {@code runId} opens: its {@code agent.log}, or the {@code review.log} a
   * review wrote before every lane logged as a run of its own, when that is exactly what the run
   * recorded. The recorded path only chooses between the two names under the run's own directory;
   * the path itself is still derived from the canonical run id, so a stored value never selects a
   * file.
   */
  public static String readableLogPath(String runId, String recordedLogPath) {
    var legacy = runDir(Ids.requireUuid(runId)) + "/review.log";
    return legacy.equals(recordedLogPath) ? legacy : forRun(runId).logPath();
  }

  /** The systemd unit name with the {@code .service} suffix, as {@code systemctl} expects it. */
  public String service() {
    return unitName + ".service";
  }

  /** The run-scoped directory holding one execution's files: {@code ~/.sail/runs/<runId>}. */
  public static String runDir(String runId) {
    return RUNS_DIR + "/" + runId;
  }

  /** Where the watcher records the guardrail that ended this run, beside the run's own files. */
  public String guardrailTriggerPath() {
    return logPath.substring(0, logPath.lastIndexOf('/')) + "/guardrail-triggered.yaml";
  }
}
