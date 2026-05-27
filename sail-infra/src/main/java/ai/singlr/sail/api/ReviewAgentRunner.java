/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

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
   * @return the agent's raw stdout output
   * @throws Exception if the agent fails to start or exits with an error
   */
  String run(String project, String agent, String prompt) throws Exception;
}
