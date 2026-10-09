/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.Spec;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.MessageStore;
import java.util.List;

/**
 * The one prompt an agent that works a spec reads: the build's, and the fix run's, which is the
 * build's with one section added, the findings. It says what the work is (the spec and its room),
 * what the loop enforces of every work run (the room protocol, commit and push, a pull request,
 * green CI, nothing uncommitted) and the advice sail gives every build. How the codebase is built,
 * tested and verified is the repository's own to say, in its {@code AGENTS.md} and skills, which
 * the harness loads itself; nothing a project writes replaces, prepends or appends to this text.
 */
public final class WorkPrompt {

  private WorkPrompt() {}

  /**
   * A built prompt and the room messages it rendered in full — the exact set the caller may
   * acknowledge as delivered at launch. Delivery derives from presentation: a message the budget
   * truncated or omitted is absent here and stays owed a full delivery.
   */
  public record Built(String prompt, List<MessageStore.MessageRow> renderedMessages) {}

  /**
   * The prompt for {@code spec}: its header, the room, {@code body}, the findings section when
   * {@code findings} is not empty (a fix run), and how the run works.
   */
  public static Built build(
      Spec spec, String body, List<MessageStore.MessageRow> messages, List<Finding> findings) {
    var conversation =
        PromptConversation.renderNewest(
            messages,
            message ->
                message.author() + " (" + message.createdAt() + "):\n" + message.body() + "\n\n");
    var prompt =
        header(spec)
            + "\n"
            + (conversation.text().isEmpty()
                ? ""
                : "## Conversation on this spec\n\n" + conversation.text())
            + body
            + "\n"
            + (findings.isEmpty() ? "" : findingsSection(spec.id(), findings))
            + howThisRunWorks(spec);
    return new Built(prompt, conversation.fullyRendered());
  }

  private static String header(Spec spec) {
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
    var targets = targetRepos + targetAgent + targetModel + targetReasoning;
    return "Your current spec: \""
        + spec.title()
        + "\" (id: "
        + spec.id()
        + ")."
        + (targets.isEmpty() ? "\n" : targets);
  }

  /**
   * The findings of a fix run, each with its id, severity, category, file and lines, evidence and
   * suggestion. A carried finding also presents the {@code still_open} ruling's evidence as a
   * reproduction claim, so a fix agent that believes the finding already fixed must answer the
   * reviewer's exact scenario — with code or a dispute argument, never a bare re-claim. A finding
   * the agent believes wrong is argued in the spec room for the re-review to rule on, never coded
   * around and never silently skipped.
   */
  private static String findingsSection(String specId, List<Finding> findings) {
    var sb = new StringBuilder();
    sb.append(
        """

        ## Review findings on your branch

        Your implementation received %d review finding(s). Address each one below; the reviewer
        re-checks the branch after you push.
        If you believe a finding is wrong, do NOT code around it and do NOT silently skip it: post
        your argument to the spec room (spec comment %s --body "...") naming the finding's id, and
        leave that code alone. The re-review reads the room and rules fixed, still_open, or disputed
        with your argument as evidence — a finding is retired by argument in the open, never by
        omission.

        """
            .formatted(findings.size(), specId));
    for (var i = 0; i < findings.size(); i++) {
      sb.append(rendered(i + 1, findings.get(i)));
    }
    sb.setLength(sb.length() - 1);
    return sb.toString();
  }

  private static String rendered(int number, Finding f) {
    var sb = new StringBuilder();
    sb.append("--- Finding %d [%s] %s ---\n".formatted(number, f.severity(), f.category()));
    sb.append("Id: %s\n".formatted(f.id()));
    sb.append("Title: %s\n".formatted(f.title()));
    if (f.file() != null) {
      sb.append("File: %s:%d".formatted(f.file(), f.lineStart()));
      if (f.lineEnd() != f.lineStart()) {
        sb.append("-%d".formatted(f.lineEnd()));
      }
      sb.append("\n");
    }
    if (!f.description().isEmpty()) {
      sb.append("Issue: %s\n".formatted(f.description()));
    }
    if (f.evidence() != null && !f.evidence().isEmpty()) {
      sb.append("Evidence: %s\n".formatted(f.evidence()));
    }
    if (f.carryEvidence() != null && !f.carryEvidence().isBlank()) {
      sb.append(
          """
          Reviewer's evidence that this remains open: %s
          Treat this as a reproduction claim — investigate this exact scenario before
          re-claiming it fixed; if your investigation shows the scenario cannot occur,
          dispute with that analysis.
          """
              .formatted(f.carryEvidence()));
    }
    if (f.suggestion() != null && !f.suggestion().rationale().isEmpty()) {
      sb.append("Fix: %s\n".formatted(f.suggestion().rationale()));
      if (!f.suggestion().before().isEmpty()) {
        sb.append("  Before: %s\n".formatted(f.suggestion().before()));
      }
      if (!f.suggestion().after().isEmpty()) {
        sb.append("  After:  %s\n".formatted(f.suggestion().after()));
      }
    }
    sb.append("\n");
    return sb.toString();
  }

  /**
   * What the loop enforces of a work run and the advice sail gives every one, the same on a build
   * and on a fix. The checks are polled in short calls because the stall guard counts from the
   * agent's last tool call or log chunk: a single watch longer than the idle window produces
   * neither and kills a run that is only waiting.
   */
  private static String howThisRunWorks(Spec spec) {
    var multiRepo =
        spec.repos().size() > 1
            ? " This spec spans multiple repos: branch, commit, and open a linked pull request in"
                + " each affected repo."
            : "";
    return """

        ## How this run works

        You are running unattended in a sail container. Act without waiting for confirmation: plan,
        implement, test, commit. Stay on the current branch.

        This repository's own instructions (AGENTS.md and the project's skills) say how it is built,
        tested and verified. Follow them, and run the project's full verification (its tests, coverage
        and lint gates) before you commit; sail runs none of it for you.

        Post progress, questions, and your final summary to this spec's room with
        `spec comment <id> --body <text>` (or `--body -` for stdin).

        The room is a live channel, not a log: replies posted while you work are delivered into
        your context automatically after a tool call finishes, and unread messages block your
        first attempt to stop. When you are blocked on a decision, read the room with
        `spec comments <id>` rather than guessing; if the room does not resolve it, post the
        question with `spec comment <id> --question --body <text>` — the flag pages the
        engineer on the board, and their reply clears it and wakes you. Read the room once more
        before posting your final summary, and acknowledge in that summary any guidance it
        carried.

        The spec's status is sail's: never change it.

        Never add AI attribution to the work: no Co-Authored-By trailers and no "Generated with"
        footers in commit messages or pull request descriptions.

        If the same error defeats three attempts, or three different approaches fail, stop and say so
        in the room rather than retrying.

        When the work is complete, commit with a clear message, push the branch, and make sure a pull
        request is open for it.\
        """
        + multiRepo
        + """
        \sThe work is not complete until CI is green: check the pull request's checks with the
        forge's CLI (`gh pr checks <number>` on GitHub, `glab ci status` on GitLab, or your forge's
        equivalent) in short polls, each well under 20 minutes, never one long watch; if a check
        fails, diagnose it, fix it on the branch, push, and check again until every check passes. If
        no authenticated forge CLI is available in this container, say so in the room with the pull
        request's URL and end your turn; do not claim CI green.

        Never leave work uncommitted — a WIP commit beats lost work. Uncommitted files contaminate the
        next run in this shared clone.
        """;
  }
}
