/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.StreamJsonResult;
import java.time.Duration;
import java.util.List;

/**
 * Runs a review or fix agent inside the project container, sharing the dispatch agent's command
 * (streaming {@code stream-json}) but <em>not</em> its process wrapper. Dispatch launches a
 * detached {@code systemd-run --user} unit because it is fire-and-forget and watched externally; a
 * review blocks the pipeline until it has findings, so it runs as a plain foreground {@code
 * shell.exec} bounded by a generous per-invocation timeout. Blocking here needs no systemd user
 * manager or D-Bus session, so it works in any container.
 *
 * <p>Every invocation runs under its review's own {@link AgentUnit#forReview} identity: the prompt
 * and log live under {@code ~/.sail/runs/<reviewId>/}, so pipelines executing concurrently on the
 * controller's virtual-thread executor never overwrite each other's prompt or interleave output.
 * The agent's output streams to the review's {@code review.log} (appended, so one attempt's
 * reviewer↔fix negotiation accumulates in one live-followable log), and the findings are read back
 * from the bytes this run appended — parsed via {@link StreamJsonResult} so a streamed reviewer and
 * a plain one are handled uniformly.
 *
 * <p>Run clean: no {@code SAIL_SPEC_ID} and no agent hooks, so the reviewer's own completion never
 * re-enters the pipeline (which would recurse forever).
 */
final class ContainerReviewAgentRunner implements ReviewAgentRunner {

  private static final String WORKSPACE = ContainerExec.DEV_HOME + "/workspace";

  /**
   * How long a single review or fix invocation may run before it is reaped. Generous enough for an
   * agent reasoning over a real diff; the dispatch-level guardrail ceiling is hours, so a bounded
   * per-invocation budget is the right limit here.
   */
  private static final Duration AGENT_TIMEOUT = Duration.ofMinutes(30);

  private final ShellExec shell;
  private final AgentSession session;

  ContainerReviewAgentRunner(ShellExec shell) {
    this(shell, new AgentSession(shell));
  }

  ContainerReviewAgentRunner(ShellExec shell, AgentSession session) {
    this.shell = shell;
    this.session = session;
  }

  @Override
  public String run(String project, String agent, String prompt, String reviewId) throws Exception {
    var cli = AgentCli.fromYamlName(agent);
    var unit = AgentUnit.forReview(reviewId);
    session.ensureDirectory(project);
    session.writeTaskFile(project, prompt, unit);

    var startOffset = logSize(project, unit);

    var agentCmd = cli.headlessCommand(unit.taskPath(), true, null, null, null, true);
    var command = "cd " + WORKSPACE + " && " + agentCmd + " >> " + unit.logPath() + " 2>&1";
    var result =
        shell.exec(
            ContainerExec.asDevUser(project, List.of("bash", "-lc", command)), null, AGENT_TIMEOUT);
    if (!result.ok()) {
      throw new IllegalStateException(
          "Review agent '"
              + agent
              + "' exited non-zero in '"
              + project
              + "' (see "
              + unit.logPath()
              + "): "
              + result.stderr());
    }

    return StreamJsonResult.extract(readLogSince(project, unit, startOffset));
  }

  /**
   * The current run's output only: the bytes appended to the review's log since {@code
   * startOffset}. The log accumulates the attempt's whole negotiation (reviewer, then fix, then any
   * later stage), so reading from the offset keeps this invocation's findings from being mistaken
   * for an earlier one's (which would stall the loop for a plain, non-stream-json agent whose
   * output carries no per-run delimiter).
   */
  private String readLogSince(String project, AgentUnit unit, long startOffset) throws Exception {
    var result =
        shell.exec(
            ContainerExec.asDevUser(
                project, List.of("tail", "-c", "+" + (startOffset + 1), unit.logPath())));
    return result.ok() ? result.stdout() : "";
  }

  private long logSize(String project, AgentUnit unit) throws Exception {
    var result =
        shell.exec(ContainerExec.asDevUser(project, List.of("stat", "-c", "%s", unit.logPath())));
    try {
      return Long.parseLong(result.stdout().trim());
    } catch (RuntimeException e) {
      return 0;
    }
  }
}
