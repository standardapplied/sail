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
   * Commits and pushes any work an agent left uncommitted on the spec's branch. The fix lane runs
   * hook-free (so a reviewer's completion can never re-enter the pipeline), which also strips the
   * dispatch lane's stop-readiness gate — so nothing stops a fix agent from ending its run with a
   * dirty tree, contaminating the shared clone and starving the re-review of the very fixes it is
   * about to judge. This is the deterministic backstop: a repo is rescued only when it is checked
   * out on the spec's own branch, never any other.
   *
   * @return the repos that had uncommitted work, now committed
   */
  default List<String> ensureCommitted(String project, List<String> repos, String branch)
      throws Exception {
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
