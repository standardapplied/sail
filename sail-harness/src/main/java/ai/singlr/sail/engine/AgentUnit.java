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
    String unitName, String logPath, String pidPath, String sessionPath, String taskPath) {

  private static final String DIR = "/home/dev/.sail";

  /**
   * The dispatched build: launched as a detached systemd unit and streamed to {@code agent.log}.
   */
  public static final AgentUnit BUILD =
      new AgentUnit(
          "sail-agent",
          DIR + "/agent.log",
          DIR + "/agent.pid",
          DIR + "/agent-session.json",
          DIR + "/agent-task.txt");

  /**
   * The read-only reviewer and the fix agent. They share {@code review.log}, which the runner
   * appends so a dispatch attempt's whole reviewer↔fix negotiation lands in one live-followable
   * file (the attempt boundary resets it via {@link AgentSession#resetLog}). Review runs as a
   * blocking foreground exec, not a systemd unit, so only its task file and log are used here.
   */
  public static final AgentUnit REVIEW =
      new AgentUnit(
          "sail-review",
          DIR + "/review.log",
          DIR + "/review.pid",
          DIR + "/review-session.json",
          DIR + "/review-prompt.txt");

  /** The systemd unit name with the {@code .service} suffix, as {@code systemctl} expects it. */
  public String service() {
    return unitName + ".service";
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
