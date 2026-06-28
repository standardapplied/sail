/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.Spec;

/**
 * Builds the task prompt handed to an agent when a spec is dispatched. Shared by the CLI dispatch
 * command and the control-plane dispatch operation so both produce an identical prompt.
 */
public final class AgentTaskPrompt {

  private AgentTaskPrompt() {}

  /** Renders the dispatch prompt for {@code spec}, appending the spec description/body. */
  public static String build(Spec spec, String description) {
    var targetRepos =
        spec.repos().isEmpty()
            ? ""
            : "\nTarget repo"
                + (spec.repos().size() == 1 ? "" : "s")
                + ": "
                + String.join(", ", spec.repos())
                + "\n";
    var targetAgent = spec.agent() == null ? "" : "\nTarget agent: " + spec.agent() + "\n";
    var targetModel = spec.model() == null ? "" : "\nTarget model: " + spec.model() + "\n";
    var targetReasoning =
        spec.reasoningEffort() == null
            ? ""
            : "\nTarget reasoning effort: " + spec.reasoningEffort() + "\n";
    return "Your current spec: \""
        + spec.title()
        + "\" (id: "
        + spec.id()
        + ")."
        + targetRepos
        + targetAgent
        + targetModel
        + targetReasoning
        + "\n"
        + description
        + autonomousProtocol(spec);
  }

  /**
   * The autonomous-operation protocol, appended to the dispatch prompt only — it applies to a
   * headless dispatched run, not to an engineer's interactive session, so it lives here rather than
   * in the always-loaded context file. The security-audit and code-review steps are enforced by the
   * post-task hooks, so the prompt stays generic.
   */
  private static String autonomousProtocol(Spec spec) {
    var multiRepo =
        spec.repos().size() > 1
            ? "\nThis spec spans multiple repos: branch, commit, and open a linked pull request in"
                + " each affected repo.\n"
            : "";
    return """

        ## Autonomous Operation
        Execute without waiting for confirmation: plan, implement, test, commit. When complete, run
        the tests, commit with a clear message, push the branch, and open a pull request.
        """
        + multiRepo
        + "If the build fails repeatedly on the same error, or three different approaches fail, stop"
        + " and report rather than retrying. Never leave work uncommitted — a WIP commit beats lost"
        + " work.\n";
  }
}
