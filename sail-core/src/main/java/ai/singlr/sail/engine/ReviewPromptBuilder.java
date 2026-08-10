/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.MessageStore;
import java.util.List;

/**
 * Builds structured review prompts for review agents. The prompt instructs the agent to respond
 * with the verdict envelope — a ruling on every finding carried forward from the previous review,
 * plus any newly discovered findings — as one JSON object. Every finding must include evidence;
 * unsubstantiated reports are explicitly excluded, and a carried finding can only be retired by a
 * verdict with evidence, never by omission.
 */
public final class ReviewPromptBuilder {

  private ReviewPromptBuilder() {}

  /**
   * @param repos the spec's target repository directory names inside the workspace — the actual
   *     checkouts to review, never the project name (a multi-repo workspace root contains several
   *     repos, and a wrong name sends the reviewer into an unrelated codebase)
   */
  public static String build(String branch, List<String> repos, List<String> categories) {
    return build(branch, repos, categories, List.of(), List.of());
  }

  public static String build(
      String branch,
      List<String> repos,
      List<String> categories,
      List<MessageStore.MessageRow> messages) {
    return build(branch, repos, categories, messages, List.of());
  }

  /**
   * @param carried the previous review's still-open findings, which the reviewer must rule on: one
   *     verdict per carried finding, alongside (not instead of) any new findings
   */
  public static String build(
      String branch,
      List<String> repos,
      List<String> categories,
      List<MessageStore.MessageRow> messages,
      List<Finding> carried) {
    var categoryList =
        categories.isEmpty() ? "any relevant category" : String.join(", ", categories);
    var repoList = repos.isEmpty() ? "the repository in the workspace" : String.join(", ", repos);

    return conversation(messages)
        + """
        Review the changes on branch %s in the following repository director%s inside this
        workspace: %s. Review only those checkouts — ignore any other repositories present.
        If that branch no longer exists, review the spec's changes as merged on the default
        branch instead; do not go hunting for the missing ref.

        Focus on these categories: %s

        """
            .formatted(branch, repos.size() == 1 ? "y" : "ies", repoList, categoryList)
        + carryForward(carried)
        + """
        Respond with exactly one JSON object — the verdict envelope:
        {"verdicts": [<one verdict per carried finding>], "findings": [<new findings only>]}

        Each entry in "verdicts" rules on one carried finding listed above and must have:
        - finding_id: the id exactly as listed above
        - verdict: "fixed", "still_open", or "disputed"
        - evidence: required for fixed (cite the commit or current code that resolves it) and
          for disputed (state why the finding is wrong; an argument posted in the conversation
          above counts). A fixed or disputed verdict without evidence is treated as still_open.
        If no carried findings are listed above, "verdicts" must be an empty array.

        Each entry in "findings" reports a NEW issue (never repeat a carried finding) and must have:
        - severity: CRITICAL, HIGH, MEDIUM, or LOW
        - category: one of SECURITY, LOGIC, EDGE_CASE, PERFORMANCE, ERROR_HANDLING, CONCURRENCY, RESOURCE_LEAK, API_CONTRACT
        - file: relative file path
        - line_start: first affected line number
        - line_end: last affected line number (same as line_start for single-line issues)
        - title: one-line summary of the issue
        - description: detailed explanation of the problem
        - evidence: proof this is a real issue (data flow trace, failing test case, CVE reference, or logical argument)
        - suggestion: an object with "before" (current code), "after" (fixed code), and "rationale" (why the fix works)
        - confidence: 0.0 to 1.0 indicating your certainty

        Rules:
        1. Only report genuine issues. Do not flag style preferences or working code.
        2. Every finding MUST include evidence. If you cannot prove it, do not report it.
        3. Every finding MUST include a concrete suggestion with before/after code.
        4. Focus on correctness, security, and reliability — not formatting.
        5. Rule on EVERY carried finding: a carried finding you do not mention is treated as
           still_open, so silence never resolves anything.
        6. If there are no new issues, "findings" must be an empty array.

        Begin the JSON object with ```json and end with ```.
        """;
  }

  /**
   * The carry-forward contract: the previous review's open findings, each with the id the verdict
   * must cite. Rendered before the response-format instructions so the reviewer reads what it must
   * rule on before it reads how to answer.
   */
  private static String carryForward(List<Finding> carried) {
    if (carried.isEmpty()) {
      return "";
    }
    var lines =
        carried.stream()
            .map(
                f ->
                    "- finding_id %s [%s] %s%s"
                        .formatted(f.id(), f.severity(), f.title(), location(f)))
            .reduce((a, b) -> a + "\n" + b)
            .orElse("");
    return """
        The previous review left these findings open. Re-examine each one against the current
        state of the branch and include a verdict for every single one in "verdicts":

        %s

        """
        .formatted(lines);
  }

  private static String location(Finding f) {
    if (f.file() == null) {
      return "";
    }
    var span = f.lineEnd() != f.lineStart() ? "-" + f.lineEnd() : "";
    return " (" + f.file() + ":" + f.lineStart() + span + ")";
  }

  private static String conversation(List<MessageStore.MessageRow> messages) {
    if (messages.isEmpty()) {
      return "";
    }
    return "Conversation on this spec:\n\n"
        + PromptConversation.renderNewest(
                messages, message -> message.author() + ": " + message.body() + "\n\n")
            .text();
  }
}
