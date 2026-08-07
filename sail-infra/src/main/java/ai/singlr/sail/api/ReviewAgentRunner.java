/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.List;

/**
 * Runs a review agent in a container and returns its raw output. The controller delegates agent
 * execution to this interface so the orchestration logic is testable without containers.
 */
@FunctionalInterface
public interface ReviewAgentRunner {

  /**
   * Launches a review agent inside the project container and blocks until it completes.
   *
   * @param project container/project name
   * @param agent agent CLI type (e.g., "codex", "claude-code")
   * @param prompt the review task prompt
   * @param reviewId the owning review; selects the review's own prompt and log files so pipelines
   *     running concurrently on the executor never share or truncate each other's state
   * @param runCredential the review run's plaintext credential, injected as {@code
   *     SAIL_RUN_CREDENTIAL} so the agent's spec and event writes authenticate as the principal
   *     recorded on its run; blank launches the agent without an identity
   * @return the agent's raw stdout output
   * @throws Exception if the agent fails to start or exits with an error
   */
  String run(String project, String agent, String prompt, String reviewId, String runCredential)
      throws Exception;

  /**
   * Same as {@link #run(String, String, String, String, String)} but carrying the spec's tuning: a
   * spec dispatched at {@code xhigh} reasoning effort must be judged at {@code xhigh}, not at the
   * CLI default. {@code model} is passed only when the lane runs the spec's own agent — model names
   * are agent-specific and the roster reviewer is usually the other agent.
   */
  default String run(
      String project,
      String agent,
      String prompt,
      String reviewId,
      String runCredential,
      String model,
      String reasoningEffort)
      throws Exception {
    return run(project, agent, prompt, reviewId, runCredential);
  }

  /**
   * Launches the review's fix agent. Unlike a reviewer, a fix agent writes to the spec branch, so
   * it runs with the stop gate armed ({@code SAIL_RUN_ID} exported, session file stamped with the
   * spec's {@code branch} and {@code repos}) — the dispatch lane's commit discipline lives in that
   * gate, not in the prompt, and an ungated fix agent ends its turn with a dirty tree every time.
   * It still exports no {@code SAIL_SPEC_ID}, so the event helper stays silent and the fix agent's
   * own stop can never re-enter the pipeline. The fix agent is the spec's own agent, so it carries
   * the spec's {@code model} and {@code reasoningEffort} exactly as the dispatch lane did.
   */
  default String runFix(
      String project,
      String agent,
      String prompt,
      String reviewId,
      String runCredential,
      String branch,
      List<String> repos,
      String model,
      String reasoningEffort)
      throws Exception {
    return run(project, agent, prompt, reviewId, runCredential, model, reasoningEffort);
  }

  /** One rescued repo and the files the rescue commit swept up, for the guardrail event. */
  record Rescue(String repo, List<String> files) {}

  /**
   * Commits and pushes any work an agent left uncommitted on the spec's branch, using {@code
   * commitMessage} so the commit explains the work it contains. The fix lane runs gated, but the
   * gate is a nudge, not a jail — a fix agent can still end its second stop with a dirty tree,
   * contaminating the shared clone and starving the re-review of the very fixes it is about to
   * judge. This is the deterministic backstop: a repo is rescued only when it is checked out on the
   * spec's own branch, never any other.
   *
   * @return the repos that had uncommitted work, now committed, each with the files it swept
   */
  default List<Rescue> ensureCommitted(
      String project, List<String> repos, String branch, String commitMessage) throws Exception {
    return List.of();
  }
}

final class ReviewAgentExecutionException extends IllegalStateException {

  private final int exitCode;

  ReviewAgentExecutionException(String message, int exitCode) {
    super(message);
    this.exitCode = exitCode;
  }

  int exitCode() {
    return exitCode;
  }
}
