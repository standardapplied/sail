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
 * <p>A dispatched build run gets a <em>per-run</em> identity via {@link #forRun}: unit {@code
 * sail-agent-<runId>} with every file under {@code ~/.sail/runs/<runId>/}, so concurrent dispatches
 * in one container never collide on a unit name or clobber each other's session state. The run
 * records the unit it was launched with as part of its aggregate; later consumers (stop, probe,
 * reconciler, watcher) rebuild the identity from that record via {@link #recorded} instead of
 * re-deriving the name, so the derivation rule can change without stranding a run that is already
 * executing.
 *
 * <p>{@link #BUILD} remains the fixed identity of the ad-hoc lane — {@code sail agent start} and
 * other engineer-initiated sessions that mint no run. A review gets a <em>per-review</em> identity
 * via {@link #forReview}: the pipeline executes each spec's review on its own virtual thread, so
 * concurrently completed specs review concurrently, and a shared prompt or log would
 * cross-contaminate them. The reviewer and its fix agent share the review's own {@code review.log},
 * so one attempt's whole review↔fix negotiation still lands in one live-followable file.
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

  /** The ad-hoc (non-dispatch) build agent: one fixed unit and file set per container. */
  public static final AgentUnit BUILD =
      new AgentUnit(
          "sail-agent",
          DIR + "/agent.log",
          DIR + "/agent.pid",
          DIR + "/agent-session.json",
          DIR + "/agent-task.txt");

  /**
   * The review role's template identity: {@link #fromRole} and the log endpoints use its file names
   * to derive run-scoped review paths, and {@code sail agent log --review} falls back to its fixed
   * log when no review exists yet. Live reviews never run here — each executes under its own {@link
   * #forReview} identity.
   */
  public static final AgentUnit REVIEW =
      new AgentUnit(
          "sail-review",
          DIR + "/review.log",
          DIR + "/review.pid",
          DIR + "/review-session.json",
          DIR + "/review-prompt.txt");

  /**
   * Derives a review's own identity: prompt, log, and session files under {@code
   * ~/.sail/runs/<reviewId>/}. The pipeline reviews concurrently completed specs on concurrent
   * virtual threads, so each review (and its fix agent, which shares the file set) must own its
   * prompt, log offsets, and output — a shared file would attach one spec's findings to another's
   * review. Review runs as a blocking foreground exec, not a systemd unit, so only the task file
   * and log are used in practice.
   */
  public static AgentUnit forReview(String reviewId) {
    var id = Ids.requireUuid(reviewId);
    var dir = runDir(id);
    return new AgentUnit(
        "sail-review-" + id,
        dir + "/review.log",
        dir + "/review.pid",
        dir + "/review-session.json",
        dir + "/review-prompt.txt");
  }

  /**
   * Derives a dispatched run's identity at launch: unit {@code sail-agent-<runId>}, files under
   * {@code ~/.sail/runs/<runId>/}. The launcher records the derived unit name on the run row;
   * everything after launch should rebuild the identity with {@link #recorded}.
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

  /** The systemd unit name with the {@code .service} suffix, as {@code systemctl} expects it. */
  public String service() {
    return unitName + ".service";
  }

  /** The run-scoped directory holding one execution's files: {@code ~/.sail/runs/<runId>}. */
  public static String runDir(String runId) {
    return RUNS_DIR + "/" + runId;
  }

  /**
   * This role's log path scoped to a single run: {@code ~/.sail/runs/<runId>/agent.log} (build) or
   * {@code review.log} (review), so a log address names exactly one execution.
   */
  public String runLogPath(String runId) {
    return runDir(runId) + "/" + logPath.substring(logPath.lastIndexOf('/') + 1);
  }

  /**
   * Resolves the log-selecting role name — {@code build} or {@code review}, the API/CLI equivalent
   * of {@code --review} — to its unit, so the log endpoints share one mapping instead of hardcoding
   * a second path. Throws {@link IllegalArgumentException} for any other value.
   */
  public static AgentUnit fromRole(String role) {
    return switch (role) {
      case "build" -> BUILD;
      case "review" -> REVIEW;
      default ->
          throw new IllegalArgumentException(
              "Unknown role: " + role + " (expected build or review)");
    };
  }
}
