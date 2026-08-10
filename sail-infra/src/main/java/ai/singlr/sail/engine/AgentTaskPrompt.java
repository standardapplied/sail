/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.Spec;
import ai.singlr.sail.store.MessageStore;
import java.util.List;

/**
 * Builds the task prompt handed to an agent when a spec is dispatched. Shared by the CLI dispatch
 * command and the control-plane dispatch operation so both produce an identical prompt.
 */
public final class AgentTaskPrompt {

  private AgentTaskPrompt() {}

  /** Renders the dispatch prompt for {@code spec}, appending the spec description/body. */
  public static String build(Spec spec, String description) {
    return build(spec, description, List.of());
  }

  public static String build(
      Spec spec, String description, List<MessageStore.MessageRow> messages) {
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
        + conversation(messages)
        + description
        + autonomousProtocol(spec);
  }

  private static String conversation(List<MessageStore.MessageRow> messages) {
    if (messages.isEmpty()) {
      return "";
    }
    return "## Conversation on this spec\n\n"
        + PromptConversation.renderNewest(
            messages,
            message ->
                message.author() + " (" + message.createdAt() + "):\n" + message.body() + "\n\n");
  }

  /**
   * The autonomous-operation protocol, appended to the dispatch prompt only — it applies to a
   * headless dispatched run, not to an engineer's interactive session, so it lives here rather than
   * in the always-loaded context file. Review is enforced server-side by the review pipeline when
   * the agent stops, so the prompt stays generic.
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
        the full local verification the project uses (including any coverage or lint gates), commit
        with a clear message, push the branch, and open a pull request.

        Post progress, questions, and your final summary to this spec's room with
        `spec comment <id> --body <text>` (or `--body -` for stdin).

        The room is a live channel, not a log: replies posted while you work are delivered into
        your context automatically after a tool call finishes, and unread messages block your
        first attempt to stop. When you are blocked on a decision, read the room with
        `spec comments <id>` rather than guessing; read it once more before posting your final
        summary, and acknowledge in that summary any guidance it carried.

        The spec is not complete until CI is green: after opening the pull request, watch its
        checks with the CLI of the forge hosting the repo (e.g. `gh pr checks <number> --watch`
        on GitHub, `glab ci status --live` on GitLab, or the equivalent on your forge), and if any
        check fails, diagnose it, fix it on the branch, push, and watch again until every check
        passes.

        Never add AI attribution to the work: no Co-Authored-By trailers and no "Generated with"
        footers in commit messages or pull request descriptions.
        """
        + multiRepo
        + "If the build fails repeatedly on the same error, or three different approaches fail, stop"
        + " and report rather than retrying. Never leave work uncommitted — a WIP commit beats lost"
        + " work.\n";
  }
}
