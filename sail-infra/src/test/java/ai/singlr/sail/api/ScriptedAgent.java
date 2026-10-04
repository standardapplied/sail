/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.List;

/**
 * What the agents of a scripted review loop say: the final answer of each reviewer and fix agent
 * {@link ReviewLoop} launches, given what it was launched with. One method covers both lanes, so a
 * lambda scripts a whole loop by the order of its calls; a script that tells the lanes apart
 * overrides {@link #runFix}. A script that throws is an agent that exited non-zero.
 */
@FunctionalInterface
interface ScriptedAgent {

  String run(String project, String agent, String prompt, String reviewId, String runCredential)
      throws Exception;

  /** A reviewer's answer, given the model and reasoning effort it was launched with. */
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

  /** A fix agent's answer, given the branch and repos its stop gate was scoped to. */
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
}
