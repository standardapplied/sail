/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

/**
 * The per-role identity of a headless agent run inside a container: the systemd unit that owns it
 * and the files carrying its pid, streamed log, session metadata, and task/prompt. Roles are
 * isolated so a review run never clobbers the build run's unit or log.
 *
 * <p>{@link #BUILD} is the coder that implements a spec (dispatch) and the fix agent that addresses
 * review findings; both write the implementation on the branch. {@link #REVIEW} is the read-only
 * reviewer and shares its log with the fix agent so {@code review.log} carries the whole review↔fix
 * negotiation, kept separate from the original build in {@code agent.log}.
 *
 * <p>The paths are the single source of truth for where an agent run lives on disk; {@link
 * AgentSession} and the watcher read them from here rather than hardcoding their own copies.
 */
public record AgentUnit(
    String unitName,
    String logPath,
    String pidPath,
    String sessionPath,
    String taskPath,
    boolean appendsLog) {

  private static final String DIR = "/home/dev/.sail";

  /** The dispatched build. Truncates its log so each dispatch starts with a fresh transcript. */
  public static final AgentUnit BUILD =
      new AgentUnit(
          "sail-agent",
          DIR + "/agent.log",
          DIR + "/agent.pid",
          DIR + "/agent-session.json",
          DIR + "/agent-task.txt",
          false);

  /**
   * The read-only reviewer and the fix agent, which share this unit's log. It <em>appends</em> so
   * every reviewer↔fix turn within one dispatch attempt lands in a single {@code review.log}; the
   * attempt boundary resets it (the dispatch clears it before the build), keeping the whole
   * negotiation in one file without growing across attempts.
   */
  public static final AgentUnit REVIEW =
      new AgentUnit(
          "sail-review",
          DIR + "/review.log",
          DIR + "/review.pid",
          DIR + "/review-session.json",
          DIR + "/review-prompt.txt",
          true);

  /** The systemd unit name with the {@code .service} suffix, as {@code systemctl} expects it. */
  public String service() {
    return unitName + ".service";
  }
}
